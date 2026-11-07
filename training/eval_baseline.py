"""基线评测：对评测集调用本地 gateway（或任意 OpenAI 兼容端点）做意图分类并输出指标。

用法：
  python eval_baseline.py --eval-set data/splits/eval_v1.jsonl --model qwen2.5-7b --tag v1
  python eval_baseline.py --dry-run
"""
import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import config
import prompt
import rewards
from config import INTENT_CODES

# 网关后端不可用时会在 HTTP 200 里返回 mock 兜底文本；这类响应不能计入模型准确率
DEGRADED_MARKERS = ("（知识库为空）", "该问题超出知识库范围")


class DegradedBackend(RuntimeError):
    """网关降级（mock/空文本）：属于基础设施问题，需重试而非判为答错。"""


def load_eval(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def classify(question: str, model: str, retries: int = 3, cooldown: float = 12.0,
             interval: float = 0.4) -> tuple:
    """返回 (intent, backend, attempts)。降级/失败退避重试，重试耗尽抛 DegradedBackend。"""
    import requests
    last_backend = ""
    for attempt in range(1, retries + 1):
        resp = requests.post(
            config.GATEWAY_BASE.rstrip("/") + "/chat/completions",
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + config.GATEWAY_KEY},
            json={"model": model, "messages": prompt.build_messages(question),
                  "temperature": 0.0, "max_tokens": 48, "use_kb": False},
            timeout=config.REQUEST_TIMEOUT,
        )
        resp.raise_for_status()
        data = resp.json()
        meta = data.get("lanlink") or {}
        last_backend = str(meta.get("backend") or "")
        try:
            text = data["choices"][0]["message"]["content"]
        except Exception:
            text = json.dumps(data, ensure_ascii=False)
        degraded = last_backend.startswith("mock") or any(mk in (text or "") for mk in DEGRADED_MARKERS)
        if degraded:
            wait = cooldown * attempt
            print(f"  [degraded] backend={last_backend or '?'} 第{attempt}次，冷却 {wait:.0f}s 后重试")
            time.sleep(wait)
            continue
        time.sleep(interval)
        return prompt.parse_intent(text), last_backend, attempt
    raise DegradedBackend(f"gateway degraded after {retries} tries (backend={last_backend or '?'})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eval-set", default=str(config.DATA_DIR / "splits" / "eval_v1.jsonl"))
    ap.add_argument("--model", default=config.INTENT_MODEL)
    ap.add_argument("--tag", default="v1")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--cooldown", type=float, default=12.0)
    ap.add_argument("--interval", type=float, default=0.4)
    ap.add_argument("--max-degraded", type=int, default=3,
                    help="仍降级的条目数超过该值则判定本次评测无效，不写报告")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    rows = load_eval(args.eval_set)
    if args.limit:
        rows = rows[: args.limit]
    dist = {}
    for r in rows:
        dist[r["intent"]] = dist.get(r["intent"], 0) + 1
    sha = hashlib.sha256(Path(args.eval_set).read_bytes()).hexdigest()
    print(json.dumps({"eval_set": args.eval_set, "eval_sha256": sha,
                      "eval_size": len(rows), "distribution": dist}, ensure_ascii=False, indent=2))
    if args.dry_run:
        return
    if not config.GATEWAY_KEY:
        print("[error] 缺少 AI_GATEWAY_API_KEY（根 .env 或环境变量）", file=sys.stderr)
        sys.exit(2)

    y_true, y_pred, errors = [], [], []
    backends, attempts = [], []
    degraded_rows = 0
    for i, r in enumerate(rows, 1):
        try:
            pred, backend, attempt = classify(r["question"], args.model, args.retries,
                                              args.cooldown, args.interval)
        except DegradedBackend as exc:
            pred, backend, attempt = "", "degraded", args.retries
            degraded_rows += 1
            errors.append({"question": r["question"], "error": str(exc)})
        except Exception as exc:
            pred, backend, attempt = "", "error", 0
            errors.append({"question": r["question"], "error": str(exc)})
        y_true.append(r["intent"]); y_pred.append(pred)
        backends.append(backend); attempts.append(attempt)
        if i % 25 == 0:
            print(f"  ... {i}/{len(rows)} (degraded={degraded_rows})")

    if degraded_rows > args.max_degraded:
        print(f"[abort] 网关降级 {degraded_rows} 条 > 阈值 {args.max_degraded}，"
              f"本次评测无效（指标会被基础设施问题污染），不写报告", file=sys.stderr)
        sys.exit(3)

    scored = [(t, p) for t, p, b in zip(y_true, y_pred, backends) if b != "degraded"]
    report = rewards.evaluate([t for t, _ in scored], [p for _, p in scored], INTENT_CODES)
    report.update({
        "model": args.model, "tag": args.tag, "eval_set": args.eval_set,
        "eval_sha256": sha,
        "eval_rows_total": len(rows), "scored_rows": len(scored),
        "error_count": len(errors), "degraded_count": degraded_rows,
        "retry_total": sum(a - 1 for a in attempts if a > 0),
        "backend_mix": {b: backends.count(b) for b in sorted(set(backends))},
        "errors": errors[:20],
        "predictions": [{"q": r["question"], "gold": t, "pred": p, "backend": b}
                        for r, t, p, b in zip(rows, y_true, y_pred, backends)],
    })
    config.REPORT_DIR.mkdir(parents=True, exist_ok=True)
    safe_model = args.model.replace("/", "_").replace(":", "_")
    out = config.REPORT_DIR / f"baseline_{args.tag}_{safe_model}.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("model", "total", "accuracy", "accuracy_ci95", "macro_f1")},
                     ensure_ascii=False, indent=2))
    print("report ->", out)


if __name__ == "__main__":
    main()

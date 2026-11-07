"""构建意图分类数据集：MySQL 意图日志 + 回归用例 + gateway trace 未标注池。

产出：
  data/intent_train.jsonl / data/intent_eval.jsonl  —— {"question","intent","label_source"}
  data/intent_unlabeled.jsonl                       —— {"question","source"} 待标注/自监督
用法：python dataset_build.py [--min-per-class 20] [--eval-ratio 0.2]
"""
import argparse
import json
import random
import sys
from pathlib import Path

import config
from config import ENTRY_TO_INTENT, INTENT_CODES, REG_CASES


def load_db_rows():
    try:
        import mysql.connector
    except Exception:
        print("[warn] mysql-connector-python 未安装，跳过 DB 意图日志", file=sys.stderr)
        return []
    try:
        conn = mysql.connector.connect(**config.DB)
        cur = conn.cursor()
        cur.execute(
            "SELECT question_text, intent FROM t_support_intent_log "
            "WHERE question_text IS NOT NULL AND CHAR_LENGTH(question_text) >= 2 "
            "AND intent IS NOT NULL AND intent <> '' ORDER BY id DESC LIMIT 5000")
        rows = [{"question": q.strip(), "intent": i.strip(), "label_source": "intent_log"}
                for q, i in cur.fetchall() if q and q.strip()]
        cur.close()
        conn.close()
        return rows
    except Exception as exc:
        print(f"[warn] 读取意图日志失败：{exc}", file=sys.stderr)
        return []


def load_reg_cases():
    rows = []
    for path in REG_CASES:
        if not Path(path).exists():
            continue
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except Exception:
            continue
        for item in data:
            q = (item.get("question") or "").strip()
            entry = item.get("expectedEntryId") or item.get("expected_entry_id") or ""
            intent = ENTRY_TO_INTENT.get(entry)
            if q and intent:
                rows.append({"question": q, "intent": intent, "label_source": "regression"})
    return rows


def load_trace_unlabeled(limit=20000):
    rows, seen = [], set()
    if not config.TRACE_DIR.exists():
        return rows
    for fp in sorted(config.TRACE_DIR.glob("*.jsonl")):
        try:
            for line in fp.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                rec = json.loads(line)
                raw = rec.get("messages") or ""
                try:
                    msgs = json.loads(raw)
                except Exception:
                    msgs = []
                user = next((m.get("content") for m in msgs if m.get("role") == "user"), "")
                if isinstance(user, str) and 2 <= len(user.strip()) <= 300 and user not in seen:
                    seen.add(user)
                    rows.append({"question": user.strip(), "source": "trace"})
                    if len(rows) >= limit:
                        return rows
        except Exception:
            continue
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--eval-ratio", type=float, default=0.2)
    ap.add_argument("--min-per-class", type=int, default=20)
    args = ap.parse_args()
    random.seed(42)

    rows = load_db_rows() + load_reg_cases()
    # 去重（同问题保留首个标签）
    dedup, seen = [], set()
    for r in rows:
        key = r["question"]
        if key and key not in seen:
            seen.add(key)
            dedup.append(r)
    # 按类分层抽 eval
    by_cls = {}
    for r in dedup:
        by_cls.setdefault(r["intent"], []).append(r)
    train, evalset = [], []
    for cls, items in by_cls.items():
        random.shuffle(items)
        k = max(1, int(len(items) * args.eval_ratio))
        evalset += items[:k]
        train += items[k:]

    config.DATA_DIR.mkdir(parents=True, exist_ok=True)

    def dump(path, data):
        with open(path, "w", encoding="utf-8") as fp:
            for r in data:
                fp.write(json.dumps(r, ensure_ascii=False) + "\n")
        return len(data)

    n_train = dump(config.DATA_DIR / "intent_train.jsonl", train)
    n_eval = dump(config.DATA_DIR / "intent_eval.jsonl", evalset)
    unlabeled = load_trace_unlabeled()
    n_unl = dump(config.DATA_DIR / "intent_unlabeled.jsonl", unlabeled)

    dist = {c: len([r for r in train if r["intent"] == c]) for c in INTENT_CODES}
    print(json.dumps({
        "train": n_train, "eval": n_eval, "unlabeled": n_unl,
        "classes": dist,
        "warn_under_min": [c for c, n in dist.items() if n < args.min_per_class],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

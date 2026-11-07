"""在冻结的 eval_v1 上直接评测 LoRA adapter（不经网关，本地 HF 生成）。

与 eval_baseline.py 用同一份 prompt.py 契约与 rewards.evaluate 指标，因此数字可与
baseline_v1 对照；但推理栈不同（本地 transformers 贪心解码 vs 网关 llama.cpp 采样），
所以这是「同数据同口径的近似对照」，最终切流前仍需用 convert_gguf.sh 回灌网关复测。

用法（WSL 内）：
  python eval_lora_local.py --adapter /root/llm/out/intent-lora-v1-7b
"""
import argparse
import json
import time
from pathlib import Path

import config
import prompt
import rewards
from config import INTENT_CODES

SPLITS = config.DATA_DIR / "splits"


def load_rows(path):
    return [json.loads(l) for l in Path(path).read_text(encoding="utf-8").splitlines() if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="unsloth/Qwen2.5-7B-Instruct-bnb-4bit")
    ap.add_argument("--adapter", required=True)
    ap.add_argument("--eval-set", default=str(SPLITS / "eval_v1.jsonl"))
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--max-new-tokens", type=int, default=24)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--tag", default="lora-v1")
    args = ap.parse_args()

    rows = load_rows(args.eval_set)
    if args.limit:
        rows = rows[: args.limit]

    import torch
    from unsloth import FastLanguageModel

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.base, adapter_name=args.adapter, max_seq_length=1024,
        full_finetuning=False, load_in_4bit=True)
    FastLanguageModel.for_inference(model)
    tokenizer.padding_side = "left"
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    y_true, y_pred, raws = [], [], []
    t0 = time.time()
    for i in range(0, len(rows), args.batch):
        chunk = rows[i:i + args.batch]
        rendered = [tokenizer.apply_chat_template(prompt.build_messages(r["question"]),
                                                  add_generation_prompt=True, tokenize=False)
                    for r in chunk]
        enc = tokenizer(rendered, return_tensors="pt", padding=True,
                        add_special_tokens=False).to("cuda")
        with torch.inference_mode():
            out = model.generate(**enc, max_new_tokens=args.max_new_tokens,
                                 do_sample=False, num_beams=1,
                                 pad_token_id=tokenizer.pad_token_id,
                                 eos_token_id=tokenizer.eos_token_id)
        gen = out[:, enc["input_ids"].shape[1]:]
        for j, r in enumerate(chunk):
            raw = tokenizer.decode(gen[j], skip_special_tokens=True).strip()
            raws.append(raw)
            y_pred.append(prompt.parse_intent(raw))
            y_true.append(r["intent"])
        done = min(i + args.batch, len(rows))
        if done % 64 < args.batch or done == len(rows):
            print(f"  ... {done}/{len(rows)} ({time.time()-t0:.0f}s)", flush=True)

    report = rewards.evaluate(y_true, y_pred, INTENT_CODES)
    report.update({
        "model": f"{args.base}+{Path(args.adapter).name}", "tag": args.tag,
        "eval_set": args.eval_set,
        "eval_sha256": __import__("hashlib").sha256(Path(args.eval_set).read_bytes()).hexdigest(),
        "harness": "local transformers greedy (非网关 llama.cpp)：同数据同提示词，可对照基线但非同一推理栈",
        "elapsed_sec": round(time.time() - t0, 1),
        "predictions": [{"q": r["question"], "gold": t, "pred": p, "raw": raws[k][:80]}
                        for k, (r, t, p) in enumerate(zip(rows, y_true, y_pred))],
    })
    config.REPORT_DIR.mkdir(parents=True, exist_ok=True)
    out_path = config.REPORT_DIR / f"{args.tag}_{Path(args.adapter).name}.json"
    out_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("total", "accuracy", "accuracy_ci95", "macro_f1")},
                     ensure_ascii=False, indent=2))
    print("report ->", out_path)


if __name__ == "__main__":
    main()

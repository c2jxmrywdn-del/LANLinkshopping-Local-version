"""意图分类 QLoRA SFT：在冻结的 v1 切分上微调 Qwen2.5，产出可插回网关的 LoRA。

数据来自 build_dataset.py：train_v1 / dev_v1 训练与验证，eval_v1 只在评测时使用，绝不进训练。
提示词与标注口径统一走 prompt.py 的严格 JSON 契约，和 IntentUnderstandingService 生产端一致。

前置（WSL2，安装见 wsl_setup.sh）：
  bash /root/llm/setup.sh

训练前必须释放显存（7B QLoRA 与现网 7B 推理不能共存）：
  docker stop lanlink-llama-cpp
  # 且根 .env 的 LLM_BACKEND 需为 auto/ollama，否则网关会一直重试已停掉的 router

用法：
  # 先用 0.5B 冒烟（约 1GB 显存，验证链路与数据格式）
  python train_intent_sft.py --base Qwen/Qwen2.5-0.5B-Instruct --max-seq-len 512 --steps 20
  # 正式：7B QLoRA（需 llama.cpp 已停）
  python train_intent_sft.py --base Qwen/Qwen2.5-7B-Instruct
"""
import argparse
import json
from pathlib import Path

import config
import prompt

SPLITS = config.DATA_DIR / "splits"


def load_rows(path):
    rows = []
    with open(path, encoding="utf-8") as fp:
        for line in fp:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def to_chat(row):
    """system/user 与生产完全一致，assistant 只输出 {"intent": "<code>"}。"""
    return {"messages": prompt.build_messages(row["question"])
            + [{"role": "assistant",
                "content": json.dumps({"intent": row["intent"]}, ensure_ascii=False)}]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="Qwen/Qwen2.5-7B-Instruct",
                    help="8GB 显存下 7B 需 llama.cpp 已停机；冒烟建议 Qwen/Qwen2.5-0.5B-Instruct")
    ap.add_argument("--train", default=str(SPLITS / "train_v1.jsonl"))
    ap.add_argument("--dev", default=str(SPLITS / "dev_v1.jsonl"))
    ap.add_argument("--epochs", type=float, default=3)
    ap.add_argument("--steps", type=int, default=-1, help=">0 时按步数冒烟，便于快速验证链路")
    ap.add_argument("--max-seq-len", type=int, default=768,
                    help="7B 在 8GB 显存上需 ≤768；本任务样本 <300 token，够用")
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=16)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--lora-r", type=int, default=16)
    ap.add_argument("--out", default=str(config.TRAIN_DIR / "out" / "intent-lora-v1"))
    ap.add_argument("--merge", action="store_true",
                    help="额外导出 merged 16bit GGUF 前置权重（7B 约占 15GB 磁盘并需较大内存）")
    args = ap.parse_args()

    train_path, dev_path = Path(args.train), Path(args.dev)
    if not train_path.exists():
        raise SystemExit(f"缺少训练集 {train_path}，请先运行 build_dataset.py")

    import torch
    from datasets import Dataset
    from unsloth import FastLanguageModel
    from trl import SFTTrainer, SFTConfig
    import inspect

    if not torch.cuda.is_available():
        raise SystemExit("CUDA 不可用：请在 WSL2 内用装好 cuXXX torch 的 venv 运行，"
                         "并确认 nvidia-smi 可见 GPU")

    ds = Dataset.from_list([to_chat(r) for r in load_rows(train_path)])
    ds_eval = (Dataset.from_list([to_chat(r) for r in load_rows(dev_path)])
               if dev_path.exists() else None)
    print(f"train={len(ds)} dev={len(ds_eval) if ds_eval else 0} base={args.base}")

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.base, max_seq_length=args.max_seq_len, load_in_4bit=True,
        dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
    )
    model = FastLanguageModel.get_peft_model(
        model, r=args.lora_r, lora_alpha=args.lora_r, lora_dropout=0.05, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
        use_gradient_checkpointing="unsloth",
        random_state=42,
    )

    # trl 1.x 改名了若干参数（tokenizer→processing_class、max_seq_length→max_length、
    # evaluation_strategy→eval_strategy）；按实际安装的签名自适应，避免版本漂移导致返工
    fields = set(SFTConfig.__dataclass_fields__)
    cfg = dict(output_dir=args.out,
               num_train_epochs=args.epochs,
               max_steps=args.steps,
               per_device_train_batch_size=args.batch,
               gradient_accumulation_steps=args.grad_accum,
               learning_rate=args.lr,
               lr_scheduler_type="cosine",
               warmup_ratio=0.03,
               optim="paged_adamw_8bit",
               bf16=torch.cuda.is_bf16_supported(),
               gradient_checkpointing=True,
               logging_steps=5,
               save_strategy="epoch" if args.steps < 0 else "no",
               report_to="none")
    if "max_length" in fields:
        cfg["max_length"] = args.max_seq_len
    elif "max_seq_length" in fields:
        cfg["max_seq_length"] = args.max_seq_len
    else:
        raise SystemExit("当前 trl 的 SFTConfig 既无 max_length 也无 max_seq_length，请检查版本")
    if ds_eval:
        eval_key = "eval_strategy" if "eval_strategy" in fields else "evaluation_strategy"
        cfg[eval_key] = "epoch"

    trainer_kwargs = dict(model=model, train_dataset=ds, eval_dataset=ds_eval,
                          args=SFTConfig(**cfg))
    trainer_kwargs["processing_class" if "processing_class" in
                   inspect.signature(SFTTrainer.__init__).parameters else "tokenizer"] = tokenizer
    trainer = SFTTrainer(**trainer_kwargs)
    trainer.train()

    adapter_dir = args.out
    model.save_pretrained(adapter_dir)
    tokenizer.save_pretrained(adapter_dir)
    print("adapter ->", adapter_dir)
    if args.merge:
        merged = adapter_dir + "-merged"
        model.save_pretrained_merged(merged, tokenizer, save_method="merged_16bit")
        print("merged ->", merged)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
agl_grpo_trainer.py: Qwen2.5-7B 基于 Agent-Lightning 轨迹与 Unsloth 4-bit QLoRA 的 GRPO 策略梯度训练器。

核心适配要点（专为 RTX 4070 Laptop 8GB 显存调优）：
1. 4-bit 量化基座：通过 unsloth 4-bit 预量化加载，模型显存锁定约 4.2GB，彻底绕开 15.2GB 的 bf16 物理限制。
2. LoRA 适配层：Rank=16, Alpha=16，全线性投影层注入，显存仅需数十 MB。
3. Paged 8-bit AdamW：显存自动按需分页至系统内存，杜绝优化器状态爆卡。
4. 群组相对优势（GRPO）：num_generations=4，组内标量奖励归一化，免去额外 Value/Critic 模型的巨大显存开销。
5. 双模数据输入：
   - AGL 模式：从 agl-server REST API 拉取多轮 Agent 真实交互轨迹与沙盒 Reward。
   - 本地回退模式：基于 train_v1 意图与回归用例自动构建 Prompt 组装数据集。
"""

import os
import sys
import json
import argparse
from typing import List, Dict, Any, Optional

# 尝试导入 unsloth, trl, torch, datasets
try:
    import torch
    from datasets import Dataset
    from unsloth import FastLanguageModel, is_bfloat16_supported
    from trl import GRPOConfig, GRPOTrainer
except ImportError:
    torch = None
    Dataset = None
    FastLanguageModel = None
    GRPOConfig = None
    GRPOTrainer = None
    is_bfloat16_supported = lambda: True

from prompt import build_messages, parse_intent, INTENTS
from shopping_agent import compute_agent_reward


def fetch_agl_trajectories(agl_url: str = "http://127.0.0.1:8181", limit: int = 256) -> List[Dict[str, Any]]:
    """从 agl-server REST API 获取终端完成的 rollouts 并提取 prompt, completion, reward"""
    import urllib.request

    try:
        req = urllib.request.Request(f"{agl_url}/v1/rollouts/terminal?limit={limit}")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            items = data.get("items", [])
    except Exception as exc:
        print(f"[Info] 无法连接 agl-server ({exc})，将切换到本地数据模式。", file=sys.stderr)
        return []

    samples = []
    for item in items:
        r_id = item.get("rollout_id")
        try:
            ev_req = urllib.request.Request(f"{agl_url}/v1/rollouts/{r_id}/events")
            with urllib.request.urlopen(ev_req, timeout=5) as ev_resp:
                events = json.loads(ev_resp.read().decode("utf-8"))
        except Exception:
            continue

        prompt_text = ""
        completion_text = ""
        reward_val = 0.0

        for ev in events:
            ev_type = ev.get("event_type")
            if ev_type == "model_request":
                d = ev.get("data", {})
                msgs = d.get("request", {}).get("messages", [])
                if msgs:
                    prompt_text = msgs[-1].get("content", "")
                choices = d.get("response", {}).get("choices", [])
                if choices:
                    completion_text = choices[0].get("message", {}).get("content", "")
            elif ev_type == "reward":
                reward_val = float(ev.get("data", {}).get("value", 0.0))

        if prompt_text and completion_text:
            samples.append({
                "prompt": prompt_text,
                "completion": completion_text,
                "reward_scalar": reward_val,
                "group_id": item.get("data_id", r_id)
            })

    print(f"[AGL] 成功从 agl-server 抓取到 {len(samples)} 条有效交互轨迹")
    return samples


def build_local_grpo_dataset(data_path: str, max_samples: int = 300) -> List[Dict[str, Any]]:
    """从本地 splits/train_v1.jsonl 构建 GRPO 引导样本"""
    samples = []
    if not os.path.exists(data_path):
        print(f"[Warning] 本地数据文件未找到: {data_path}，使用预置种子用例", file=sys.stderr)
        raw_rows = [
            {"question": "请问你们支持批量开具增值税专用发票吗？", "intent": "invoice_tax"},
            {"question": "刚下的订单想修改收货仓库地址，能改吗？", "intent": "cart_order"},
            {"question": "查询一下从上海港发出的集装箱物流轨迹", "intent": "logistics_tracking"},
            {"question": "企业商户入驻需要准备哪些经营许可资质材料？", "intent": "merchant_onboarding"},
            {"question": "密码忘记了怎么找回平台账号？", "intent": "account_access"},
            {"question": "这批钢材尺寸不符合合同技术协议，要求退款处理", "intent": "refund_aftersales"},
        ]
    else:
        raw_rows = []
        with open(data_path, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    raw_rows.append(json.loads(line))
                if len(raw_rows) >= max_samples:
                    break

    for idx, row in enumerate(raw_rows):
        q = row.get("question", "")
        exp_intent = row.get("intent", "other")
        msgs = build_messages(q)
        prompt_str = f"系统要求：输出意图与结构化客服解答。\n用户问题：{q}"

        samples.append({
            "prompt": prompt_str,
            "question": q,
            "expected_intent": exp_intent,
            "group_id": f"grp_{idx}",
        })

    return samples


def grpo_reward_function(completions, prompts=None, expected_intent=None, question=None, **kwargs) -> List[float]:
    """
    GRPO 批次奖励判别器：根据模型实际生成的 completion 计算相对奖励。
    """
    rewards = []
    for i, comp in enumerate(completions):
        exp_i = expected_intent[i] if expected_intent is not None and i < len(expected_intent) else None
        q_i = question[i] if question is not None and i < len(question) else ""

        # 尝试解析为 JSON
        clean = comp.strip()
        parsed = {}
        try:
            parsed = json.loads(clean)
        except Exception:
            # 宽容 Markdown 包裹
            if "{" in clean and "}" in clean:
                s = clean[clean.find("{"):clean.rfind("}") + 1]
                try:
                    parsed = json.loads(s)
                except Exception:
                    parsed = {"raw_content": clean}
            else:
                parsed = {"raw_content": clean}

        # 计算复合奖励
        score, _ = compute_agent_reward(
            question=q_i,
            expected_intent=exp_i,
            case_meta={},
            final_output=parsed,
            trace=[]
        )
        rewards.append(score)

    return rewards


def run_grpo_training(
    model_name: str = "unsloth/Qwen2.5-7B-Instruct-bnb-4bit",
    output_dir: str = "/root/llm/out/qwen7b-agl-grpo-v1",
    data_path: str = "data/splits/train_v1.jsonl",
    agl_url: str = "http://127.0.0.1:8181",
    num_generations: int = 4,
    learning_rate: float = 5e-6,
    max_steps: int = 50,
    dry_run: bool = False,
):
    print(f"=== [1/4] 检查训练环境与权重: {model_name} ===")
    if FastLanguageModel is None:
        print("[Error] 当前环境未安装 unsloth 或 trl，请在 WSL2 /root/llm/venv 环境下运行！", file=sys.stderr)
        if not dry_run:
            sys.exit(1)

    print(f"=== [2/4] 准备样本数据集 (AGL / 本地回退) ===")
    agl_samples = fetch_agl_trajectories(agl_url)
    if agl_samples:
        print(f"使用 AGL 实时收集的轨迹数据集 ({len(agl_samples)} 条)")
        raw_dataset = agl_samples
    else:
        print(f"使用本地数据构建 GRPO 训练集 ({data_path})")
        raw_dataset = build_local_grpo_dataset(data_path)

    print(f"样本总数: {len(raw_dataset)} 条, GRPO Group Size: {num_generations}")
    train_dataset = Dataset.from_list(raw_dataset) if Dataset is not None else raw_dataset

    if dry_run:
        print("[Dry-Run] 样本首条展示:")
        print(json.dumps(raw_dataset[0], ensure_ascii=False, indent=2))
        print("[Dry-Run] 环境与参数校验通过，退出。")
        return

    print("=== [3/4] 载入 4-bit Qwen2.5-7B 模型与 LoRA 适配层 ===")
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=model_name,
        max_seq_length=768,
        load_in_4bit=True,
        fast_inference=False,
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=16,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        lora_alpha=16,
        lora_dropout=0.0,
        bias="none",
        use_gradient_checkpointing="unsloth",
        random_state=42,
    )

    print("=== [4/4] 配置 8GB 极致显存参数并启动 GRPOTrainer ===")
    training_args = GRPOConfig(
        output_dir=output_dir,
        learning_rate=learning_rate,
        adam_beta1=0.9,
        adam_beta2=0.99,
        weight_decay=0.01,
        warmup_ratio=0.1,
        lr_scheduler_type="cosine",
        logging_steps=1,
        bf16=is_bfloat16_supported(),
        fp16=not is_bfloat16_supported(),
        per_device_train_batch_size=1,
        gradient_accumulation_steps=8,
        num_generations=num_generations,
        max_prompt_length=256,
        max_completion_length=512,
        max_steps=max_steps,
        save_strategy="steps",
        save_steps=min(20, max(1, max_steps)),
        optim="paged_adamw_8bit",
        report_to="none",
    )

    trainer = GRPOTrainer(
        model=model,
        processing_class=tokenizer,
        reward_funcs=[grpo_reward_function],
        args=training_args,
        train_dataset=train_dataset,
    )

    print(">>> 启动 GRPO 强化学习训练循环...")
    trainer.train()

    print(f">>> 保存进化后的 LoRA 适配器权重至: {output_dir}")
    trainer.save_model(output_dir)
    model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)
    print(">>> 7B GRPO 训练完成！")


def main():
    parser = argparse.ArgumentParser(description="Qwen2.5-7B Agent-Lightning GRPO Trainer")
    parser.add_argument("--base", type=str, default="unsloth/Qwen2.5-7B-Instruct-bnb-4bit", help="基座模型")
    parser.add_argument("--out", type=str, default="/root/llm/out/qwen7b-agl-grpo-v1", help="LoRA 输出目录")
    parser.add_argument("--data", type=str, default="data/splits/train_v1.jsonl", help="数据路径")
    parser.add_argument("--agl-url", type=str, default="http://127.0.0.1:8181", help="AGL Server URL")
    parser.add_argument("--generations", type=int, default=4, help="GRPO 每组采样数")
    parser.add_argument("--steps", type=int, default=30, help="训练步数")
    parser.add_argument("--lr", type=float, default=5e-6, help="学习率")
    parser.add_argument("--dry-run", action="store_true", help="校验模式，不实际启动 GPU 训练")
    args = parser.parse_args()

    run_grpo_training(
        model_name=args.base,
        output_dir=args.out,
        data_path=args.data,
        agl_url=args.agl_url,
        num_generations=args.generations,
        learning_rate=args.lr,
        max_steps=args.steps,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()

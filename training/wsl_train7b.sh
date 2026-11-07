#!/usr/bin/env bash
# 7B QLoRA 训练：先取 4bit 预量化权重（避免 fp16 15GB 载入撑爆内存），再在冻结 train_v1 上训练。
# 前提：lanlink-llama-cpp 已停（释放 6.5GB 显存 + 4.1GB 内存），日志落 /mnt/c 便于 Windows 侧读。
exec > /mnt/c/Users/35144/AppData/Local/Temp/llm_train7b.log 2>&1
set -uo pipefail
export HF_HOME=/root/llm/hf
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
PY=/root/llm/venv/bin/python
MODEL=unsloth/Qwen2.5-7B-Instruct-bnb-4bit

echo "== 基线状态 $(date -Is)"
free -m | head -2
nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader
docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null | grep -E "llama|gateway|ollama" || true

echo "== [1/2] 预下载 4bit 权重 $MODEL (HF_ENDPOINT=$HF_ENDPOINT)"
"$PY" - <<PYEOF
import os
from huggingface_hub import snapshot_download
p = snapshot_download("$MODEL", allow_patterns=["*.json","*.safetensors","*.txt","*.model","*.jinja"])
print("cached at", p)
print("size_gb", round(sum(os.path.getsize(os.path.join(r,f))
      for r,_,fs in os.walk(p) for f in fs)/2**30, 2))
PYEOF
dl=$?
echo "download rc=$dl"
[ "$dl" = 0 ] || { echo "FATAL: 权重下载失败，训练未开始"; exit 1; }

echo "== [2/2] QLoRA 训练 $(date -Is)"
cd /root/llm/training && "$PY" train_intent_sft.py \
  --base "$MODEL" \
  --epochs 3 --max-seq-len 768 --batch 1 --grad-accum 16 --lr 2e-4 --lora-r 16 \
  --out /root/llm/out/intent-lora-v1-7b
rc=$?
echo "== 训练结束 rc=$rc $(date -Is)"
ls -l /root/llm/out/intent-lora-v1-7b 2>/dev/null | head -12
free -m | head -2
nvidia-smi --query-gpu=memory.used --format=csv,noheader

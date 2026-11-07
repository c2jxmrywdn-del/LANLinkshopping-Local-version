#!/usr/bin/env bash
# 训练完成后：在同一份冻结 eval_v1 上评测 7B LoRA，并把报告回写到仓库 training/reports。
exec > /mnt/c/Users/35144/AppData/Local/Temp/llm_eval7b.log 2>&1
set -uo pipefail
export HF_HOME=/root/llm/hf
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
PY=/root/llm/venv/bin/python
ADAPTER="${1:-/root/llm/out/intent-lora-v1-7b}"
# 每个 adapter 单独一份日志，避免串行评测互相覆盖
LOG=/mnt/c/Users/35144/AppData/Local/Temp/llm_eval_$(basename "$ADAPTER").log
exec > "$LOG" 2>&1
echo "== 评测开始 $(date -Is) adapter=$ADAPTER"
free -m | head -2; nvidia-smi --query-gpu=memory.used --format=csv,noheader
cd /root/llm/training && "$PY" eval_lora_local.py \
  --base unsloth/Qwen2.5-7B-Instruct-bnb-4bit \
  --adapter "$ADAPTER" --batch 8 --tag lora-v1-local
rc=$?
echo "== 评测结束 rc=$rc $(date -Is)"

echo "== 回写报告到仓库"
DST=""
for c in $(ls -d /mnt/d/*/LANLinkShopping/Shopping/training 2>/dev/null); do
  if [ -f "$c/train_intent_sft.py" ] && [ -f "$c/data/splits/eval_v1.sha256" ]; then DST="$c/reports"; break; fi
done
echo "DST=$DST"
[ -n "$DST" ] || { echo "WARN: 未定位到工作树，报告留在 /root/llm/training/reports"; exit 0; }
mkdir -p "$DST"
cp -f /root/llm/training/reports/*.json "$DST"/ && ls -l "$DST" | tail -5

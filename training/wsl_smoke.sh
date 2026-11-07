#!/usr/bin/env bash
# WSL 内训练冒烟：拷数据到 ext4 → 计时导入 unsloth → 0.5B 4bit 跑 20 步。
# 以 detached 方式运行，日志落 /mnt/c 便于 Windows 侧读取。
exec > /mnt/c/Users/35144/AppData/Local/Temp/llm_smoke.log 2>&1
set -uo pipefail
PY=/root/llm/venv/bin/python
TRAIN=/root/llm/training

echo "== 时间/内存基线 $(date -Is)"; free -m | head -2; nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader

echo "== 复制 training 到 ext4"
# 机器上存在 D:\Desktop 与 D:\桌面 两份仓库树（前者缺 training/*.py），glob 顺序不可信，
# 因此按「同时具备 train_intent_sft.py 与 eval_v1.sha256」挑选真实工作树
SRC=""
for c in $(ls -d /mnt/d/*/LANLinkShopping/Shopping/training 2>/dev/null); do
  if [ -f "$c/train_intent_sft.py" ] && [ -f "$c/data/splits/eval_v1.sha256" ]; then SRC="$c"; break; fi
done
echo "SRC=$SRC"
[ -n "$SRC" ] || { echo "FATAL: 未找到含冻结切分的 training 目录"; exit 1; }
mkdir -p "$TRAIN"
cp -f "$SRC"/*.py "$SRC"/*.sh "$SRC"/*.txt "$SRC"/README.md "$TRAIN"/ 2>/dev/null
mkdir -p "$TRAIN"/data/splits "$TRAIN"/data/seeds
cp -f "$SRC"/data/splits/* "$TRAIN"/data/splits/ 2>/dev/null
cp -f "$SRC"/data/seeds/* "$TRAIN"/data/seeds/ 2>/dev/null
wc -l "$TRAIN"/data/splits/train_v1.jsonl "$TRAIN"/data/splits/dev_v1.jsonl "$TRAIN"/data/splits/eval_v1.jsonl
echo "== 校验冻结指纹（不匹配则终止，绝不训练在漂移数据上）"
ACTUAL=$(sha256sum "$TRAIN"/data/splits/eval_v1.jsonl | cut -d' ' -f1)
EXPECTED=$(cut -d' ' -f1 "$TRAIN"/data/splits/eval_v1.sha256 | tr -d '\r')
echo "expected=$EXPECTED"
echo "actual  =$ACTUAL"
[ "$ACTUAL" = "$EXPECTED" ] || { echo "FATAL: eval_v1 指纹失配"; exit 1; }

echo "== 计时导入 unsloth"
t0=$(date +%s)
"$PY" -c "
import time,sys
t=time.time()
import torch
print('torch import %.1fs'%(time.time()-t), flush=True)
t=time.time()
import unsloth
print('unsloth import %.1fs version=%s'%(time.time()-t, unsloth.__version__), flush=True)
t=time.time()
from trl import SFTTrainer, SFTConfig
import inspect
print('trl import %.1fs'%(time.time()-t), flush=True)
print('cuda', torch.cuda.is_available(), torch.cuda.mem_get_info(), flush=True)
"
echo "unsloth 总耗时 $(( $(date +%s) - t0 ))s"

echo "== 0.5B QLoRA 冒烟 20 步 $(date -Is)"
cd "$TRAIN" && "$PY" train_intent_sft.py \
  --base /root/models/Qwen2.5-0.5B-Instruct \
  --max-seq-len 512 --steps 20 --batch 2 --grad-accum 4 \
  --out /root/llm/out/smoke-lora
echo "== 冒烟结束 rc=$? $(date -Is)"
free -m | head -2
nvidia-smi --query-gpu=memory.used --format=csv,noheader

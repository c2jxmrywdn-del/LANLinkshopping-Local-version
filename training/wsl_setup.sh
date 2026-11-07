#!/usr/bin/env bash
# WSL2 意图 SFT 训练环境安装（在 WSL 内以 root 执行）：
#   wsl -d Ubuntu -u root -- bash /mnt/d/<仓库路径>/training/wsl_setup.sh
#
# venv 与缓存一律落在 ext4（/root/llm），不放在 /mnt/d：9p 下 site-packages 的
# 小文件 IO 会慢一个数量级，训练时 dataloader 更明显。
set -euo pipefail

VENV=/root/llm/venv
PIP="$VENV/bin/pip"
PY="$VENV/bin/python"
MIRROR="${PIP_MIRROR:-https://mirrors.aliyun.com/pypi/simple/}"
CUDA_CHANNEL="${CUDA_CHANNEL:-cu130}"   # 驱动 616.92 满足；可改 cu128 回退

export HF_HOME=/root/llm/hf
export PIP_CACHE_DIR=/root/llm/.pipcache
export TORCH_HOME=/root/llm/torch
mkdir -p "$HF_HOME" "$PIP_CACHE_DIR" "$TORCH_HOME"

echo "== [1/4] venv"
[ -x "$PY" ] || python3.12 -m venv "$VENV"
"$PIP" install -q -U pip setuptools wheel -i "$MIRROR"

echo "== [2/4] torch ($CUDA_CHANNEL)"
# 先装 torch，避免 unsloth 的依赖解析把 CUDA 版本换成 CPU 轮子
"$PIP" install -q --index-url "https://download.pytorch.org/whl/$CUDA_CHANNEL" \
  "torch>=2.8,<2.15" torchvision torchaudio || {
    echo "[warn] $CUDA_CHANNEL 安装失败，回退 cu128"
    "$PIP" install -q --index-url https://download.pytorch.org/whl/cu128 \
      "torch>=2.8,<2.15" torchvision torchaudio
  }

echo "== [3/4] unsloth + 训练栈"
# transformers 上限由 unsloth 决定；--no-deps 保住上面装好的 CUDA torch
"$PIP" install -q -i "$MIRROR" unsloth || "$PIP" install -q unsloth
"$PIP" install -q -i "$MIRROR" \
  "trl>=0.24" "peft>=0.18.0" "datasets>=3.4.1" "accelerate>=1.0" \
  "bitsandbytes>=0.45.5" "sentencepiece" protobuf "torch>=2.8,<2.15"

echo "== [4/4] CUDA 冒烟"
"$PY" - <<'PY'
import torch, json
p = torch.cuda.get_device_properties(0)
print(json.dumps({
    "torch": torch.__version__, "cuda": torch.version.cuda,
    "available": torch.cuda.is_available(), "device": p.name,
    "vram_mib": p.total_memory // 2**20, "cc": f"{p.major}.{p.minor}",
}, ensure_ascii=False))
import trl, peft, transformers, datasets, bitsandbytes
print(json.dumps({"trl": trl.__version__, "peft": peft.__version__,
                  "transformers": transformers.__version__,
                  "datasets": datasets.__version__,
                  "bitsandbytes": bitsandbytes.__version__}, ensure_ascii=False))
from trl import SFTConfig, SFTTrainer
import inspect
fields = set(getattr(SFTConfig, "__dataclass_fields__", {}).keys())
print("SFTConfig has max_length:", "max_length" in fields,
      "| has max_seq_length:", "max_seq_length" in fields,
      "| has processing_class:", "processing_class" in
      set(inspect.signature(SFTTrainer.__init__).parameters))
PY
echo "== done"

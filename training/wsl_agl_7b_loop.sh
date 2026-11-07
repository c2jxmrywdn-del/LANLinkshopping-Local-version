#!/usr/bin/env bash
# ==============================================================================
# wsl_agl_7b_loop.sh: Qwen2.5-7B 基于 Agent-Lightning 轨迹与 4-bit QLoRA GRPO 进化训练流水线
# 专为 RTX 4070 Laptop (8GB 显存 / 8188 MiB) 调优：两阶段显存时分复用
#
# 用法：
#   wsl -d Ubuntu -u root -- bash /root/llm/training/wsl_agl_7b_loop.sh
#   或者传入参数：
#   DRY_RUN=1 bash wsl_agl_7b_loop.sh        # 仅做环境与数据校验
#   TRAIN_STEPS=30 bash wsl_agl_7b_loop.sh   # 指定 GRPO 训练步数
#   FREE_GPU=1 bash wsl_agl_7b_loop.sh       # 自动先停占用显存的推理容器
# ==============================================================================
set -euo pipefail

export HF_HOME="${HF_HOME:-/root/llm/hf}"
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
export PYTORCH_CUDA_ALLOC_CONF="expandable_segments:True"
export VLLM_USE_FLASHINFER_SAMPLER=0
export RAY_memory_monitor_refresh_ms=0
export NO_PROXY="localhost,127.0.0.1,::1${NO_PROXY:+,$NO_PROXY}"
export no_proxy="$NO_PROXY"

PY="/root/llm/venv/bin/python"
MODEL_NAME="${MODEL_NAME:-unsloth/Qwen2.5-7B-Instruct-bnb-4bit}"
OUT_DIR="${OUT_DIR:-/root/llm/out/qwen7b-agl-grpo-v1}"
TRAIN_STEPS="${TRAIN_STEPS:-25}"
AGL_PORT=8181
INFER_PORT=8000
DRY_RUN="${DRY_RUN:-0}"
FREE_GPU="${FREE_GPU:-1}"

INFER_PID=""
AGL_PID=""

cleanup() {
  echo ">>> [收尾] 清理后台进程..."
  [ -n "$INFER_PID" ] && kill "$INFER_PID" 2>/dev/null || true
  [ -n "$AGL_PID" ] && kill "$AGL_PID" 2>/dev/null || true
  echo ">>> [收尾] 进程清理完毕。"
}
trap cleanup EXIT INT TERM

echo "===================================================================="
echo ">>> [1/6] 资源检查与显存准备 $(date -Is)"
echo "===================================================================="
free -m | head -2
nvidia-smi --query-gpu=name,memory.used,memory.total,memory.free --format=csv,noheader

FREE_MIB=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
if [ "$FREE_MIB" -lt 5500 ]; then
  echo "[Warning] 当前可用显存仅 ${FREE_MIB}MiB < 5500MiB"
  if [ "$FREE_GPU" = "1" ]; then
    echo ">>> 正在停止占卡容器释放显存 (lanlink-llama-cpp / lanlink-ollama)..."
    docker stop lanlink-llama-cpp 2>/dev/null || true
    docker stop lanlink-ollama 2>/dev/null || true
    sleep 2
    FREE_MIB=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
    echo ">>> 释放后可用显存: ${FREE_MIB}MiB"
  fi
fi

echo "===================================================================="
echo ">>> [2/6] 同步最新训练代码至 ext4 (/root/llm/training)"
echo "===================================================================="
SRC="/mnt/d/桌面/LANLinkShopping/Shopping/training"
if [ ! -d "$SRC" ]; then
  SRC=$(ls -d /mnt/d/*/LANLinkShopping/Shopping/training 2>/dev/null | head -1 || true)
fi
if [ -n "$SRC" ] && [ -d "$SRC" ]; then
  mkdir -p /root/llm/training
  cp "$SRC"/*.py /root/llm/training/ 2>/dev/null || true
  cp "$SRC"/*.sh /root/llm/training/ 2>/dev/null || true
  chmod +x /root/llm/training/*.sh 2>/dev/null || true
  echo ">>> 成功从 $SRC 同步最新 Python/Shell 代码"
fi

cd /root/llm/training

if [ "$DRY_RUN" = "1" ]; then
  echo ">>> [Dry-Run] 执行代码与环境自检..."
  "$PY" agl_grpo_trainer.py --data data/splits/train_v1.jsonl --dry-run
  echo ">>> [Dry-Run] 自检完成，退出。"
  exit 0
fi

echo "===================================================================="
echo ">>> [3/6] 阶段一：启动 AGL 网关与 Agent 轨迹交互 (Rollout Phase)"
echo "===================================================================="
# 检查是否已有 AGL server，若本地 agent-lightning 仓库可用则尝试启动，否则走离线混合模式
AGL_REPO="/root/agent-lightning"
if [ -d "$AGL_REPO" ] && [ -x "$AGL_REPO/.venv/bin/agl-server" ]; then
  echo ">>> 启动 agl-server 网关 (端口 $AGL_PORT)..."
  "$AGL_REPO/.venv/bin/agl-server" port=$AGL_PORT key=shopping-dev-key > /tmp/agl-server.log 2>&1 &
  AGL_PID=$!
  sleep 3
else
  echo "[Info] 未发现独立 agl-server 二进制，使用内置直接数据通路模式。"
fi

echo "===================================================================="
echo ">>> [4/6] 阶段二：释放推理显卡，启动 4-bit QLoRA GRPO 策略梯度优化"
echo "===================================================================="
# 确保推理进程释放显存，只留 GRPO Trainer 单独占卡
[ -n "$INFER_PID" ] && kill "$INFER_PID" 2>/dev/null || true
sleep 2

mkdir -p "$OUT_DIR"
echo ">>> 执行训练: 基座=$MODEL_NAME, 步数=$TRAIN_STEPS, 目标=$OUT_DIR"
"$PY" agl_grpo_trainer.py \
  --base "$MODEL_NAME" \
  --out "$OUT_DIR" \
  --data data/splits/train_v1.jsonl \
  --generations 4 \
  --steps "$TRAIN_STEPS" \
  --lr 5e-6

echo "===================================================================="
echo ">>> [5/6] 阶段三：LoRA 产物检查与格式转换准备"
echo "===================================================================="
if [ -d "$OUT_DIR" ]; then
  echo ">>> 训练产物清单:"
  ls -lh "$OUT_DIR" | head -10
fi

echo "===================================================================="
echo ">>> [6/6] 总结与后续接入指引"
echo "===================================================================="
echo "1. LoRA 适配器权重已就绪: $OUT_DIR"
echo "2. 导出 GGUF 并回灌推理端："
echo "   ./convert_gguf.sh $OUT_DIR qwen2.5-7b-tuned-grpo"
echo "3. 在 scripts/local-ai/llama-container-preset.ini 中配置新模型预设"
echo "4. 重新启动 llama.cpp，ai-gateway 探针将自动热重载接管！"
echo ">>> 全部流程执行完毕 $(date -Is) <<<"

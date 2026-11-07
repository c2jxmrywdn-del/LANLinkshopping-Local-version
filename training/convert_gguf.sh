#!/usr/bin/env bash
# 合并后的 HF 模型 → GGUF Q4_K_M（供 llama.cpp 加载）
# 用法：./convert_gguf.sh <merged_hf_dir> <out_name>
set -euo pipefail
MERGED="${1:?merged hf dir}"
NAME="${2:-qwen2.5-intent-tuned}"
LLAMA_DIR="${LLAMA_CPP_DIR:-/opt/llama.cpp}"
OUT_DIR="${MODEL_OUT_DIR:-/models}"
python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MERGED" --outfile "$OUT_DIR/$NAME-f16.gguf" --outtype f16
"$LLAMA_DIR/build/bin/llama-quantize" "$OUT_DIR/$NAME-f16.gguf" "$OUT_DIR/$NAME-Q4_K_M.gguf" Q4_K_M
echo "done: $OUT_DIR/$NAME-Q4_K_M.gguf"
echo "next: 在 scripts/local-ai/llama-container-preset.ini 增加 [ $NAME ] 并重启 llama-cpp"

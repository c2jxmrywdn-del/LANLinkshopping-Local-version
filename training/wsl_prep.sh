#!/usr/bin/env bash
# 把 training 目录复制进 WSL ext4 并做训练前自检（只读检查 + 拷贝，不训练）。
exec > /mnt/c/Users/35144/AppData/Local/Temp/llm_prep.txt 2>&1
set -uo pipefail
PY=/root/llm/venv/bin/python

echo "== unsloth import"
"$PY" -c "import unsloth, torch; print('unsloth', unsloth.__version__, '| torch', torch.__version__, '| cuda', torch.cuda.is_available())"

echo "== 本地可用权重"
ls -d /root/models/*/ 2>/dev/null || echo "(none)"

echo "== 定位仓库 training 目录（用 glob 避开中文路径传参）"
SRC=$(ls -d /mnt/d/*/LANLinkShopping/Shopping/training 2>/dev/null | head -1)
echo "SRC=$SRC"
[ -n "$SRC" ] || { echo "FATAL: 未找到 training 目录"; exit 1; }

echo "== 复制到 ext4（训练期不再走 9p）"
mkdir -p /root/llm/training
cp -r "$SRC"/. /root/llm/training/
ls /root/llm/training/*.py
echo "== 切分条数"
wc -l /root/llm/training/data/splits/train_v1.jsonl \
      /root/llm/training/data/splits/dev_v1.jsonl \
      /root/llm/training/data/splits/eval_v1.jsonl
echo "== 冻结指纹"
cat /root/llm/training/data/splits/eval_v1.sha256
echo "== 训练数据首条（校验严格 JSON 标注形态）"
"$PY" -c "
import json,sys
sys.path.insert(0,'/root/llm/training')
import prompt
row=json.loads(open('/root/llm/training/data/splits/train_v1.jsonl',encoding='utf-8').readline())
msgs=prompt.build_messages(row['question'])+[{'role':'assistant','content':json.dumps({'intent':row['intent']},ensure_ascii=False)}]
print(json.dumps(msgs,ensure_ascii=False)[:520])
print('roundtrip parse ->', prompt.parse_intent(msgs[-1]['content']))
"
echo "== done"

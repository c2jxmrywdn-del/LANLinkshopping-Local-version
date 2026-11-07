# 本地模型后训练工程（意图分类 SFT）

目标：为本地模型（Qwen2.5-7B）沉淀可复现的训练语料与**冻结评测集**，用 LoRA/SFT 提升意图分类，
再导出 GGUF 回灌 llama.cpp。**本目录不参与后端/容器构建**，只在本地（Windows 取数/评测，
WSL2 训练）运行。

## 数据链路

种子来自四处，全部可追溯（`seed_id` 保留来源）：
1. Java 规则语料 `IntentVectorService.CORPUS`（解析源码，不是猜的）；
2. 回归用例：`backend/src/test/resources/support-learning-sandbox-cases.json` +
   `support-learning-sandbox/conversation_regression_cases.json`（entry→intent 银标）；
3. 意图日志 `t_support_intent_log.question_text`（后端已补列写入原文）；
4. 每类手写的 CORES / 中英混合 BILINGUAL 种子（补长尾类别）。

```bash
cd training
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

python seeds_export.py                                                 # → data/seeds/seeds_v1.jsonl
python build_dataset.py                                                # → data/splits/{train,dev,eval}_v1.jsonl
python eval_baseline.py --dry-run                                      # 核对规模/分布/sha256
python eval_baseline.py --model qwen2.5-7b --tag v1                    # 冻结集基线
```

当前冻结产物（`manifest_v1.json` + `eval_v1.sha256`，改动即失配）：

| 划分 | 条数 | 每类 | 说明 |
|---|---|---|---|
| train_v1 | 693 | 63 | 7 种子 ×9 变体，已剔除与 eval 的近似重复 |
| dev_v1 | 121 | 11 | 训练期验证 |
| eval_v1 | 264 | 24 | **只用于评测**，11 类均衡，中英混合 88 条 |

去泄漏：train/dev 相对 eval 做字符 bigram Jaccard ≥0.85 剔除，因此基线不会因“见过同一句话的
标点变体”而虚高。

## 指标口径

`rewards.evaluate` 以 **macro-F1（仅统计评测集中真实出现的类）为主指标**，配 accuracy 的
bootstrap 95% CI；`macro_f1_all_labels` 把缺失类按 0 计入，用于观察长尾。报告落在
`reports/baseline_<tag>_<model>.json`，含每条预测的 `gold/pred/backend`。

## 已知坑：网关静默降级会把评测打成分数（重要）

网关在 llama.cpp router 不可达时会**降级到 mock 后端**，返回 HTTP 200 + 看似正常的中文文本
（`（知识库为空）来源：无`），调用方无从分辨。`model_manager` 原来对 `/v1/models` 只给 1.5s
超时，失败后进入 **30s 负冷却**——而 router 正在生成时 `/v1/models` 恰恰最容易慢，于是

> 一次探测抖动 → 30s 内全部请求走 mock → 首轮 264 条基线全部判 0 分。

这也是“没跑任何 AI 分析也显示系统繁忙”的根因。已做两处修复：

1. `ai-gateway/docker-compose.yml`：`MODEL_PROBE_TIMEOUT=6`、`MODEL_PROBE_COOLDOWN=5`
   （修复后同批 264 条几乎全为 `llamacpp:qwen2.5-7b`，零星降级被重试吸收）。
2. `eval_baseline.py`：读响应里的 `lanlink.backend`，识别 mock/兜底文本后退避重试；
   仍降级的条目**不计入准确率**，超过 `--max-degraded`（默认 3）直接判本次评测无效并退出 3，
   绝不把基础设施抖动写成模型分数。

注意：重建网关必须带根 .env，否则 `LLM_BACKEND` 会静默回落到 ollama（0.5b），基线就测错模型了：

```bash
docker compose --env-file ../.env -f ai-gateway/docker-compose.yml up -d ai-gateway
docker exec lanlink-ai-gateway printenv LLM_BACKEND   # 期望 llamacpp
```

## 训练（WSL2 + CUDA）

```bash
# 1) 安装（venv 与缓存落 ext4 的 /root/llm，绝不放 /mnt/d：9p 下 site-packages 慢一个数量级）
tr -d '\r' < training/wsl_setup.sh | wsl -d Ubuntu -u root -- bash -c 'cat > /root/llm/setup.sh'
wsl -d Ubuntu -u root -- bash /root/llm/setup.sh

# 2) 训练前先释放显存：7B QLoRA 与现网 7B 推理不能共存
docker stop lanlink-llama-cpp
#    并把根 .env 的 LLM_BACKEND 改成 auto/ollama，否则网关会反复重试已停机的 router

# 3) 先 0.5B 冒烟验证链路与数据格式，再上 7B
wsl -d Ubuntu -u root -- /root/llm/venv/bin/python /mnt/d/.../training/train_intent_sft.py \
    --base Qwen/Qwen2.5-0.5B-Instruct --max-seq-len 512 --steps 20
wsl -d Ubuntu -u root -- /root/llm/venv/bin/python /mnt/d/.../training/train_intent_sft.py
```

脚本对 trl 版本自适应（`processing_class`/`tokenizer`、`max_length`/`max_seq_length`、
`eval_strategy`/`evaluation_strategy` 按安装后的真实签名选择）。

显存预算（RTX 4070 Laptop 8GB）：4bit 7B 基座 + LoRA + paged 8bit 优化器，`max-seq-len ≤768`、
`batch 1`、`grad-accum 16` 可跑，但必须停机 llama.cpp；否则先训 1.5B。

> 内存教训：`.wslconfig` 的 `memory=` 是 **Docker 栈与 Ubuntu 发行版共用的同一个 VM 上限**。
> 旧值下 vmmem 顶到 7.6GB，Docker 引擎直接返回 500、容器全停。训练期间同时跑「评测 + pip 安装
> + 7B 推理」会复现该现象；扩到 8.65GB（重启 WSL 后实测 `free` 可见 ~11.8GB）并串行执行后恢复。

## 导出与切流（P3）

```bash
./convert_gguf.sh out/intent-lora-v1-merged qwen2.5-intent-tuned
# 在 scripts/local-ai/llama-container-preset.ini 增加 [qwen2.5-intent-tuned]
# 重建 llama-cpp，用同一份 eval_v1 跑 tuned 基线，与 baseline_v1 对比后再灰度切流
```

切流前必须满足：同一 `eval_v1.sha256`、同一 `prompt.py` 口径、`backend` 全为真实后端。
生产端 `IntentUnderstandingService` 已改用与 `training/prompt.py` 同构的严格 JSON 契约
（11 类 + `other`），所以微调模型可直接替换而未微调模型仍受旧裸标签兼容路径保护。

## 目录

```
training/
  config.py            路径/数据库/网关/意图标签（11 类）+ entry→intent 映射
  prompt.py            统一意图提示词与解析（训练/评测/生产同一口径）
  seeds_export.py      CORPUS + 回归用例 + 意图日志 → data/seeds/seeds_v1.jsonl
  build_dataset.py     模板改写增广 → 按 seed 分组切分 → 防泄漏 → 冻结 eval_v1
  rewards.py           accuracy + bootstrap CI / macro-F1 / 每类 / 混淆对
  eval_baseline.py     基线评测（识别网关降级，拒绝被污染的报告）
  train_intent_sft.py  QLoRA SFT（trl 版本自适应）
  wsl_setup.sh         WSL2 训练环境安装（cu130 优先，失败回退 cu128）
  convert_gguf.sh      HF → GGUF Q4_K_M
  requirements.txt
```

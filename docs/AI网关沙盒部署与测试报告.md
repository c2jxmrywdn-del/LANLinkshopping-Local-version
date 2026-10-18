# LANLink AI 网关沙盒 —— 部署配置说明、接口示例与测试验证报告

> 版本：1.0.0 ｜ 部署日期：2026-10-15 ｜ 运行环境：Docker 沙盒（本地，零外部依赖）

## 一、方案总览

在 Docker 沙盒中部署三层架构，全部组件本地运行、不依赖任何远程资源：

```
┌────────────────────────────────────────────────────────────┐
│  lanlink-ai-gateway（端口 8090，OpenAI API 兼容网关）          │
│  ├─ 路由转发：/v1/* → 大模型推理，/kb/* → 知识库检索           │
│  ├─ 请求校验：model/messages/temperature 等按 OpenAI 规范校验  │
│  ├─ 响应转换：Ollama 响应 → chat.completion 标准结构           │
│  └─ RAG 编排：场景识别 → 向量检索 → Prompt 模板 → 生成 → 引用   │
├────────────────────────────────────────────────────────────┤
│  知识库模块（容器内）：15 篇电商知识文档                        │
│  jieba 分词 + TF-IDF 向量 + 余弦相似度检索 + 上下文关联扩展     │
├────────────────────────────────────────────────────────────┤
│  lanlink-ollama（端口 11434，本地大模型推理服务）              │
│  模型：qwen2.5:0.5b（397MB，本地加载推理，权重卷持久化）        │
└────────────────────────────────────────────────────────────┘
```

## 二、部署配置说明

### 2.1 目录结构

```
ai-gateway/
├── app/
│   ├── main.py        # OpenAI 兼容网关（路由转发/请求校验/响应转换/RAG 编排）
│   ├── retriever.py   # 知识库模块（向量检索/上下文关联/embeddings）
│   ├── prompts.py     # 电商场景 Prompt 模板（6 大场景路由）
│   └── llm.py         # 大模型推理客户端（Ollama 后端 + mock 兜底）
├── kb_data/ecommerce_kb.json   # 本地知识库（15 篇，源自真实业务数据）
├── tests/test_gateway.py       # 测试套件（22 项，构建期自检）
├── Dockerfile                  # 沙盒镜像（构建期跑全量测试）
├── docker-compose.yml          # 编排：ollama + ai-gateway
└── requirements.txt
```

### 2.2 一键部署

```bash
# 前置：Docker Desktop 已启动（本项目 docker-compose.local.yml 的基础设施可同机共存）
docker volume create lanlink-ollama-models        # 模型权重持久化卷

cd ai-gateway
docker compose up -d --build
```

构建期自动执行 22 项单元/接口测试，不通过则镜像构建失败，保证交付质量。

### 2.3 配置项清单

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `PORT` | `8090` | AI 网关监听端口 |
| `OLLAMA_BASE_URL` | `http://ollama:11434` | 本地大模型服务地址（沙盒内网） |
| `LLM_MODEL` | `qwen2.5:0.5b` | 本地大模型（Ollama 模型名） |
| `KB_PATH` | `/app/kb_data/ecommerce_kb.json` | 本地知识库文件路径 |

### 2.4 组件与端口

| 容器 | 端口 | 健康检查 | 说明 |
|------|------|----------|------|
| `lanlink-ai-gateway` | 8090 | `GET /health` | OpenAI 兼容网关 + RAG |
| `lanlink-ollama` | 11434 | `ollama list` | 本地大模型推理（权重卷 `lanlink-ollama-models`） |

> 大模型后端可插拔：`llm.py` 在 Ollama 未就绪时自动回退 mock 后端，保证链路始终可验证；`GET /health` 可查看当前实际后端（`components.llm.backend`）。

### 2.5 本地知识库

- 数据来源：`lanlink_shopping` 真实数据库（30 商品 / 24 行业 / 30 分类 / 订单表结构）+ 项目业务规则
- 15 篇文档覆盖 7 个分类：商品（6 篇分行业）、订单、物流、售后、支付、会员、账户
- 向量化：jieba 中文分词 → TF-IDF → 余弦相似度；支持上下文关联扩展（同分类文档补全）

## 三、接口调用示例

### 3.1 健康检查

```bash
curl http://localhost:8090/health
```

```json
{"status":"UP","components":{"llm":{"backend":"ollama","model":"qwen2.5:0.5b"},
 "knowledge_base":{"document_count":15,"categories":["会员","售后","商品","支付","物流","订单","账户"],"vocab_size":466}}}
```

### 3.2 对话补全（OpenAI 兼容，RAG 自动开启）

```bash
curl http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"七天无理由退货是怎么规定的"}],"temperature":0.2}'
```

响应（标准 `chat.completion` 结构 + 扩展溯源字段）：

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1760560000,
  "model": "qwen2.5:0.5b",
  "choices": [{"index": 0, "message": {"role": "assistant",
    "content": "适用条件：七天无理由退货适用于签收后7天内，商品完好不影响二次销售的，可申请无理由退货，退货运费由买家承担。\n...来源：《售后政策》"},
    "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 32, "completion_tokens": 68, "total_tokens": 100},
  "lanlink": {"latency_ms": 820, "backend": "ollama", "scene": "aftersales",
    "kb_hits": [{"id": "after-sales-policy", "title": "售后政策", "score": 0.21}]}
}
```

兼容任意 OpenAI SDK：

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:8090/v1", api_key="lanlink-local")
resp = client.chat.completions.create(model="qwen2.5:0.5b",
    messages=[{"role": "user", "content": "运费怎么算"}])
print(resp.choices[0].message.content)
```

### 3.3 流式输出（SSE）

```bash
curl -N http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5:0.5b","messages":[{"role":"user","content":"支持哪些支付方式"}],"stream":true}'
```

输出 `data: {...}` 增量块，以 `data: [DONE]` 结束，与 OpenAI SSE 语义一致。

### 3.4 其他兼容接口

| 接口 | 说明 |
|------|------|
| `GET /v1/models` | 模型列表（`object: "list"`） |
| `POST /v1/embeddings` | 文本向量（固定 256 维，`object: "embedding"` + usage） |
| `GET /kb/search?q=...&top_k=3` | 知识库向量检索调试 |
| `POST /kb/reload` | 知识库热更新 |

参数校验失败返回 OpenAI 标准错误结构：

```json
{"error": {"message": "body.messages.0.role: ...", "type": "invalid_request_error", "code": "invalid_value"}}
```

## 四、电商场景 Prompt 模板

`app/prompts.py` 按咨询意图自动路由 6 套模板（`use_kb`、`scene` 可由调用方覆盖）：

| 场景 | 触发关键词示例 | 模板要点 |
|------|----------------|----------|
| `product` 商品 | 价格/库存/规格/型号 | 分条列商品名称、品牌、规格、单价、库存 |
| `order` 订单 | 订单状态/支付/积分/发票 | 订单状态流转 + 1000 积分=1 元、最小 1 元、上限 10% |
| `logistics` 物流 | 快递/发货/运费/签收 | 发货时效、快递合作方、运费规则、轨迹查询方式 |
| `aftersales` 售后 | 退货/退款/换货/质量 | 适用条件、时间窗口、运费承担方、退款时效 |
| `account` 账户 | 认证/账期/会员等级 | 流程、时效、权益 |
| `general` 通用 | 其他 | 依据知识库简洁作答 |

所有模板统一约束：**只依据知识库回答、不得编造、超出范围转人工、金额时效必须与知识库一致、末尾标注来源**。

## 五、测试验证报告

### 5.1 测试套件（构建期 + 本地）：22 项全部通过

| 测试组 | 用例数 | 覆盖内容 | 结果 |
|--------|--------|----------|------|
| 向量检索 | 7 | 知识库加载、分词、4 类查询命中、分数排序、上下文扩展 | 7/7 通过 |
| 场景路由 | 6 | 6 类场景识别 + Prompt 组装结构 | 6/6 通过 |
| OpenAI 契约 | 9 | 模型列表、响应结构、非法 role 校验(422)、空 user(400)、回答命中知识库、流式、embeddings、检索、健康 | 9/9 通过 |

### 5.2 全链路联调（真实大模型 qwen2.5:0.5b + 本地知识库）

| 测试项 | 延迟 | 场景识别 | 关键信息覆盖 | 来源引用 | 命中知识库 | 结果 |
|--------|------|----------|--------------|----------|------------|------|
| 商品信息（螺纹钢价格） | 1.28s | product ✅ | 3850 元/吨 ✅ | ✅ | product-construction | 通过 |
| 订单状态 | 7.83s | order ✅ | 待付款/待发货/待收货/已完成 ✅ | ✅ | order-status | 通过 |
| 物流查询 | 3.63s | logistics ✅ | 顺丰/德邦/满 5000 包邮 ✅ | ✅ | logistics-policy | 通过 |
| 售后政策 | 0.82s | aftersales ✅ | 7 天/买家运费/1-3 工作日 ✅ | ✅ | after-sales-policy | 通过 |
| 支付方式 | 3.01s | order ✅ | 对公转账/微信/支付宝/余额 ✅ | ✅ | payment-guide | 通过 |
| 积分规则 | 1.63s | order ✅ | 1000 积分=1 元/最小 1 元/上限 10% ✅ | ✅ | member-points | 通过 |

回答内容与知识库数据逐项比对一致（单价、库存、时效、比例等数字全部吻合），无编造信息。

### 5.3 OpenAI 兼容性验证

| 检查项 | 结果 |
|--------|------|
| `GET /v1/models` | `object=list`，含 `qwen2.5:0.5b`、`lanlink-ecommerce-rag` ✅ |
| `POST /v1/embeddings` | 256 维向量 + usage 统计 ✅ |
| 非法 role 参数校验 | HTTP 422 + `{"error":{"type":"invalid_request_error"}}` ✅ |
| 流式 SSE | 增量块 + `[DONE]` 结束标记 ✅ |
| 响应结构 | `id/object/created/model/choices/usage/finish_reason` 齐备 ✅ |

### 5.4 知识库检索准确性

| 查询 | Top1 命中文档 | 期望 | 结果 |
|------|---------------|------|------|
| 螺纹钢价格 | product-construction | product-construction | ✅ |
| 退货退款政策 | after-sales-policy | after-sales-policy | ✅ |
| 发货快递运费 | logistics-policy | logistics-policy | ✅ |
| 积分抵扣规则 | member-points | member-points | ✅ |

### 5.5 通信稳定性与延迟

| 指标 | 数值 | 判定 |
|------|------|------|
| 连续调用成功率 | 10/10（100%） | 符合预期 ✅ |
| 平均响应延迟 | 2.60s（0.5B 本地 CPU 推理） | 符合预期 ✅ |
| 最小 / 最大延迟 | 1.91s / 3.34s | 波动平稳 ✅ |
| 网关自身开销（不含推理） | < 0.3s | ✅ |
| 容器健康检查 | ai-gateway healthy、ollama healthy | ✅ |

> 延迟主要由本地大模型生成 token 决定；如需更高性能可在 `LLM_MODEL` 换用更大参数量模型（GPU）或调整 `max_tokens`。

### 5.6 测试中发现并修复的问题

| # | 问题 | 修复 |
|---|------|------|
| 1 | FastAPI 参数校验错误(422)不符合 OpenAI 错误结构 | 增加 `RequestValidationError` 处理器，输出 `{"error":{...}}` |
| 2 | 小模型偶发重复句/退化循环 | `_polish` 后处理：逐句去重 + 高频重复行截断 |
| 3 | 回答缺少来源引用 | 后处理强制追加 `来源：《文档》` |
| 4 | embeddings 维度稀疏（不固定） | 改为固定 256 维哈希词袋向量 |
| 5 | 容器内 jieba 缓存写盘权限报错 | 禁用词典缓存写盘（`jieba.dt.cache_file=None`） |
| 6 | 小模型回声"用户问题：" | 后处理过滤回声行 |

## 六、运行维护

```bash
# 状态与日志
docker ps --filter name=lanlink-ai-gateway
docker logs -f lanlink-ai-gateway

# 知识库更新（改 kb_data/ecommerce_kb.json 后热加载）
curl -X POST http://localhost:8090/kb/reload

# 停止 / 清理
cd ai-gateway && docker compose down        # 权重卷保留
```

## 七、结论

Docker 沙盒内 AI 网关（OpenAI 兼容）、本地大模型（qwen2.5:0.5b）、本地知识库（15 篇电商文档 + 向量检索）三组件部署完成并全链路联调通过：接口兼容性、检索准确性、回答相关性、通信稳定性、延迟均符合预期，回答内容与本地知识库逐项一致，无外部远程依赖，可稳定复现运行（`docker compose up -d --build` 一键部署）。

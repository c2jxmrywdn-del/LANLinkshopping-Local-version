"""RAG-Anything（Redis 向量后端）只读检索 MCP 工具。

为什么要单独一个工具
--------------------
平台现在有两套**互相独立**的检索能力，语义不同、数据不同，不能混成一个工具：

| 能力 | 索引 | 特点 |
|:---|:---|:---|
| `lanlink_kb_search` | `aikb-idx`（Java 知识库） | 结构化政策/商品快照，带 source/category，适合精确取证 |
| `lanlink_rag_search`（本文件） | `rag-idx:_:*`（RAG-Anything） | 多模态文档（图文表混排）的实体/关系图 + 向量混合检索，适合「这份文档里讲了什么」 |

外部大模型通过 AI 网关调用 MCP（`POST /mcp` → `tools/call`）时，看到的是这两个
独立的只读工具；网关内部再把请求转发到对应后端，模型侧不需要知道 Redis 索引细节。

安全边界
--------
- **只读**：只调 rag-service 的 `/query`，不碰 `/ingest`；
- **不改索引**：不提供任何写入/删除参数；
- **不外泄拓扑**：返回体只给答案与引用，不回显内部 base_url / 密钥；
- **超时兜底**：rag-service 的 LLM 生成可能 20-60s，工具侧给 120s 上限并转成
  结构化错误，而不是让 MCP 连接挂死。
"""
from __future__ import annotations

import logging
import os

import httpx

logger = logging.getLogger(__name__)

MAX_TOP_K = 20
MAX_QUESTION = 2000

RAG_TOOL_DEF = {
    "type": "function",
    "function": {
        "name": "lanlink_rag_search",
        "description": (
            "只读检索平台的多模态文档知识库（RAG-Anything：图文表混排文档经实体/关系图 + 向量混合检索）。"
            "适用于「某份文档/手册/合同里怎么写的」「某个流程的完整步骤」这类需要跨段落综合的问题。"
            "与 lanlink_kb_search 的区别：后者查结构化政策库（aikb-idx），本工具查多模态文档库（独立 Redis 索引），"
            "两者数据不重叠，可分别调用后交叉验证。返回一段已归纳的答案与引用文件名。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "自然语言问题",
                    "maxLength": MAX_QUESTION,
                },
                "mode": {
                    "type": "string",
                    "enum": ["hybrid", "local", "global", "naive", "mix"],
                    "default": "hybrid",
                    "description": (
                        "检索模式：hybrid=向量+图混合（默认，最稳）；"
                        "local=偏向实体邻域；global=偏向关系；naive=纯向量；mix=图+向量并重"
                    ),
                },
                "topK": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": MAX_TOP_K,
                    "default": 10,
                    "description": "召回条数上限（1-20）",
                },
            },
            "required": ["question"],
        },
    },
}


def _base_url() -> str:
    return (
        os.environ.get("RAG_SERVICE_BASE_URL")
        or "http://lanlink-rag-service:8093"
    ).rstrip("/")


def _secret() -> str:
    return (os.environ.get("RAG_INTERNAL_SECRET") or "").strip()


def make_rag_search() -> callable:
    """返回一个绑定好配置的 handler；配置在调用时读取，便于热更新环境变量。"""

    def rag_search(args: dict) -> dict:
        question = str((args or {}).get("question") or "").strip()
        if not question:
            return {"ok": False, "error": "question 不能为空"}
        if len(question) > MAX_QUESTION:
            return {"ok": False, "error": f"question 超过 {MAX_QUESTION} 字上限"}

        mode = str((args or {}).get("mode") or "hybrid").strip().lower()
        if mode not in ("hybrid", "local", "global", "naive", "mix"):
            mode = "hybrid"
        try:
            top_k = int((args or {}).get("topK") or 10)
        except (TypeError, ValueError):
            top_k = 10
        top_k = max(1, min(top_k, MAX_TOP_K))

        secret = _secret()
        if not secret:
            # 与 rag-service 的 fail-closed 语义一致：没有密钥就不发请求，
            # 避免把「配置缺失」表现成「检索没结果」这种静默失败。
            return {"ok": False, "error": "RAG_INTERNAL_SECRET 未配置，RAG 检索通道不可用"}

        url = f"{_base_url()}/query"
        payload = {"question": question, "mode": mode, "topK": top_k}
        try:
            with httpx.Client(timeout=120.0) as client:
                resp = client.post(url, json=payload, headers={"X-Internal-Secret": secret})
        except httpx.TimeoutException:
            return {"ok": False, "error": "RAG 检索超时（>120s），请缩小问题范围后重试"}
        except httpx.HTTPError as exc:
            logger.warning("[mcp-rag] 请求失败: %s", exc)
            return {"ok": False, "error": f"RAG 服务不可达（{type(exc).__name__}）"}

        if resp.status_code != 200:
            detail = ""
            try:
                detail = str((resp.json() or {}).get("detail") or "")[:300]
            except Exception:  # noqa: BLE001
                detail = resp.text[:300]
            return {
                "ok": False,
                "error": f"RAG 服务返回 HTTP {resp.status_code}",
                "detail": detail,
            }

        try:
            body = resp.json() or {}
        except ValueError:
            return {"ok": False, "error": "RAG 服务返回非 JSON 响应"}

        answer = str(body.get("answer") or "").strip()
        if not answer:
            return {
                "ok": True,
                "question": question,
                "mode": body.get("mode") or mode,
                "answer": "",
                "noAnswer": True,
                "status": "NO_ANSWER",
            }

        return {
            "ok": True,
            "question": question,
            "mode": body.get("mode") or mode,
            "topK": top_k,
            "answer": answer,
            "noAnswer": False,
            "status": "ANSWERED",
            # 索引名不回显（内部拓扑），只标注数据来源族，便于调用方区分两套工具
            "source": "rag-anything",
        }

    return rag_search

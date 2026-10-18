# -*- coding: utf-8 -*-
"""LANLink AI 网关 —— OpenAI API 兼容网关。

职责：
  1. 路由转发：/v1/* 请求转发到本地大模型推理服务（Ollama），/kb/* 转发知识库模块；
  2. 请求校验：按 OpenAI Chat Completions 规范校验 model/messages/temperature 等参数；
  3. 响应转换：将 Ollama 响应转换为 OpenAI chat.completion 格式（含 usage/finish_reason/id）；
  4. RAG 编排：电商场景识别 -> 知识库向量检索 -> prompt 模板组装 -> 大模型生成 -> 来源引用。
"""
import json
import os
import time
import uuid

import jieba

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator

from .llm import LLMClient
from .prompts import build_messages, detect_scene
from .retriever import KnowledgeBase, default_kb_path

app = FastAPI(
    title="LANLink AI Gateway",
    version="1.0.0",
    description="OpenAI API 兼容的电商 AI 网关：本地大模型 + 本地知识库 RAG",
)

KB = KnowledgeBase(default_kb_path())
LLM = LLMClient()
GATEWAY_START = time.time()


# ----------------------------- OpenAI 兼容数据结构 -----------------------------

class ChatMessage(BaseModel):
    role: str = Field(..., description="system / user / assistant / tool")
    content: str = Field(..., description="消息内容")

    @field_validator("role")
    @classmethod
    def check_role(cls, v):
        allowed = {"system", "user", "assistant", "tool", "function"}
        if v not in allowed:
            raise ValueError(f"role 必须是 {allowed} 之一")
        return v


class ChatCompletionRequest(BaseModel):
    model: str = Field(..., min_length=1, description="模型名")
    messages: list[ChatMessage] = Field(..., min_length=1, description="消息列表")
    temperature: float = Field(0.3, ge=0, le=2)
    top_p: float = Field(1.0, ge=0, le=1)
    max_tokens: int | None = Field(None, ge=1, le=4096)
    stream: bool = False
    n: int = Field(1, ge=1, le=4)
    stop: str | list | None = None
    user: str | None = None
    # 扩展字段（OpenAI 允许自定义参数透传）
    use_kb: bool = Field(True, description="是否启用本地知识库 RAG")
    scene: str | None = Field(None, description="指定电商场景（product/order/logistics/aftersales/account/general）")


class EmbeddingRequest(BaseModel):
    model: str = Field(..., min_length=1)
    input: str | list[str]
    user: str | None = None


def _completion_shell(model: str, content: str, finish: str, usage: dict, latency: float) -> dict:
    """响应格式转换：统一输出 OpenAI chat.completion 结构。"""
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex[:24]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": content},
            "finish_reason": finish,
        }],
        "usage": usage,
        "lanlink": {"latency_ms": int(latency * 1000), "backend": LLM.backend_name()},
    }


def _usage(prompt: str, completion: str) -> dict:
    # 与 OpenAI 一致的 usage 统计（中文按 jieba 词数近似 token）
    pt = len(list(jieba.cut(prompt)))
    ct = len(list(jieba.cut(completion)))
    return {"prompt_tokens": pt, "completion_tokens": ct, "total_tokens": pt + ct}


def _polish(text: str, docs: list) -> str:
    """回答后处理：小模型退化重复句去重 + 强制来源引用（保证回答可溯源）。"""
    import re as _re
    lines = text.split("\n")
    seen, cleaned = set(), []
    for ln in lines:
        if ln.strip().startswith("用户问题："):  # 清理小模型对提问的回声
            continue
        key = _re.sub(r"^[\d.\s、]+", "", ln).strip()
        if not key:
            continue
        if key in seen:  # 去掉小模型重复生成的句子
            continue
        seen.add(key)
        cleaned.append(ln)
    text = "\n".join(cleaned).strip()
    # 若超过 80% 行重复同一内容则截断（退化兜底）
    if cleaned:
        from collections import Counter
        top = Counter(_re.sub(r"^[\d.\s、]+", "", ln).strip() for ln in cleaned).most_common(1)[0]
        if top[1] > 2:
            text = "\n".join(ln for ln in cleaned if _re.sub(r"^[\d.\s、]+", "", ln).strip() != top[0])
    if text and "来源" not in text and docs:
        titles = "、".join(f"《{d['title']}》" for d in docs[:2])
        text = f"{text}\n来源：{titles}"
    return text


# ----------------------------- OpenAI 兼容接口 -----------------------------

@app.post("/v1/chat/completions")
def chat_completions(req: ChatCompletionRequest, request: Request):
    """OpenAI Chat Completions 兼容接口（支持 stream / 非 stream）。"""
    t0 = time.time()
    question = next((m.content for m in reversed(req.messages) if m.role == "user"), "")
    if not question.strip():
        raise HTTPException(status_code=400, detail={"error": {"message": "messages 中必须包含 user 消息", "type": "invalid_request_error"}})

    # RAG 编排：场景识别 -> 知识库向量检索 -> prompt 模板组装
    docs = KB.search(question, top_k=4) if req.use_kb else []
    messages, scene = build_messages(question, docs, req.scene)

    text, backend, infer_latency = LLM.chat(
        messages,
        temperature=req.temperature,
        max_tokens=req.max_tokens or 512,
    )
    text = _polish(text, docs)
    latency = time.time() - t0
    usage = _usage(question, text)

    if req.stream:
        def gen():
            chunk_head = {
                "id": f"chatcmpl-{uuid.uuid4().hex[:24]}",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": req.model,
                "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}],
            }
            yield f"data: {json.dumps(chunk_head, ensure_ascii=False)}\n\n"
            # 流式按句输出，符合 OpenAI SSE 语义
            import re as _re
            parts = [p for p in _re.split(r"(?<=[。！？\n])", text) if p]
            for part in parts:
                delta = {
                    "id": chunk_head["id"],
                    "object": "chat.completion.chunk",
                    "created": chunk_head["created"],
                    "model": req.model,
                    "choices": [{"index": 0, "delta": {"content": part}, "finish_reason": None}],
                }
                yield f"data: {json.dumps(delta, ensure_ascii=False)}\n\n"
            tail = {
                "id": chunk_head["id"],
                "object": "chat.completion.chunk",
                "created": chunk_head["created"],
                "model": req.model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                "usage": usage,
            }
            yield f"data: {json.dumps(tail, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"

        return StreamingResponse(gen(), media_type="text/event-stream")

    result = _completion_shell(req.model, text, "stop", usage, latency)
    result["lanlink"].update({
        "scene": scene,
        "backend": backend,
        "kb_hits": [{"id": d["id"], "title": d["title"], "score": d.get("score", 0)} for d in docs],
    })
    return JSONResponse(result)


@app.get("/v1/models")
def list_models():
    """OpenAI 兼容模型列表。"""
    now = int(time.time())
    return {
        "object": "list",
        "data": [{
            "id": LLM.model,
            "object": "model",
            "created": now,
            "owned_by": "lanlink-local",
            "permission": [],
        }, {
            "id": "lanlink-ecommerce-rag",
            "object": "model",
            "created": now,
            "owned_by": "lanlink-local",
            "permission": [],
        }],
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest):
    """OpenAI 兼容 Embeddings（基于知识库 TF-IDF 向量空间）。"""
    texts = [req.input] if isinstance(req.input, str) else req.input
    if not texts:
        raise HTTPException(status_code=400, detail={"error": {"message": "input 不能为空", "type": "invalid_request_error"}})
    from .retriever import embed, tokenize
    data = []
    total = 0
    for i, t in enumerate(texts):
        data.append({"object": "embedding", "index": i, "embedding": embed(t)})
        total += len(tokenize(t))
    return {"object": "list", "model": req.model, "data": data,
            "usage": {"prompt_tokens": total, "total_tokens": total}}


# ----------------------------- 知识库 / 网关自管理接口 -----------------------------

@app.get("/health")
def health():
    return {
        "status": "UP",
        "components": {
            "llm": {"backend": LLM.backend_name(), "model": LLM.model, "ollama_url": LLM.base_url},
            "knowledge_base": KB.stats(),
        },
        "uptime_sec": int(time.time() - GATEWAY_START),
    }


@app.get("/kb/search")
def kb_search(q: str, top_k: int = 4):
    """知识库向量检索调试接口。"""
    if not q.strip():
        raise HTTPException(status_code=400, detail="q 不能为空")
    return {"query": q, "scene": detect_scene(q), "results": KB.search(q, top_k=top_k)}


@app.post("/kb/reload")
def kb_reload():
    KB.reload()
    return {"status": "ok", "stats": KB.stats()}


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    # 统一错误格式与 OpenAI 对齐：{"error": {message, type}}
    detail = exc.detail
    if isinstance(detail, dict) and "error" in detail:
        content = detail
    else:
        content = {"error": {"message": str(detail), "type": "invalid_request_error"}}
    return JSONResponse(status_code=exc.status_code, content=content)


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    # 参数校验失败同样输出 OpenAI 标准错误结构
    msg = "; ".join(f"{'.'.join(str(x) for x in e.get('loc', []))}: {e.get('msg')}" for e in exc.errors())
    return JSONResponse(status_code=422, content={
        "error": {"message": msg, "type": "invalid_request_error", "param": None, "code": "invalid_value"}})


@app.exception_handler(Exception)
async def unhandled(request: Request, exc: Exception):
    # 统一错误格式与 OpenAI 对齐
    return JSONResponse(status_code=500, content={"error": {"message": str(exc), "type": "server_error"}})

"""RAG-Anything HTTP 服务（内部服务，仅供后端调用）。

端点：
  GET  /health          健康与配置快照（不触发 LLM）
  POST /ingest          写入文档内容块
  POST /query           多模态混合检索 + 生成

安全：除 /health 外都要求 `X-Internal-Secret` 与配置一致；未配置密钥时
拒绝启动写操作（fail-closed），避免"忘了配密钥 = 裸奔"。
"""
from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .config import settings
from .engine import engine

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("rag.main")

app = FastAPI(title="LANLink RAG-Anything Service", version="1.0.0")


class IngestRequest(BaseModel):
    docId: str = Field(min_length=1, max_length=200)
    # RAG-Anything 原生 content_list 结构
    contentList: list[dict[str, Any]] = Field(min_length=1)
    filePath: str | None = None


class QueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=4000)
    mode: str = "hybrid"
    topK: int | None = None


def _require_secret(secret: str | None) -> None:
    expected = settings.internal_secret
    if not expected:
        # 未配置密钥时禁止写/查：宁可 503，也不让内部服务裸奔
        raise HTTPException(status_code=503, detail="RAG_INTERNAL_SECRET 未配置，服务已拒绝请求")
    if secret != expected:
        raise HTTPException(status_code=403, detail="内部密钥不匹配")


@app.get("/health")
async def health() -> dict[str, Any]:
    return await engine.health()


@app.post("/ingest")
async def ingest(req: IngestRequest, x_internal_secret: str | None = Header(default=None)) -> dict[str, Any]:
    _require_secret(x_internal_secret)
    try:
        return await engine.ingest(req.docId, req.contentList, req.filePath)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:  # noqa: BLE001
        log.exception("ingest 失败 docId=%s", req.docId)
        raise HTTPException(status_code=500, detail=f"索引失败: {e}") from e


@app.post("/query")
async def query(req: QueryRequest, x_internal_secret: str | None = Header(default=None)) -> dict[str, Any]:
    _require_secret(x_internal_secret)
    try:
        answer = await engine.query(req.question, req.mode, req.topK)
        return {"answer": answer, "mode": req.mode}
    except Exception as e:  # noqa: BLE001
        log.exception("query 失败")
        raise HTTPException(status_code=500, detail=f"检索失败: {e}") from e

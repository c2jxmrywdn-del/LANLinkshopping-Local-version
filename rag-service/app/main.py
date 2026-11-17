"""RAG-Anything HTTP 服务（内部服务，仅供后端调用）。

端点：
  GET  /health          健康与配置快照（不触发 LLM）
  GET  /detail          完整配置快照（需密钥）
  POST /ingest          写入文档内容块
  POST /query           多模态混合检索 + 生成
  DELETE /doc/{docId}   删除文档及其全部索引残留
  POST /purge-cache     清空 LightRAG 的查询响应缓存

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

"""
关闭自动生成的 OpenAPI 文档。

`/docs`、`/redoc`、`/openapi.json` 会把全部端点、请求体结构暴露给任何能访问
8093 的人。本服务只绑回环，但"只绑回环"不是不加固的理由——本机其他进程、
容器内横向移动都能打到。运维排障用 /health 与日志足够。
"""
app = FastAPI(
    title="LANLink RAG-Anything Service",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


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
    """轻量健康检查：只报「能不能用」，不回显内部配置。

    原来这里直接返回 `engine.health()`，会把 `llmBaseUrl`、`llmModel`、
    `embedModel`、`workingDir` 一并吐出去。这些是内部拓扑信息，
    对健康检查没有价值，却方便了探测者。

    需要看详情时用 `GET /detail`（要求 X-Internal-Secret）。
    """
    h = await engine.health()
    return {"ok": bool(h.get("ok")), "initialized": bool(h.get("initialized"))}


@app.get("/detail")
async def detail(x_internal_secret: str | None = Header(default=None)) -> dict[str, Any]:
    """完整配置快照（需内部密钥），供运维排障。"""
    _require_secret(x_internal_secret)
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


@app.delete("/doc/{doc_id}")
async def delete_doc(
    doc_id: str,
    x_internal_secret: str | None = Header(default=None),
) -> dict[str, Any]:
    """删除一篇文档及其全部索引残留。

    幂等：文档不存在时返回 200 + `existed=false`，而不是 404。
    为什么不做成 404：后端删除入口可能被重复调用（重试、补偿任务），
    把「已经删干净」当成失败会让调用方误以为需要人工介入。

    只有「删完复核仍有残留」才返回 500——那种情况必须让调用方知道，
    否则又回到「接口说成功、检索还查得到」的静默失败。
    """
    _require_secret(x_internal_secret)
    if not doc_id or not doc_id.strip():
        raise HTTPException(status_code=400, detail="docId 不能为空")
    try:
        result = await engine.adelete(doc_id.strip())
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:  # noqa: BLE001
        log.exception("删除失败 docId=%s", doc_id)
        raise HTTPException(status_code=500, detail=f"删除失败: {e}") from e

    if result.get("residual"):
        # 复核仍能查到残留：必须显式失败，避免「接口说成功、检索还查得到」
        log.error("删除后复核仍有残留 docId=%s: %s", doc_id, result.get("warnings"))
        raise HTTPException(
            status_code=500,
            detail={
                "message": "删除后仍有残留，请查看 warnings",
                "docId": doc_id,
                "deleted": result.get("deleted"),
                "warnings": result.get("warnings"),
            },
        )
    if result.get("warnings"):
        log.warning("删除后仍有残留 docId=%s: %s", doc_id, result["warnings"])
    return result


@app.post("/purge-cache")
async def purge_cache(x_internal_secret: str | None = Header(default=None)) -> dict[str, Any]:
    """清理 LightRAG 的查询响应缓存（保留实体抽取缓存）。"""
    _require_secret(x_internal_secret)
    try:
        removed = engine.purge_query_cache()
    except Exception as e:  # noqa: BLE001
        log.exception("清理查询缓存失败")
        raise HTTPException(status_code=500, detail=f"清理缓存失败: {e}") from e
    return {"removed": removed}

# -*- coding: utf-8 -*-
"""资讯管线 HTTP 路由：POST /v1/news/extract 与 /v1/news/extract:batch。

- 角色门显式化：news_extract 仅登记 ROLE_TOOL_MAP buyer/service（tools.py），
  **不进 READONLY_BACKEND_TOOLS**——空角色/未知角色/support/finance/fulfillment
  一律 403（is_tool_allowed_for_role 对空/未知角色走只读白名单回退，news_extract
  不在该集，自然拒绝）。拒绝响应与 /v1/ai/tools/execute 同形（403 permission_denied）。
- 路由层不做任何入库决定（网关无 MySQL）：batch 结果逐条返回 verdict/flags 留痕，
  flags.stored=true 语义 = 允许调用方落库（Java 编排层按此执行）。
- 继承 app 级 require_gateway_key + rate_limit_guard（main.py dependencies）。
"""
import logging
import os
import time

from fastapi import HTTPException, Request
from pydantic import BaseModel, Field

from .. import tools as TOOLBOX
from ..metrics import REGISTRY
from . import structured_extract

logger = logging.getLogger("lanlink.ai-gateway")

NEWS_TOOL_NAME = "news_extract"
BATCH_MAX_ITEMS = 5

#: /v1/news/extract 的 schema 定义（仅供 GET /v1/ai/tools 清单展示，不进 BUILTIN_TOOL_DEFS——
#: 本端点是结构化 HTTP 端点而非模型工具，模型 agent 循环不分发到它）。
NEWS_EXTRACT_TOOL_DEF = {
    "type": "function",
    "function": {
        "name": NEWS_TOOL_NAME,
        "description": ("资讯结构化抓取（buyer/service 专用）：过 robots 门禁与逐跳 SSRF 守卫抓取"
                        "新闻/资讯页，返回标题/正文/发布时间/指纹/判重结论等结构化字段；"
                        "网关不落库，落库放行由后端编排层按 flags.stored 执行。"),
        "parameters": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "资讯页 URL（http/https）"},
                "sourceHint": {"type": "string", "description": "来源提示（如 xinhua），用于指标维度"},
                "maxChars": {"type": "integer", "description": "正文最大字符数，默认 20000"},
            },
            "required": ["url"],
        },
    },
}


class NewsExtractRequest(BaseModel):
    url: str = Field(..., min_length=8, description="资讯页 URL")
    sourceHint: str = ""
    maxChars: int = 20000


class NewsBatchRequest(BaseModel):
    items: list[NewsExtractRequest] = Field(..., max_length=BATCH_MAX_ITEMS)


def _resolve_role(request: Request, body_role: str | None = None) -> str:
    """与 main._resolve_tool_role 同款解析（避免 main ↔ news 循环导入，运行时转发）。"""
    from ..main import _resolve_tool_role
    return _resolve_tool_role(request, body_role)


def _deny(role: str) -> HTTPException:
    """与 main._deny_tool 同形（403 permission_denied），并计入同一越权审计指标。"""
    logger.warning("[news] 越权访问被拒 role=%r tool=%s", role or None, NEWS_TOOL_NAME)
    try:
        REGISTRY.tool_calls_total.inc({"tool": NEWS_TOOL_NAME,
                                       "role": role or "unknown", "success": "false"})
    except Exception:  # noqa: BLE001
        pass
    detail = {
        "message": f"角色 {role or '(未声明)'} 无权使用工具: {NEWS_TOOL_NAME}",
        "type": "permission_denied",
        "denied_tools": [NEWS_TOOL_NAME],
    }
    return HTTPException(status_code=403, detail={"error": detail})


def _pipeline_enabled() -> bool:
    """LANLINK_NEWSPIPELINE_ENABLED=0 时端点整体 404（既有链路零行为变化）。"""
    return (os.environ.get("LANLINK_NEWSPIPELINE_ENABLED", "0").strip().lower()
            in ("1", "true", "yes", "on"))


def register_news_routes(app) -> None:
    @app.post("/v1/news/extract")
    def news_extract(req: NewsExtractRequest, request: Request):
        if not _pipeline_enabled():
            raise HTTPException(status_code=404, detail={
                "error": {"message": "not found", "type": "invalid_request_error"}})
        role = _resolve_role(request, None)
        if not TOOLBOX.is_tool_allowed_for_role(NEWS_TOOL_NAME, role):
            raise _deny(role)
        t0 = time.time()
        try:
            data = structured_extract.extract(req.url, source_hint=req.sourceHint,
                                              max_chars=max(200, int(req.maxChars)))
        except Exception as exc:  # 管线自身崩溃才 ok=false
            logger.warning("[news] extract 异常: %s", exc)
            REGISTRY.news_fetch_total.inc(
                {"source": req.sourceHint or "manual", "result": "error"})
            return {"ok": False, "error": f"资讯管线内部异常: {exc}"}
        result = data or {"source": {"originSite": req.sourceHint or "unknown"},
                          "flags": {"stored": False}}
        REGISTRY.news_fetch_total.inc({
            "source": req.sourceHint or "manual",
            "result": _result_label(result)})
        REGISTRY.news_pipeline_latency.observe(
            (time.time() - t0), {"stage": "single"})
        return {"ok": True, "data": result,
                "elapsedMs": int((time.time() - t0) * 1000)}

    @app.post("/v1/news/extract:batch")
    def news_extract_batch(req: NewsBatchRequest, request: Request):
        if not _pipeline_enabled():
            raise HTTPException(status_code=404, detail={
                "error": {"message": "not found", "type": "invalid_request_error"}})
        role = _resolve_role(request, None)
        if not TOOLBOX.is_tool_allowed_for_role(NEWS_TOOL_NAME, role):
            raise _deny(role)
        t0 = time.time()
        results = []
        for item in req.items[:BATCH_MAX_ITEMS]:
            try:
                data = structured_extract.extract(item.url, source_hint=item.sourceHint,
                                                  max_chars=max(200, int(item.maxChars)))
            except Exception as exc:  # 单条异常不阻塞批次
                logger.warning("[news] batch item 异常: %s", exc)
                REGISTRY.news_fetch_total.inc(
                    {"source": item.sourceHint or "manual", "result": "error"})
                data = None
            if data is None:
                results.append({"ok": False, "error": "资讯管线内部异常"})
                continue
            REGISTRY.news_fetch_total.inc({
                "source": item.sourceHint or "manual",
                "result": _result_label(data)})
            results.append({"ok": True, "data": data})
        batch_ms = int((time.time() - t0) * 1000)
        REGISTRY.news_pipeline_latency.observe(batch_ms / 1000.0, {"stage": "batch"})
        return {"results": results, "batchMs": batch_ms}


def _result_label(data: dict) -> str:
    """把单条结果折算成 news_fetch_total 的 result 维度（robots_denied/fetch_error/ok）。"""
    try:
        errs = ((data.get("aiGateway") or {}).get("errors") or [])
        if errs:
            e = errs[0]
            if "robots" in e:
                return "robots_denied"
            if "SSRF" in e:
                return "ssrf_blocked"
            return "fetch_error"
        return "ok"
    except Exception:
        return "ok"

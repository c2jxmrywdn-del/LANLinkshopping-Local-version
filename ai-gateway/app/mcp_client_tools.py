# -*- coding: utf-8 -*-
"""MCP fetch/playwright 工具桥（网关侧 HTTP JSON-RPC 客户端）。

把内网的两个 MCP server（mcp-fetch 自建桥 / mcp-playwright 官方镜像，
均 streamable HTTP）的工具经 register_local_tool 注册进网关本地工具表。
会话握手、tools/list、tools/call 仿 tools.py 的 context7 客户端模式
（_mcp_post/_ctx7_session），零新依赖（httpx 已有）。

安全边界：
- URL 参数在 handler 入口先过 tools._ssrf_guard（MCP server 自身不受网关守卫
  保护，这道门必须在调用 MCP server 之前）。
- 工具静态白名单翻译：只暴露 MCP_TOOL_ALLOWLIST 声明的原始工具，server 升级
  新增的高危工具（如 browser_evaluate/browser_run_code_unsafe）不会静默上线。
- 降级：注册期/重探期不可达时静默摘除（只记 warning），调用期异常折叠为
  ok=False JSON，绝不抛出——与 execute_tool 契约一致。

开关（全部经 compose env 注入，改后需重建网关容器）：
- LANLINK_MCP_ENABLED 总闸（默认 0）
- LANLINK_MCP_FETCH_URL / LANLINK_MCP_PLAYWRIGHT_URL（留空=该 server 禁用）
- LANLINK_MCP_FETCH_TIMEOUT_MS / LANLINK_MCP_PLAYWRIGHT_TIMEOUT_MS
- LANLINK_MCP_HEALTH_INTERVAL_SEC 健康重探周期（连续 2 次失败摘除）
- LANLINK_MCP_BLOCKED_HOSTS 出站黑名单（缓解公网 302→内网的残余风险）
- LANLINK_MCP_ROLE_TOOLS 角色→工具映射（解析见 tools.MCP_ROLE_TOOL_MAP）
"""
from __future__ import annotations

import json
import logging
import os
import socket
import threading
import time
from urllib.parse import urlparse

import httpx

from . import tools as TOOLBOX

logger = logging.getLogger("lanlink.ai-gateway.mcp-bridge")

# 独立 handler + 显式 level：主进程 root logger 是 WARNING（uvicorn 默认无
# basicConfig），挂在共享 logger 上的 INFO 行（注册成功/调用审计 JSON）会被
# effective level=30 过滤，docker logs 里根本看不到——审计要求 server/tool/
# url/elapsedMs/isError 五字段行必须可见（实测缺陷：网关容器内 root level=30，
# 0 条 mcp-bridge 输出）。默认 INFO；LANLINK_MCP_LOG_LEVEL=WARNING 可静默。
logger.setLevel(os.environ.get("LANLINK_MCP_LOG_LEVEL", "INFO").strip().upper() or "INFO")
_log_handler = logging.StreamHandler()
_log_handler.setFormatter(logging.Formatter("%(name)s %(levelname)s %(message)s"))
logger.addHandler(_log_handler)
logger.propagate = False  # 不再向上冒泡，避免换根 handler 后输出重复

# 会话握手超时（秒）：探测与握手都不允许拖慢启动线程
_HANDSHAKE_TIMEOUT = 8.0

# ---- 静态白名单：允许注册的 MCP 原始工具名（按 server 分组）----
# schema 按真实 server 的 tools/list 手写（官方镜像实测抓取，见验收 B3）；
# 白名单外工具（browser_evaluate / browser_run_code_unsafe 等 RCE 级能力）
# 一律跳过并记 warning，防 server 升级静默上线。
MCP_TOOL_ALLOWLIST: dict[str, frozenset[str]] = {
    "fetch": frozenset({"fetch"}),
    "playwright": frozenset({"browser_navigate", "browser_snapshot",
                             "browser_find", "browser_close"}),
}

MCP_FETCH_TOOL_DEF = {
    "type": "function",
    "function": {
        "name": "mcp_fetch",
        "description": ("抓取指定网页并转为 Markdown 正文（官方 fetch server）。"
                        "用于 web_search 之后读取具体页面；返回内容自带截断游标，"
                        "配合 start_index 可继续读取。"),
        "parameters": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "要抓取的网页 URL（http/https）"},
                "max_length": {"type": "integer", "description": "返回正文最大字符数（1-999999），默认 5000"},
                "start_index": {"type": "integer", "description": "起始字符下标（续读上次截断的内容），默认 0"},
                "raw": {"type": "boolean", "description": "true=返回原始 HTML 而非 Markdown 提取，默认 false"},
                "timeout_ms": {"type": "integer", "description": "连接/读取超时毫秒（100-600000），默认 30000"},
            },
            "required": ["url"],
        },
    },
}

MCP_BROWSER_NAVIGATE_DEF = {
    "type": "function",
    "function": {
        "name": "mcp_browser_navigate",
        "description": ("无头浏览器导航到 URL（playwright）。导航后用 mcp_browser_snapshot "
                        "查看页面、mcp_browser_find 定位元素。每次调用都是全新会话，"
                        "无历史页面。"),
        "parameters": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "要导航到的 URL（http/https）"},
            },
            "required": ["url"],
        },
    },
}

MCP_BROWSER_SNAPSHOT_DEF = {
    "type": "function",
    "function": {
        "name": "mcp_browser_snapshot",
        "description": ("截取当前页面的无障碍快照（比截图更省 token）。必须先 "
                        "mcp_browser_navigate 打开页面。"),
        "parameters": {
            "type": "object",
            "properties": {
                "target": {"type": "string", "description": "限定某元素（快照 ref 或选择器），可选"},
                "depth": {"type": "number", "description": "限制快照树深度，可选"},
                "boxes": {"type": "boolean", "description": "是否附带元素包围盒坐标，可选"},
            },
        },
    },
}

MCP_BROWSER_FIND_DEF = {
    "type": "function",
    "function": {
        "name": "mcp_browser_find",
        "description": ("在当前页面快照中按文本或正则搜索，返回命中节点及上下文"
                        "（比整页快照便宜）。必须先 mcp_browser_navigate。"),
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "要搜索的文本（大小写不敏感子串），与 regex 二选一"},
                "regex": {"type": "string", "description": "正则（如 /error/i 带旗标），与 text 二选一"},
                "maxResults": {"type": "number", "description": "最多返回条数，默认全部"},
            },
        },
    },
}

MCP_BROWSER_CLOSE_DEF = {
    "type": "function",
    "function": {
        "name": "mcp_browser_close",
        "description": "关闭浏览器页面并释放会话。",
        "parameters": {"type": "object", "properties": {}},
    },
}

# server key → 工具定义（注册顺序决定清单顺序）
_SERVER_TOOL_DEFS: dict[str, list[dict]] = {
    "fetch": [MCP_FETCH_TOOL_DEF],
    "playwright": [MCP_BROWSER_NAVIGATE_DEF, MCP_BROWSER_SNAPSHOT_DEF,
                   MCP_BROWSER_FIND_DEF, MCP_BROWSER_CLOSE_DEF],
}

# 网关工具名 → (server key, 原始工具名)
_TOOL_ROUTING: dict[str, tuple[str, str]] = {
    "mcp_fetch": ("fetch", "fetch"),
    "mcp_browser_navigate": ("playwright", "browser_navigate"),
    "mcp_browser_snapshot": ("playwright", "browser_snapshot"),
    "mcp_browser_find": ("playwright", "browser_find"),
    "mcp_browser_close": ("playwright", "browser_close"),
}


def _enabled() -> bool:
    return os.environ.get("LANLINK_MCP_ENABLED", "0").strip().lower() in ("1", "true", "yes", "on")


def _server_url(server_key: str) -> str:
    var = {"fetch": "LANLINK_MCP_FETCH_URL",
           "playwright": "LANLINK_MCP_PLAYWRIGHT_URL"}[server_key]
    return (os.environ.get(var) or "").strip()


def _call_timeout(server_key: str) -> float:
    var = {"fetch": "LANLINK_MCP_FETCH_TIMEOUT_MS",
           "playwright": "LANLINK_MCP_PLAYWRIGHT_TIMEOUT_MS"}[server_key]
    try:
        return int(os.environ.get(var) or "0") / 1000.0
    except ValueError:
        return {"fetch": 30.0, "playwright": 60.0}[server_key]


def _blocked_hosts() -> frozenset[str]:
    raw = (os.environ.get("LANLINK_MCP_BLOCKED_HOSTS") or "").strip()
    return frozenset(h.strip().lower() for h in raw.split(",") if h.strip())


def _tcp_alive(url: str, timeout: float = 2.0) -> bool:
    """健康探测：TCP 连得上即视为 server 在线（不握手，仿方案定义）。"""
    u = urlparse(url)
    host, port = u.hostname, u.port or (443 if u.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


# ---- JSON-RPC over streamable HTTP（手写，仿 tools._mcp_post/_ctx7_session）----

def _rpc_post(c: httpx.Client, url: str, session: str | None, payload: dict,
              timeout: float) -> tuple[dict, str]:
    headers = {"Accept": "application/json, text/event-stream"}
    if session:
        headers["Mcp-Session-Id"] = session
    r = c.post(url, json=payload, headers=headers, timeout=timeout)
    r.raise_for_status()
    sid = r.headers.get("mcp-session-id", session or "")
    ctype = r.headers.get("content-type", "")
    body = r.text
    if "event-stream" in ctype:
        for line in reversed([ln for ln in body.splitlines() if ln.startswith("data:")]):
            try:
                return json.loads(line[5:].strip()), sid
            except Exception:
                continue
        raise ValueError("SSE 响应中无有效 JSON")
    return json.loads(body), sid


def _open_session(c: httpx.Client, url: str, timeout: float) -> str:
    init, sid = _rpc_post(c, url, None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "lanlink-gateway", "version": "1.0"}}}, timeout)
    if init.get("error"):
        raise ValueError("initialize: " + str(init["error"])[:200])
    # notifications/initialized 的响应允许失败（部分 server 回 202/空体）
    try:
        _rpc_post(c, url, sid, {"jsonrpc": "2.0", "method": "notifications/initialized"},
                  timeout)
    except Exception:
        pass
    return sid


def _call_tool(server_key: str, tool_name: str, args: dict) -> dict:
    """tools/call 一条龙：握手（404 时重握手）→ 调用 → 折叠结果。"""
    url = _server_url(server_key)
    timeout = _call_timeout(server_key)
    with httpx.Client(timeout=max(timeout, _HANDSHAKE_TIMEOUT) + 5) as c:
        session = None
        for attempt in (1, 2):
            try:
                session = _open_session(c, url, _HANDSHAKE_TIMEOUT)
                payload, _ = _rpc_post(c, url, session, {
                    "jsonrpc": "2.0", "id": 3, "method": "tools/call",
                    "params": {"name": tool_name, "arguments": args}}, timeout)
                break
            except httpx.HTTPStatusError as exc:
                # 会话过期时 server 回 404：重握手一次
                if exc.response.status_code == 404 and attempt == 1:
                    continue
                raise
        if payload.get("error"):
            return TOOLBOX._mk_result(False, error=str(payload["error"])[:300])
        result = payload.get("result") or {}
        texts = [seg.get("text", "") for seg in (result.get("content") or [])
                 if isinstance(seg, dict)]
        text = "\n".join(t for t in texts if t)
        if result.get("isError"):
            return TOOLBOX._mk_result(False, server=server_key, error=text[:500] or "工具执行失败")
        return TOOLBOX._mk_result(True, server=server_key, content=text)


# ---- 注册入口 ----

_REGISTERED: bool = False


def register_mcp_tools() -> None:
    """总入口：按 env 决定是否向各 MCP server 注册白名单工具。

    模块级调用（main.py RAG 注册行之后）。不可达时静默返回（只 warning），
    工具不进注册表，清单与执行两侧自然收敛。
    """
    global _REGISTERED
    if not _enabled() or _REGISTERED:
        return
    _REGISTERED = True
    for server_key in ("fetch", "playwright"):
        url = _server_url(server_key)
        if not url:
            continue
        _register_one_server(server_key, url)


def _register_one_server(server_key: str, url: str) -> None:
    try:
        if not _tcp_alive(url):
            raise ConnectionError("TCP 探测不可达")
        # tools/list 校验白名单交集（schema 以本地静态定义为准，server 只是可达性佐证）
        with httpx.Client(timeout=_HANDSHAKE_TIMEOUT + 2) as c:
            sid = _open_session(c, url, _HANDSHAKE_TIMEOUT)
            payload, _ = _rpc_post(c, url, sid, {
                "jsonrpc": "2.0", "id": 2, "method": "tools/list"}, _HANDSHAKE_TIMEOUT)
        if payload.get("error"):
            raise ValueError("tools/list: " + str(payload["error"])[:200])
        server_names = {t.get("name") for t in (payload.get("result") or {}).get("tools") or []}
        allow = MCP_TOOL_ALLOWLIST[server_key]
        # 白名单外工具（如 browser_evaluate / browser_run_code_unsafe）显式告警——
        # server 升级新增高危工具必须人工评审后加白名单才会暴露给模型。
        for outside in sorted(server_names - allow):
            logger.warning("[mcp-bridge] server=%s 出现白名单外工具 %s，已跳过"
                           "（如需暴露请人工评审后加入 MCP_TOOL_ALLOWLIST）",
                           server_key, outside)
        registered = 0
        for raw_name in sorted(allow):
            if raw_name not in server_names:
                logger.warning("[mcp-bridge] server=%s 未提供白名单工具 %s，跳过",
                               server_key, raw_name)
                continue
            gw_name = _gw_name(server_key, raw_name)
            definition = _def_for(gw_name)
            if definition is None:
                continue
            TOOLBOX.register_local_tool(definition, _make_handler(server_key, gw_name))
            TOOLBOX.register_mcp_tool_name(gw_name)
            registered += 1
        logger.info("[mcp-bridge] server=%s 注册 %d 个工具（%s）",
                    server_key, registered, url)
    except Exception as exc:
        logger.warning("[mcp-bridge] server=%s unreachable, tools suppressed: %s",
                       server_key, exc)


def _gw_name(server_key: str, raw_name: str) -> str:
    for gw, (sk, rn) in _TOOL_ROUTING.items():
        if sk == server_key and rn == raw_name:
            return gw
    return ""


def _def_for(gw_name: str) -> dict | None:
    for defs in _SERVER_TOOL_DEFS.values():
        for d in defs:
            if d["function"]["name"] == gw_name:
                return d
    return None


def _host_blocked(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    if not host:
        return False
    blocked = _blocked_hosts()
    return url.strip().lower() in blocked or host in blocked


def _make_handler(server_key: str, gw_name: str):
    def handler(args: dict) -> dict:
        args = args or {}
        raw_name = _TOOL_ROUTING[gw_name][1]
        # URL 参数一律入口守卫（mcp_fetch / mcp_browser_navigate）
        url = str(args.get("url") or "").strip()
        if url:
            if _host_blocked(url):
                return TOOLBOX._mk_result(False, url=url,
                                          error="目标主机在 LANLINK_MCP_BLOCKED_HOSTS 黑名单中")
            guard = TOOLBOX._ssrf_guard(url)
            if guard:
                return TOOLBOX._mk_result(False, url=url, error=guard)
        t0 = time.time()
        try:
            out = _call_tool(server_key, raw_name, args)
        except Exception as exc:
            out = TOOLBOX._mk_result(False, server=server_key,
                                     error=f"MCP server 不可达: {type(exc).__name__}: {str(exc)[:200]}")
        out.setdefault("tool", gw_name)
        out.setdefault("elapsedMs", int((time.time() - t0) * 1000))
        _log_call(server_key, gw_name, url, out)
        return out

    return handler


def _log_call(server_key: str, gw_name: str, url: str, out: dict) -> None:
    logger.info("[mcp-bridge] %s", json.dumps({
        "server": server_key, "tool": gw_name, "url": url or None,
        "elapsedMs": out.get("elapsedMs"), "isError": not out.get("ok", False),
    }, ensure_ascii=False))


# ---- 健康重探线程（连续 2 次失败摘除 / 恢复重注册）----

_health_thread: threading.Thread | None = None
_health_stop = threading.Event()


_fail_count_by_server: dict[str, int] = {}


def _server_tool_names(server_key: str) -> list[str]:
    """该 server 当前已注册的网关工具名（健康摘除用）。"""
    return [gw for gw, (sk, _rn) in _TOOL_ROUTING.items()
            if sk == server_key and TOOLBOX.is_local_tool(gw)]


def _drop_server_tools(server_key: str) -> None:
    """静默摘除该 server 的全部工具（健康重探连续失败时调用）。

    注意同步清 `_TOOL_NAMES`：register_local_tool 注册时把名字加进集合，
    摘除时只清 LOCAL_HANDLERS 不清集合的话，恢复重注册会撞
    「本地工具名称与既有工具冲突」防线，工具永远回不来。
    """
    names = _server_tool_names(server_key)
    for gw in names:
        TOOLBOX.unregister_mcp_tool_name(gw)
        TOOLBOX.LOCAL_TOOL_DEFS[:] = [
            d for d in TOOLBOX.LOCAL_TOOL_DEFS if d["function"]["name"] != gw]
        TOOLBOX.LOCAL_HANDLERS.pop(gw, None)
        TOOLBOX._TOOL_NAMES.discard(gw)
    if names:
        logger.warning("[mcp-bridge] server=%s unreachable, tools suppressed: %s",
                       server_key, ", ".join(names))


def _health_loop() -> None:
    interval = 30.0
    try:
        interval = float(os.environ.get("LANLINK_MCP_HEALTH_INTERVAL_SEC") or 30)
    except ValueError:
        pass
    while not _health_stop.wait(interval):
        if not _enabled():
            continue
        for server_key in ("fetch", "playwright"):
            url = _server_url(server_key)
            if not url:
                continue
            names = _server_tool_names(server_key)
            try:
                alive = _tcp_alive(url)
            except Exception:
                alive = False
            if alive:
                _fail_count_by_server[server_key] = 0
                if not names:
                    # 曾被摘除：重新注册（register_local_tool 幂等覆盖更新）
                    logger.info("[mcp-bridge] server=%s 恢复，重新注册工具", server_key)
                    _register_one_server(server_key, url)
                continue
            fails = _fail_count_by_server.get(server_key, 0) + 1
            _fail_count_by_server[server_key] = fails
            if fails >= 2 and names:
                _drop_server_tools(server_key)


def start_health_loop() -> None:
    """启动健康重探线程（startup 事件调用一次；仿 semantic_cache pubsub 线程模式）。"""
    global _health_thread
    if not _enabled() or (_health_thread and _health_thread.is_alive()):
        return
    _health_stop.clear()
    _health_thread = threading.Thread(target=_health_loop,
                                      name="mcp-bridge-health", daemon=True)
    _health_thread.start()

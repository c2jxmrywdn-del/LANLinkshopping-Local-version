# -*- coding: utf-8 -*-
"""本地 Qwen2.5 工具执行器（web_search / fetch_page / context7_docs / think）。

设计要点：
- 工具定义采用 OpenAI function calling schema；llama.cpp(b11913) 与 Ollama OpenAI 层均原生支持，
  自动路由 = 模型基于 tools 定义自主决定"是否调用/调用哪个/参数是什么"（关键词由模型从用户
  query 自动提取后作为 web_search 的 query 入参，无需用户指定工具名）。
- 执行器只做"取数与清洗"；综合/分析由模型在 agent 循环中基于 tool 结果完成。
  fetch_page 的超长正文在工具内做一次模型摘要压缩（summarize 回调由 main.py 注入，
  在 ModelCallGate 之外执行，避免嵌套取锁）。
- 所有失败都返回结构化错误 JSON（绝不抛异常到模型），模型可在下一轮自行兜底/改述。
- 实测结论（2026-10-27 排查）：DuckDuckGo 全系被墙不可用；Bing 容器内 200 可解析；
  Context7 MCP（mcp.context7.com/mcp）免 key 全链路可用；百度为备用引擎。
"""
import html
import json
import os
import re
import time
from urllib.parse import quote, urlparse

import httpx

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
TOOL_TIMEOUT = float(os.environ.get("LANLINK_TOOL_TIMEOUT", "15"))
SEARCH_MAX_RESULTS = int(os.environ.get("LANLINK_SEARCH_MAX_RESULTS", "8"))
FETCH_MAX_CHARS = int(os.environ.get("LANLINK_FETCH_MAX_CHARS", "6000"))
CTX7_MCP_URL = os.environ.get("LANLINK_CTX7_MCP", "https://mcp.context7.com/mcp")

try:
    import trafilatura  # 正文提取（requirements 已装；缺失时走内置降级提取器）
except Exception:  # pragma: no cover
    trafilatura = None


# ============================ 工具定义（OpenAI schema） ============================

TOOL_DEFS = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": ("联网搜索工具。当问题涉及实时信息、时效性行情、你不确定的最新事实时调用。"
                            "必须先把用户的请求提炼成 2-4 个词、以空格分隔的自然搜索关键词作为 query"
                            "（如：螺纹钢 价格；不要照抄整句、不要超过 4 个词）。"
                            "engine 可选 bing（默认）/baidu；结果为标题+链接+摘要的结构化列表。"),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "搜索关键词（从用户请求自动提炼）"},
                    "engine": {"type": "string", "enum": ["bing", "baidu", "auto"],
                               "description": "搜索引擎，默认 auto（bing 失败自动换 baidu）"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_page",
            "description": ("网页内容爬取与总结工具。传入 URL 自动提取正文（过滤广告/导航/页脚等冗余），"
                            "超长内容自动压缩为要点摘要（保留事实、数字与结论）。"
                            "常在 web_search 之后对最相关的 1 条结果调用。"),
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "要抓取的网页 URL（http/https）"},
                    "max_chars": {"type": "integer", "description": "返回正文/摘要的最大字符数，默认 6000"},
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "context7_docs",
            "description": ("技术文档/代码库上下文检索（Context7）。当问题涉及具体框架/库的 API 用法、"
                            "配置、版本特性时调用：library 传框架或库名（如 vue、fastapi、mybatis-plus，"
                            "可带仓库名如 websites/vuejs_org），topic 传要查的主题关键词（如 路由、事务）。"),
            "parameters": {
                "type": "object",
                "properties": {
                    "library": {"type": "string", "description": "框架/库名（可带 /仓库 ID）"},
                    "topic": {"type": "string", "description": "要检索的具体主题（可选）"},
                },
                "required": ["library"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "think",
            "description": ("增强思考工具。复杂任务（多步规划、多条件权衡、计算推理）执行过程中调用，"
                            "把当前推理步骤写入 thought；mode=cot 链式思考 / reflection 反思校验 / "
                            "multipath 多路径对比。返回引导提示，帮助你组织下一步推理。"
                            "thought 必须控制在 100 字内的要点式短句（严禁长文，否则会被截断报错）。"),
            "parameters": {
                "type": "object",
                "properties": {
                    "thought": {"type": "string", "description": "当前中间推理步骤（简洁，200 字内）"},
                    "mode": {"type": "string", "enum": ["cot", "reflection", "multipath"],
                             "description": "思考模式"},
                    "next_action": {"type": "string", "description": "计划中的下一步（可选）"},
                },
                "required": ["thought"],
            },
        },
    },
]

_TOOL_NAMES = {d["function"]["name"] for d in TOOL_DEFS}


# ============================ 基础设施 ============================

def _client() -> httpx.Client:
    return httpx.Client(timeout=TOOL_TIMEOUT, follow_redirects=True,
                        headers={"User-Agent": UA, "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"})


def _strip_tags(fragment: str) -> str:
    text = re.sub(r"<[^>]+>", " ", fragment or "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


class _SimpleTextExtractor:
    """trafilatura 缺失时的降级正文提取：去掉 script/style/nav 等噪声标签后取块文本。"""

    _NOISE = re.compile(r"<(script|style|nav|header|footer|aside|noscript|form|iframe|svg)\b.*?</\1>",
                        re.S | re.I)
    _BLOCK = re.compile(r"<(p|li|h1|h2|h3|h4|td|div)[^>]*>(.*?)</\1>", re.S | re.I)

    @classmethod
    def extract(cls, raw: str) -> str:
        body = cls._NOISE.sub(" ", raw or "")
        parts = [_strip_tags(m.group(2)) for m in cls._BLOCK.finditer(body)]
        lines = [p for p in parts if len(p) >= 12]
        return "\n".join(lines[:400])


def _ssrf_guard(url: str) -> str | None:
    """只允许 http(s) 且拒绝内网/环回地址（本地工具不作为内网跳板）。"""
    try:
        u = urlparse(url)
        host = (u.hostname or "").lower()
        if u.scheme not in ("http", "https"):
            return "仅支持 http/https URL"
        if (not host or host in ("localhost", "host.docker.internal")
                or re.match(r"^(127\.|10\.|192\.168\.|169\.254\.|0\.0\.0\.0)", host)
                or re.match(r"^172\.(1[6-9]|2\d|3[01])\.", host)):
            return "不允许访问内网/环回地址"
        return None
    except Exception as exc:
        return f"URL 解析失败: {exc}"


def _truncate_head_tail(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    head = int(max_chars * 0.7)
    tail = max_chars - head
    return text[:head] + "\n…(中略)…\n" + text[-tail:]


def _mk_result(ok: bool, **data) -> dict:
    return dict({"ok": ok}, **data)


# ============================ 联网搜索 ============================

_BING_BLOCK = re.compile(r'<li class="b_algo".*?</li>', re.S)
_BING_LINK = re.compile(r'<h2[^>]*>\s*<a[^>]+href="(http[^"]+)"[^>]*>(.*?)</a>', re.S)
_BING_SNIPPET = re.compile(r'<p[^>]*>(.*?)</p>', re.S)
_BAIDU_BLOCK = re.compile(r'<h3[^>]*>\s*<a[^>]+href="(http[^"]+)"[^>]*>(.*?)</a>', re.S)
_BAIDU_SNIPPET = re.compile(r'class="c-abstract[^"]*"[^>]*>(.*?)</div>', re.S)


def _parse_bing(raw: str, limit: int) -> list[dict]:
    out = []
    seen = set()
    for block in _BING_BLOCK.finditer(raw):
        m = _BING_LINK.search(block.group(0))
        if not m:
            continue
        url, title = m.group(1), _strip_tags(m.group(2))
        if url in seen or not title:
            continue
        seen.add(url)
        sm = _BING_SNIPPET.search(block.group(0))
        out.append({"title": title[:120], "url": url, "snippet": _strip_tags(sm.group(1))[:220] if sm else ""})
        if len(out) >= limit:
            break
    return out


def _parse_baidu(raw: str, limit: int) -> list[dict]:
    out, seen = [], set()
    for m in _BAIDU_BLOCK.finditer(raw):
        url, title = m.group(1), _strip_tags(m.group(2))
        if url in seen or not title:
            continue
        seen.add(url)
        out.append({"title": title[:120], "url": url, "snippet": ""})
        if len(out) >= limit:
            break
    # 摘要与链接分离渲染，按标题就近补齐
    snips = [_strip_tags(s.group(1))[:220] for s in _BAIDU_SNIPPET.finditer(raw)]
    for i, item in enumerate(out):
        if i < len(snips):
            item["snippet"] = snips[i]
    return out


_BING_RSS_ITEM = re.compile(r"<item>(.*?)</item>", re.S)


def _search_bing_rss(c: httpx.Client, query: str, limit: int) -> list[dict]:
    """Bing RSS 端点（format=rss）：结构化 XML、无反爬布局，容器内实测对中文行情查询命中精准
    （如"螺纹钢 价格"→ mysteel/东方财富）。HTML 版对部分长查询会整页降级成无关结果。"""
    r = c.get("https://www.bing.com/search", params={"q": query, "format": "rss", "count": str(limit)})
    out = []
    for it in _BING_RSS_ITEM.findall(r.text)[:limit]:
        t = re.search(r"<title>(.*?)</title>", it, re.S)
        l = re.search(r"<link>(.*?)</link>", it, re.S)
        d = re.search(r"<description>(.*?)</description>", it, re.S)
        if not (t and l):
            continue
        out.append({"title": _strip_tags(t.group(1))[:120], "url": _strip_tags(l.group(1)),
                    "snippet": _strip_tags(d.group(1))[:220] if d else ""})
    return out


def _search_tavily(query: str, limit: int) -> list[dict] | None:
    key = os.environ.get("TAVILY_API_KEY")
    if not key:
        return None
    r = _client().post("https://api.tavily.com/search", json={
        "api_key": key, "query": query, "max_results": limit, "search_depth": "basic"})
    r.raise_for_status()
    return [{"title": x.get("title", ""), "url": x.get("url", ""), "snippet": x.get("content", "")[:220]}
            for x in (r.json().get("results") or [])][:limit]


def _search_serper(query: str, limit: int) -> list[dict] | None:
    key = os.environ.get("SERPER_API_KEY")
    if not key:
        return None
    r = _client().post("https://google.serper.dev/search",
                       headers={"X-API-KEY": key}, json={"q": query, "num": limit})
    r.raise_for_status()
    return [{"title": x.get("title", ""), "url": x.get("link", ""), "snippet": x.get("snippet", "")}
            for x in (r.json().get("organic") or [])][:limit]


def web_search(query: str, engine: str = "auto") -> dict:
    query = (query or "").strip()
    if not query:
        return _mk_result(False, error="query 不能为空")
    limit = SEARCH_MAX_RESULTS
    # 配置了 key 型引擎则优先（多引擎策略：key 引擎 > bing > baidu）
    for fn in (_search_tavily, _search_serper):
        try:
            results = fn(query, limit)
            if results is not None:
                return _mk_result(True, query=query, engine=fn.__name__, count=len(results), results=results)
        except Exception:
            continue
    eng = (engine or "auto").lower()
    engines = ["bing", "baidu"] if eng in ("auto", "") else [eng, "bing" if eng == "baidu" else "baidu"]
    last_err = ""
    with _client() as c:
        for name in engines:
            try:
                if name == "bing":
                    # RSS 结构化端点优先，HTML 解析兜底（cn.bing + mkt=zh-CN）
                    results = _search_bing_rss(c, query, limit)
                    if not results:
                        r = c.get("https://cn.bing.com/search",
                                  params={"q": query, "mkt": "zh-CN", "setlang": "zh-hans",
                                          "count": str(limit)})
                        results = _parse_bing(r.text, limit)
                else:
                    r = c.get("https://www.baidu.com/s", params={"wd": query, "rn": str(limit)})
                    results = _parse_baidu(r.text, limit)
                if results:
                    return _mk_result(True, query=query, engine=name, count=len(results), results=results)
                last_err = f"{name}: 页面可达但未解析出结果"
            except Exception as exc:
                last_err = f"{name}: {exc}"
    return _mk_result(False, query=query, error=f"所有搜索引擎失败（{last_err}）",
                      hint="可尝试改写关键词后重试，或改用 engine=baidu")


# ============================ 网页抓取与压缩 ============================

def fetch_page(url: str, max_chars: int = 0, summarize=None) -> dict:
    url = (url or "").strip()
    guard = _ssrf_guard(url)
    if guard:
        return _mk_result(False, url=url, error=guard)
    limit = int(max_chars) if max_chars and int(max_chars) > 200 else FETCH_MAX_CHARS
    try:
        with _client() as c:
            r = c.get(url)
            r.raise_for_status()
        ctype = r.headers.get("content-type", "")
        if "html" not in ctype and "xml" not in ctype and "text" not in ctype:
            return _mk_result(False, url=url, error=f"不支持的内容类型: {ctype or '未知'}")
    except Exception as exc:
        return _mk_result(False, url=url, error=f"抓取失败: {exc}")

    raw = r.text
    title = ""
    tm = re.search(r"<title[^>]*>(.*?)</title>", raw, re.S | re.I)
    if tm:
        title = _strip_tags(tm.group(1))[:120]
    text = ""
    if trafilatura is not None:
        try:
            text = trafilatura.extract(raw, url=url, include_comments=False, favor_recall=True) or ""
        except Exception:
            text = ""
    if not text:
        text = _SimpleTextExtractor.extract(raw)
    if not text:
        return _mk_result(False, url=url, title=title, error="未能提取到正文（可能为纯脚本页）")

    compressed = False
    if len(text) > limit and summarize is not None:
        try:
            shortened = summarize(text, limit)
            if shortened:
                text, compressed = shortened, True
        except Exception:
            pass
    text = _truncate_head_tail(text, limit) if len(text) > limit else text
    return _mk_result(True, url=url, title=title, compressed=compressed,
                      chars=len(text), content=text)


# ============================ Context7（MCP over HTTP） ============================

def _mcp_post(c: httpx.Client, session: str | None, payload: dict) -> tuple[dict, str]:
    headers = {"Accept": "application/json, text/event-stream", "User-Agent": UA}
    if session:
        headers["Mcp-Session-Id"] = session
    r = c.post(CTX7_MCP_URL, json=payload, headers=headers, timeout=TOOL_TIMEOUT)
    r.raise_for_status()
    sid = r.headers.get("mcp-session-id", session or "")
    ctype = r.headers.get("content-type", "")
    body = r.text
    if "event-stream" in ctype:
        # SSE：取最后一个 data: JSON 行
        for line in reversed([ln for ln in body.splitlines() if ln.startswith("data:")]):
            try:
                return json.loads(line[5:].strip()), sid
            except Exception:
                continue
        raise ValueError("SSE 响应中无有效 JSON")
    return json.loads(body), sid


def _ctx7_call(c: httpx.Client, session: str, name: str, args: dict) -> dict:
    payload, session = _mcp_post(c, session, {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": name, "arguments": args}})
    if payload.get("error"):
        raise ValueError(str(payload["error"])[:200])
    result = payload.get("result") or {}
    texts = [c2.get("text", "") for c2 in (result.get("content") or []) if isinstance(c2, dict)]
    return {"text": "\n".join(t for t in texts if t), "isError": bool(result.get("isError"))}


def _ctx7_resolve(c: httpx.Client, session: str, library: str) -> str:
    """库名 → Context7 库 ID。

    Context7 服务端（4.4.3 实测）schema 与执行器参数名不一致：schema 声明 query、
    执行器要 libraryName——两个键都发，任一版本的服务端都能命中。
    """
    resolved = _ctx7_call(c, session, "resolve-library-id",
                          {"query": library, "libraryName": library})
    text = resolved.get("text", "")
    if resolved.get("isError"):
        raise ValueError(text[:200])
    m = re.search(r"(/[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-]+)", text)
    if not m:
        raise ValueError("未找到匹配的库")
    return m.group(1).strip().rstrip("/")


def _ctx7_session(c: httpx.Client) -> str:
    init, sid = _mcp_post(c, None, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "lanlink-gateway", "version": "1.0"}}})
    if init.get("error"):
        raise ValueError("initialize: " + str(init["error"])[:200])
    try:
        _mcp_post(c, sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    except Exception:
        pass
    return sid


def context7_docs(library: str, topic: str = "", max_chars: int = 0) -> dict:
    library = (library or "").strip()
    if not library:
        return _mk_result(False, error="library 不能为空")
    limit = int(max_chars) if max_chars and int(max_chars) > 500 else 8000
    try:
        with _client() as c:
            sid = _ctx7_session(c)
            # resolve-library-id：库名 → Context7 库 ID
            lib_id = _ctx7_resolve(c, sid, library)
            # query-docs 同样存在 schema(query)/执行器(topic) 命名分裂——双键同发
            call_args = {"libraryId": lib_id, "tokens": limit}
            if topic:
                call_args["topic"] = topic
                call_args["query"] = topic
            try:
                docs = _ctx7_call(c, sid, "query-docs", call_args)
            except Exception:
                docs = _ctx7_call(c, sid, "get-library-docs", call_args)  # 兼容旧工具名
            text = docs.get("text", "")
            if docs.get("isError"):
                return _mk_result(False, library=library, libId=lib_id, error=text[:300])
            return _mk_result(True, library=library, libId=lib_id, topic=topic or "",
                              chars=len(text), content=_truncate_head_tail(text, limit))
    except Exception as exc:
        return _mk_result(False, library=library, error=f"Context7 调用失败: {exc}")


# ============================ 增强思考 ============================

_THINK_GUIDE = {
    "cot": "继续链式思考：把剩余步骤逐个列出，每步标注依赖关系，最后给出结论。",
    "reflection": ("反思校验：逐条检查你刚才的推理——(1) 事实与数字是否有依据；(2) 逻辑是否自洽；"
                   "(3) 是否遗漏约束/边界情况。发现问题先修正再作答。"),
    "multipath": ("多路径推理：给出 2-3 条不同的解题路径，各自列出关键假设与风险，"
                  "对比后选择最优路径并说明理由。"),
}


def think(thought: str, mode: str = "cot", next_action: str = "") -> dict:
    thought = (thought or "").strip()
    if not thought:
        return _mk_result(False, error="thought 不能为空")
    if len(thought) > 400:
        thought = thought[:400] + "…(已截断)"  # 防超长 thought 撑爆生成预算
    mode = mode if mode in _THINK_GUIDE else "cot"
    return _mk_result(True, mode=mode, recordedChars=len(thought),
                      guidance=_THINK_GUIDE[mode],
                      note="推理已记录；按 guidance 继续组织后续步骤。")


# ============================ 执行入口 ============================

def execute_tool(name: str, raw_arguments, summarize=None) -> dict:
    """执行一个工具调用；任何异常都折叠为 ok=False 的 JSON（模型可读），绝不抛出。"""
    if name not in _TOOL_NAMES:
        return _mk_result(False, error=f"未知工具: {name}", available=sorted(_TOOL_NAMES))
    if isinstance(raw_arguments, str):
        try:
            args = json.loads(raw_arguments or "{}")
        except Exception as exc:
            return _mk_result(False, tool=name, error=f"arguments 不是合法 JSON: {exc}")
    elif isinstance(raw_arguments, dict):
        args = raw_arguments
    else:
        args = {}
    t0 = time.time()
    try:
        if name == "web_search":
            out = web_search(str(args.get("query") or ""), str(args.get("engine") or "auto"))
        elif name == "fetch_page":
            out = fetch_page(str(args.get("url") or ""),
                             int(args.get("max_chars") or 0), summarize=summarize)
        elif name == "context7_docs":
            out = context7_docs(str(args.get("library") or ""),
                                str(args.get("topic") or ""), int(args.get("max_chars") or 0))
        else:
            out = think(str(args.get("thought") or ""),
                        str(args.get("mode") or "cot"), str(args.get("next_action") or ""))
    except Exception as exc:  # 兜底：任何漏网异常都折叠为错误 JSON
        out = _mk_result(False, error=f"工具执行异常: {exc}")
    out["tool"] = name
    out["elapsedMs"] = int((time.time() - t0) * 1000)
    return out

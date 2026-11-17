# -*- coding: utf-8 -*-
"""MCP fetch 工具桥：stdio(mcp-server-fetch) → HTTP(streamable /mcp)。

为什么需要这层桥：官方 mcp-server-fetch 只提供 stdio 传输，而 ai-gateway 以
HTTP JSON-RPC 客户端方式访问工具后端（与 playwright MCP 官方镜像同一协议）。
本桥用 SDK 的 stdio_client 拉起 fetch server 子进程，低层 Server 把 tools/list
与 tools/call 原样代理过去，再经 streamable_http_app 暴露 /mcp 端点。

安全边界：
- SSRF 的入站拦截在网关侧（tools._ssrf_guard，先于本桥）；本桥是内网-only 服务
  （无宿主端口映射），自身不做 URL 校验。
- 官方 fetch server（>=2026.6.4）自带私网/环回拒绝与逐跳重定向复检，入站守卫
  + 出站守卫两层防线。
- --user-agent 注入 lanlink-ai-gateway 前缀（可审计）；robots.txt 默认遵守，
  LANLINK_FETCH_IGNORE_ROBOTS=1 可放开。
"""
import asyncio
import contextlib
import os

import uvicorn
from mcp import ClientSession, StdioServerParameters, stdio_client
from mcp.server.lowlevel import Server
from mcp.server.transport_security import TransportSecuritySettings
from starlette.responses import JSONResponse
from starlette.routing import Route

BRIDGE_PORT = int(os.environ.get("PORT", "8781"))
USER_AGENT = os.environ.get("LANLINK_FETCH_USER_AGENT",
                            "lanlink-ai-gateway (+internal bridge)")
IGNORE_ROBOTS = os.environ.get("LANLINK_FETCH_IGNORE_ROBOTS", "0") in ("1", "true", "yes")


async def _main() -> None:
    server_params = StdioServerParameters(
        command="mcp-server-fetch",
        args=["--user-agent", USER_AGENT,
              *(["--ignore-robots-txt"] if IGNORE_ROBOTS else [])])

    async with stdio_client(server_params) as (read_stream, write_stream):
        child = ClientSession(read_stream, write_stream)
        async with child:
            await child.initialize()

            async def on_list_tools(ctx, params):
                return await child.list_tools()

            async def on_call_tool(ctx, params):
                return await child.call_tool(params.name, params.arguments or {})

            server = Server("mcp-fetch-bridge",
                            on_list_tools=on_list_tools, on_call_tool=on_call_tool)
            # 显式声明 Host 白名单：SDK 在 transport_security=None + host=127.0.0.1
            # 时会自动开启 DNS rebinding 防护（allowed_hosts 仅 localhost 系），
            # 把网关容器经服务名直连的 Host: mcp-fetch:8781 拒成 421（实测）。
            # 本桥仅 shopping_default 内网可达、无宿主端口映射，白名单放行服务名
            # 保留 localhost 系供容器内健康自检；Origin 校验对无 Origin 的
            # JSON-RPC 客户端天然放行（transport_security.py:75-76）。
            security = TransportSecuritySettings(
                enable_dns_rebinding_protection=True,
                allowed_hosts=["mcp-fetch:*", "mcp-fetch",
                               "127.0.0.1:*", "localhost:*", "[::1]:*"])
            app = server.streamable_http_app(stateless_http=True, json_response=True,
                                             streamable_http_path="/mcp",
                                             transport_security=security)

            async def healthz(request):
                # 子进程会话由本协程持有：能响应即说明桥与 fetch server 均在运行
                return JSONResponse({"ok": True, "server": "mcp-fetch-bridge"})

            app.router.routes.append(Route("/healthz", healthz))

            config = uvicorn.Config(app, host="0.0.0.0", port=BRIDGE_PORT,
                                    log_level="warning")
            uvi = uvicorn.Server(config)
            serve_task = asyncio.create_task(uvi.serve())
            print(f"[mcp-bridge] fetch bridge listening on :{BRIDGE_PORT}/mcp", flush=True)
            await serve_task


if __name__ == "__main__":
    asyncio.run(_main())

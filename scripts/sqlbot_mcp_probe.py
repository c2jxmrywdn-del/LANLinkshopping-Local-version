"""SQLBot MCP 端点探针：连接 SSE 端点、初始化 JSON-RPC 并枚举可用工具。

用于验证 SQLBot 的 MCP SSE 服务（端口 8001）与 JWT 鉴权链路是否正常。
"""

import json
import os
import sys
import threading
import time
from pathlib import Path
import requests

from sqlbot_client import SqlbotClient, _env_value

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def probe_mcp(mcp_url: str | None = None, api_base: str = "http://127.0.0.1:8000"):
    mcp_endpoint = mcp_url or _env_value("SQLBOT_MCP_URL") or "http://127.0.0.1:8001/mcp"
    print(f"[*] 准备连接 SQLBot MCP 端点: {mcp_endpoint}")

    # 1. 获取登录 Token
    client = SqlbotClient(base_url=api_base)
    token = client.login()
    print(f"[+] 登录成功，获取 Token (长度 {len(token)})")

    headers = {
        "X-SQLBOT-TOKEN": f"Bearer {token}",
        "Accept": "text/event-stream",
    }

    # 2. 建立 SSE 连接
    session = requests.Session()
    post_endpoint = None
    tools_received = []
    stop_event = threading.Event()

    def listen_sse():
        nonlocal post_endpoint
        try:
            with session.get(mcp_endpoint, headers=headers, stream=True, timeout=30) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if stop_event.is_set():
                        break
                    if not line:
                        continue
                    text = line.decode("utf-8", errors="replace").strip()
                    if text.startswith("event:"):
                        ev_name = text[6:].strip()
                    elif text.startswith("data:"):
                        data_str = text[5:].strip()
                        if "endpoint" in text or "sessionId=" in data_str:
                            post_endpoint = data_str
                        try:
                            payload = json.loads(data_str)
                            if "result" in payload and "tools" in payload["result"]:
                                tools_received.extend(payload["result"]["tools"])
                                stop_event.set()
                        except Exception:
                            pass
        except Exception as e:
            if not stop_event.is_set():
                print(f"[!] SSE 连接异常: {e}")

    t = threading.Thread(target=listen_sse, daemon=True)
    t.start()

    # 等待 endpoint 事件
    for _ in range(50):
        if post_endpoint or stop_event.is_set():
            break
        time.sleep(0.1)

    if not post_endpoint:
        # 回退使用默认相对路径拼接
        post_endpoint = f"{mcp_endpoint.rstrip('/')}/messages"
        print(f"[*] 未收到 endpoint 事件，默认使用消息入口: {post_endpoint}")
    else:
        if post_endpoint.startswith("/"):
            from urllib.parse import urlparse
            parsed = urlparse(mcp_endpoint)
            post_endpoint = f"{parsed.scheme}://{parsed.netloc}{post_endpoint}"
        print(f"[+] 收到消息发送端点: {post_endpoint}")

    msg_headers = {
        "X-SQLBOT-TOKEN": f"Bearer {token}",
        "Content-Type": "application/json",
    }

    # 3. 发送 initialize
    init_body = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "sqlbot-mcp-probe", "version": "1.0"},
        },
    }
    r = session.post(post_endpoint, headers=msg_headers, json=init_body, timeout=10)
    print(f"[*] initialize 请求状态: {r.status_code}")

    # 4. 发送 notifications/initialized
    notif_body = {
        "jsonrpc": "2.0",
        "method": "notifications/initialized",
    }
    session.post(post_endpoint, headers=msg_headers, json=notif_body, timeout=10)

    # 5. 发送 tools/list
    list_body = {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/list",
        "params": {},
    }
    r = session.post(post_endpoint, headers=msg_headers, json=list_body, timeout=10)
    print(f"[*] tools/list 请求状态: {r.status_code}")

    # 等待工具列表返回
    for _ in range(50):
        if tools_received or stop_event.is_set():
            break
        time.sleep(0.1)

    stop_event.set()

    print(f"\n[+] 成功探测到 {len(tools_received)} 个 MCP 工具:")
    for tool in tools_received:
        name = tool.get("name", "unknown")
        desc = (tool.get("description") or "").splitlines()[0][:60]
        print(f"  - {name}: {desc}")

    return tools_received


if __name__ == "__main__":
    probe_mcp()

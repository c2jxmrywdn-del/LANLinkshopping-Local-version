# -*- coding: utf-8 -*-
"""LANLink MCP Stdio to HTTP Bridge Proxy.

桥接 Claude Desktop / Cursor (stdio 协议模式) 到 LANLink AI Gateway 的 HTTP MCP 端点 (/mcp)。
用法：
  python scripts/mcp_stdio_bridge.py
  (可通过环境变量 LANLINK_MCP_URL, LANLINK_GATEWAY_KEY 覆盖默认网关地址与鉴权 Key)
"""
import json
import os
import sys
import urllib.request
import urllib.error

# 强制标准输入/输出/错误流采用 UTF-8 编码，防止 Windows 控制台/stdio 默认 GBK/cp936 导致中文字符崩溃
if hasattr(sys.stdin, "reconfigure"):
    sys.stdin.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

MCP_URL = os.environ.get("LANLINK_MCP_URL", "http://127.0.0.1:8090/mcp")
GATEWAY_KEY = (
    os.environ.get("LANLINK_GATEWAY_KEY")
    or os.environ.get("GATEWAY_API_KEY")
    or os.environ.get("AI_GATEWAY_API_KEY")
    or "lanlink-local-ai-gateway"
).strip()

AGENT_ROLE = os.environ.get("LANLINK_AGENT_ROLE", "").strip()
if "--role" in sys.argv:
    try:
        r_idx = sys.argv.index("--role")
        if r_idx + 1 < len(sys.argv):
            AGENT_ROLE = sys.argv[r_idx + 1].strip()
    except Exception:
        pass

def main():
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {GATEWAY_KEY}",
        "User-Agent": "lanlink-mcp-stdio-bridge/1.0"
    }
    if AGENT_ROLE:
        headers["X-Agent-Role"] = AGENT_ROLE

    # 循环读取 stdin 输入的 JSON-RPC 报文
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req_data = line.encode("utf-8")
            req = urllib.request.Request(MCP_URL, data=req_data, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=30) as resp:
                # notifications 响应可能为空（如 HTTP 204）
                if resp.status == 204:
                    continue
                body = resp.read().decode("utf-8")
                if body:
                    sys.stdout.write(body.strip() + "\n")
                    sys.stdout.flush()
        except urllib.error.HTTPError as he:
            err_body = he.read().decode("utf-8", "replace")
            sys.stderr.write(f"[mcp-bridge] HTTP {he.code}: {err_body}\n")
            sys.stderr.flush()
            # 尝试回传合法的 JSON-RPC 错误
            try:
                rpc_req = json.loads(line)
                req_id = rpc_req.get("id")
            except Exception:
                req_id = None
            err_resp = {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32000, "message": f"Gateway HTTP Error {he.code}: {err_body[:200]}"}
            }
            sys.stdout.write(json.dumps(err_resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()
        except Exception as exc:
            sys.stderr.write(f"[mcp-bridge] request failed: {exc}\n")
            sys.stderr.flush()
            try:
                rpc_req = json.loads(line)
                req_id = rpc_req.get("id")
            except Exception:
                req_id = None
            err_resp = {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32000, "message": f"Bridge internal error: {str(exc)}"}
            }
            sys.stdout.write(json.dumps(err_resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()

if __name__ == "__main__":
    main()

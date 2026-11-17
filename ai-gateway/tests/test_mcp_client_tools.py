# -*- coding: utf-8 -*-
"""MCP fetch/playwright 工具桥（网关侧）测试：注册/降级/SSRF/健康摘除/清单执行一致/角色门禁。

测试策略（与 test_ai_tools_gateway.py 一致）：TestClient + mock；
MCP server 的 JSON-RPC 响应用 unittest.mock 打在 mcp_client_tools 的
httpx 调用层（_rpc_post/_tcp_alive），不依赖真实 MCP server。
"""
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

_app_dir = str(Path(__file__).resolve().parent.parent)
if _app_dir not in sys.path:
    sys.path.insert(0, _app_dir)

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
for _name in ("REDIS_URL", "REDIS_URLS", "REDIS_PASSWORD"):
    os.environ.pop(_name, None)

from fastapi.testclient import TestClient

from app.main import app, GATEWAY_API_KEY
from app import tools as TOOLBOX
from app import mcp_client_tools as MCP


AUTH = {"Authorization": f"Bearer {GATEWAY_API_KEY}"}


def _rpc_response(result=None, error=None):
    """构造一个 httpx.Response 形状的 JSON-RPC 响应（供 _rpc_post mock 返回）。"""
    body = {"jsonrpc": "2.0", "id": 0}
    if error is not None:
        body["error"] = error
    else:
        body["result"] = result if result is not None else {}
    resp = MagicMock()
    resp.status_code = 200
    resp.headers = {"content-type": "application/json"}
    resp.text = __import__("json").dumps(body)
    resp.json.return_value = body
    resp.raise_for_status.return_value = None
    return resp


def _rpc_pair(result=None, error=None):
    """_rpc_post 的返回形状：(payload, session_id)。payload 是真 dict。"""
    body = {"jsonrpc": "2.0", "id": 0}
    if error is not None:
        body["error"] = error
    else:
        body["result"] = result if result is not None else {}
    return (body, "test-session")


def _fake_tools_list(names):
    return {"tools": [{"name": n} for n in names]}


class _McpEnv:
    """统一管理 MCP 相关 env 的临时配置，测试结束还原。"""

    def __init__(self, **overrides):
        self._overrides = overrides

    def __call__(self, name, default=None):
        if name in self._overrides:
            return self._overrides[name]
        return os.environ.get(name, default)

    def __enter__(self):
        self._patcher = patch.dict(os.environ, self._overrides)
        self._patcher.start()
        return self

    def __exit__(self, *exc):
        self._patcher.stop()
        return False


def _reset_registry():
    """还原注册表到无 MCP 工具的基线（用例之间互不污染）。"""
    for gw in list(TOOLBOX.MCP_TOOL_NAMES):
        TOOLBOX.unregister_mcp_tool_name(gw)
    for gw in MCP._TOOL_ROUTING:
        TOOLBOX.LOCAL_TOOL_DEFS[:] = [
            d for d in TOOLBOX.LOCAL_TOOL_DEFS if d["function"]["name"] != gw]
        TOOLBOX.LOCAL_HANDLERS.pop(gw, None)
        TOOLBOX._TOOL_NAMES.discard(gw)
    MCP._REGISTERED = False
    MCP._fail_count_by_server.clear()


class TestMcpClientTools(unittest.TestCase):

    def setUp(self):
        _reset_registry()
        self.client = TestClient(app)

    def tearDown(self):
        _reset_registry()

    # ---------- ① 注册成功 ----------

    def test_register_success_adds_tools_and_catalog_visible(self):
        """握手+tools/list 成功后：mcp_* 进本地注册表，/v1/ai/tools?category=local 可见。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL="http://mcp-playwright:8782/mcp"):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(),        # fetch initialize
                     _rpc_pair(),        # fetch notifications/initialized
                     _rpc_pair({"tools": [{"name": "fetch"}]}),          # fetch tools/list
                     _rpc_pair(),        # playwright initialize
                     _rpc_pair(),        # playwright notifications/initialized
                     _rpc_pair({"tools": [{"name": "browser_navigate"}, {"name": "browser_snapshot"},
                                          {"name": "browser_find"}, {"name": "browser_close"},
                                          {"name": "browser_evaluate"},
                                          {"name": "browser_run_code_unsafe"}]}),  # playwright tools/list
                 ]):
                MCP.register_mcp_tools()
        names = {d["function"]["name"] for d in TOOLBOX.local_tool_defs()}
        for expected in ("mcp_fetch", "mcp_browser_navigate", "mcp_browser_snapshot",
                         "mcp_browser_find", "mcp_browser_close"):
            self.assertIn(expected, names)
        # 白名单外的 server 工具不得注册
        self.assertNotIn("mcp_browser_evaluate", names)
        self.assertNotIn("mcp_browser_run_code_unsafe", names)

        resp = self.client.get("/v1/ai/tools?category=local", headers=AUTH)
        self.assertEqual(resp.status_code, 200)
        catalog_names = {t["function"]["name"] for t in resp.json().get("data", [])}
        self.assertIn("mcp_fetch", catalog_names)
        self.assertIn("mcp_browser_navigate", catalog_names)

    def test_register_whitelists_schema_matches_b3(self):
        """mcp_fetch inputSchema 与官方镜像实测 schema 对齐（url 必填 + 4 可选参数）。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL=""):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}]})]):
                MCP.register_mcp_tools()
        schema = None
        for d in TOOLBOX.local_tool_defs():
            if d["function"]["name"] == "mcp_fetch":
                schema = d["function"]["parameters"]
        self.assertIsNotNone(schema)
        self.assertEqual(schema.get("required"), ["url"])
        self.assertIn("url", schema["properties"])
        for optional in ("max_length", "start_index", "raw", "timeout_ms"):
            self.assertIn(optional, schema["properties"])
        # 无 url 参数的 snapshot：必须有定义且不含 url
        snap = next(d for d in MCP._SERVER_TOOL_DEFS["playwright"]
                    if d["function"]["name"] == "mcp_browser_snapshot")
        self.assertNotIn("url", snap["function"]["parameters"].get("properties", {}))

    # ---------- ② 降级 ----------

    def test_degradation_silent_when_server_unreachable(self):
        """连接拒绝时 register_mcp_tools 静默返回：无 mcp_*、chat 不带 tools 正常 200。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL=""):
            with patch.object(MCP, "_tcp_alive", return_value=False):
                MCP.register_mcp_tools()
        self.assertEqual([d["function"]["name"] for d in TOOLBOX.local_tool_defs()
                          if d["function"]["name"].startswith("mcp_")], [])

        # Cache-Control: no-cache 绕开语义缓存（缓存命中路径不带 lanlink.tools，
        # 同一问题跨用例/跨进程第二次调用会命中 Tier-1 精确缓存导致断言假失败）
        resp = self.client.post("/v1/chat/completions", headers={**AUTH, "Cache-Control": "no-cache"},
                                json={"model": "qwen2.5-7b",
                                      "messages": [{"role": "user", "content": "mcp降级检查专用问题-请直接回答ok"}]})
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertEqual((body.get("lanlink") or {}).get("tools", {}).get("enabled"), False)
        self.assertTrue(body.get("choices"))

    def test_disabled_by_default_registers_nothing(self):
        """LANLINK_MCP_ENABLED=0（默认）时不发起任何连接、不注册任何工具。"""
        with patch.object(MCP, "_tcp_alive") as probe, \
             patch.object(MCP, "_rpc_post") as rpc:
            MCP.register_mcp_tools()
        probe.assert_not_called()
        rpc.assert_not_called()
        self.assertEqual([d["function"]["name"] for d in TOOLBOX.local_tool_defs()
                          if d["function"]["name"].startswith("mcp_")], [])

    # ---------- ③ SSRF ----------

    def test_ssrf_guard_blocks_private_urls_before_mcp_call(self):
        """内网/变体 URL 在 handler 入口被拦：MCP server 收不到 tools/call。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL=""):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}]})]):
                MCP.register_mcp_tools()
        handler = TOOLBOX.LOCAL_HANDLERS["mcp_fetch"]
        with patch.object(MCP, "_call_tool") as call:
            for url in ("http://127.0.0.1", "http://0x7f000001"):
                out = handler({"url": url})
                self.assertFalse(out.get("ok"), url)
                # 拦截文案随变体类型不同（不允许访问内网 / 不允许十进制十六进制 IP）
                self.assertIn("不允许", out.get("error", ""), url)
            # mysql 在测试环境解析不出（容器内才可解析），守卫按「主机无法解析」拒绝
            out = handler({"url": "http://mysql:3306"})
            self.assertFalse(out.get("ok"), "http://mysql:3306")
        call.assert_not_called()

    def test_blocked_hosts_blacklist(self):
        """LANLINK_MCP_BLOCKED_HOSTS 命中目标 host 时入口直接拒绝。"""
        handler = MCP._make_handler("fetch", "mcp_fetch")
        with patch.dict(os.environ, {"LANLINK_MCP_BLOCKED_HOSTS": "evil.example.com"}), \
             patch.object(MCP, "_call_tool") as call:
            out = handler({"url": "http://evil.example.com/page"})
            self.assertFalse(out.get("ok"))
            self.assertIn("黑名单", out.get("error", ""))
        call.assert_not_called()

    # ---------- ④ 健康摘除与恢复 ----------

    def test_health_loop_drops_and_restores_tools(self):
        """连续 2 次探测失败摘除工具；恢复后重注册。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL=""):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}]})]):
                MCP.register_mcp_tools()
            self.assertTrue(TOOLBOX.is_local_tool("mcp_fetch"))
            self.assertIn("mcp_fetch", TOOLBOX.MCP_TOOL_NAMES)

            with patch.object(MCP, "_tcp_alive", return_value=False):
                # 直接驱动重探逻辑（不经 sleep 循环）：两次失败摘除
                for _ in range(2):
                    alive = MCP._tcp_alive("http://mcp-fetch:8781/mcp")
                    names = MCP._server_tool_names("fetch")
                    if alive:
                        MCP._fail_count_by_server["fetch"] = 0
                    else:
                        fails = MCP._fail_count_by_server.get("fetch", 0) + 1
                        MCP._fail_count_by_server["fetch"] = fails
                        if fails >= 2 and names:
                            MCP._drop_server_tools("fetch")
            self.assertFalse(TOOLBOX.is_local_tool("mcp_fetch"))
            self.assertNotIn("mcp_fetch", TOOLBOX.MCP_TOOL_NAMES)
            self.assertEqual([d["function"]["name"] for d in TOOLBOX.local_tool_defs()
                              if d["function"]["name"] == "mcp_fetch"], [])

            # 恢复：探测成功 + server 重新 tools/list
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}]})]):
                MCP._register_one_server("fetch", "http://mcp-fetch:8781/mcp")
            self.assertTrue(TOOLBOX.is_local_tool("mcp_fetch"))

    # ---------- ⑤ 清单与执行一致（单一真值源） ----------

    def test_catalog_consistency_single_source_of_truth(self):
        """清单与执行共用 is_tool_allowed_for_role：support/未知角色不见 mcp_*，
        映射角色可见，目录视图与 auto 注入按设计可见（执行期兜底）。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL="http://mcp-playwright:8782/mcp",
                     LANLINK_MCP_ROLE_TOOLS="buyer:mcp_fetch|mcp_browser_navigate"):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}]}),
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "browser_navigate"}]}),  # playwright tools/list
                 ]):
                # _parse_mcp_role_tools 在 import 时已跑过，这里重新解析
                with patch.dict(os.environ, {}):
                    TOOLBOX.MCP_ROLE_TOOL_MAP.clear()
                    TOOLBOX.MCP_ROLE_TOOL_MAP.update(TOOLBOX._parse_mcp_role_tools())
                MCP.register_mcp_tools()

        # 已知角色经 per-role 过滤：buyer 只看到映射的 mcp_*
        buyer = {t["function"]["name"] for t in TOOLBOX.get_tools_by_role("buyer")}
        self.assertIn("mcp_fetch", buyer)
        self.assertIn("mcp_browser_navigate", buyer)
        self.assertNotIn("mcp_browser_snapshot", buyer)

        # support 不含任何 mcp_*（映射无该键）
        support = {t["function"]["name"] for t in TOOLBOX.get_tools_by_role("support")}
        self.assertEqual([n for n in support if n.startswith("mcp_")], [])

        # 未知角色 readonly_tool_defs 不含 mcp_*
        readonly = {t["function"]["name"] for t in TOOLBOX.readonly_tool_defs()}
        self.assertEqual([n for n in readonly if n.startswith("mcp_")], [])

        # role=None 目录视图（MCP 协议语义全量）与 category=local 目录视图含 mcp_*
        resp = self.client.get("/v1/ai/tools?category=local", headers=AUTH)
        local_names = {t["function"]["name"] for t in resp.json().get("data", [])}
        self.assertIn("mcp_fetch", local_names)
        full = {t["function"]["name"] for t in TOOLBOX.get_tools_by_role(None)}
        self.assertIn("mcp_fetch", full)

        # tools=auto 非 support 注入（TOOL_DEFS+local_tool_defs）含 mcp_*——
        # 外部模型必须看到才能自动路由，执行期由门禁兜底（finance 调用被拒）。
        auto_inject = {t["function"]["name"] for t in TOOLBOX.TOOL_DEFS + TOOLBOX.local_tool_defs()}
        self.assertIn("mcp_fetch", auto_inject)
        self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "finance"))

    # ---------- ⑥ 角色门禁 ----------

    def test_role_gate_fail_closed_without_mapping(self):
        """未配置映射：任何角色都调不到 mcp_*（含 LOCAL_TOOL_ROLES 兜底路径被 MCP 块前置拦截）。"""
        with patch.dict(os.environ, {"LANLINK_MCP_ROLE_TOOLS": ""}):
            TOOLBOX.MCP_ROLE_TOOL_MAP.clear()
            TOOLBOX.MCP_TOOL_NAMES.add("mcp_fetch")
            try:
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "buyer"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "support"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "finance"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", None))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "unknown-role"))
                self.assertTrue(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "all"))
            finally:
                TOOLBOX.MCP_TOOL_NAMES.discard("mcp_fetch")

    def test_role_gate_buyer_mapping(self):
        """配置 buyer 映射后：buyer 放行、support/None 拒绝、all 放行。"""
        with patch.dict(os.environ, {"LANLINK_MCP_ROLE_TOOLS":
                                     "buyer:mcp_fetch|mcp_browser_navigate"}):
            TOOLBOX.MCP_ROLE_TOOL_MAP.clear()
            TOOLBOX.MCP_ROLE_TOOL_MAP.update(TOOLBOX._parse_mcp_role_tools())
            TOOLBOX.MCP_TOOL_NAMES.update({"mcp_fetch", "mcp_browser_navigate"})
            try:
                self.assertTrue(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "buyer"))
                self.assertTrue(TOOLBOX.is_tool_allowed_for_role("mcp_browser_navigate", "buyer"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_browser_snapshot", "buyer"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "support"))
                self.assertFalse(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", None))
                self.assertTrue(TOOLBOX.is_tool_allowed_for_role("mcp_fetch", "all"))
            finally:
                TOOLBOX.MCP_TOOL_NAMES.discard("mcp_fetch")
                TOOLBOX.MCP_TOOL_NAMES.discard("mcp_browser_navigate")

    def test_role_map_support_entry_dropped_with_warning(self):
        """LANLINK_MCP_ROLE_TOOLS 里的 support 条目解析期丢弃并告警。"""
        with patch.dict(os.environ, {"LANLINK_MCP_ROLE_TOOLS":
                                     "buyer:mcp_fetch,support:mcp_fetch"}):
            # 告警由 tools.py 的解析函数发出（logger 名 lanlink.ai-gateway）
            with self.assertLogs("lanlink.ai-gateway", level="WARNING") as logs:
                mapping = TOOLBOX._parse_mcp_role_tools()
            self.assertIn("buyer", mapping)
            self.assertNotIn("support", mapping)
            self.assertTrue(any("support" in line for line in logs.output))

    # ---------- ⑦ 重名冲突防线 ----------

    def test_name_collision_with_reserved_tool_skipped(self):
        """server 的 tools/list 返回保留名（如 fetch_page）时跳过注册，不覆盖既有工具。"""
        with _McpEnv(LANLINK_MCP_ENABLED="1",
                     LANLINK_MCP_FETCH_URL="http://mcp-fetch:8781/mcp",
                     LANLINK_MCP_PLAYWRIGHT_URL=""):
            with patch.object(MCP, "_tcp_alive", return_value=True), \
                 patch.object(MCP, "_rpc_post", side_effect=[
                     _rpc_pair(), _rpc_pair(),
                     _rpc_pair({"tools": [{"name": "fetch"}, {"name": "fetch_page"}]})]):
                MCP.register_mcp_tools()
        # fetch_page 是内置工具，绝不能被本地注册表覆盖
        self.assertNotIn("fetch_page", TOOLBOX.LOCAL_HANDLERS)
        self.assertNotIn("fetch_page", TOOLBOX.MCP_TOOL_NAMES)
        self.assertTrue(TOOLBOX.is_local_tool("mcp_fetch"))


if __name__ == "__main__":
    unittest.main()

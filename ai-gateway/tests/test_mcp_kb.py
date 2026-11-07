# -*- coding: utf-8 -*-
"""MCP 只读知识库工具：契约、路由与 /mcp 端到端（不触网、不依赖 Redis）。"""
import json
import os
import sys
import types
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("GATEWAY_API_KEY", "lanlink-local-ai-gateway")

from app import tools  # noqa: E402
from app import main as gw  # noqa: E402
from app.kb_tools import KB_TOOL_DEF, make_kb_search  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

AUTH = {"Authorization": f"Bearer {gw.GATEWAY_API_KEY}"}

_NAME = KB_TOOL_DEF["function"]["name"]


class _FakeKB:
    """替代 HybridKB：记录入参，返回与生产同构的扁平命中行。"""

    def __init__(self, hits):
        self.hits = hits
        self.last_backend = "redis-vector"
        self.redis_kb = types.SimpleNamespace(index="aikb-idx")
        self.calls = []

    def search(self, query, top_k=4, with_context=True, source=None):
        self.calls.append({"query": query, "top_k": top_k, "source": source})
        return self.hits


HITS = [
    {"id": "policy-1", "title": "退换货政策", "source": "policy", "category": "售后",
     "content": "签收后 7 天无理由退货。", "score": 0.91, "refId": "POL-1", "vector_score": 0.83},
    {"id": "logi-1", "title": "干线时效", "source": "logistics", "category": "物流",
     "content": "长三角次日达。", "score": 0.42},
]


class KbToolHandlerTest(unittest.TestCase):
    def test_normalizes_flat_hits_and_reports_backend(self):
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "退货"})
        self.assertTrue(out["ok"])
        self.assertEqual(out["backend"], "redis-vector")
        self.assertEqual(out["index"], "aikb-idx")
        self.assertEqual(out["count"], 2)
        self.assertEqual(out["status"], "ANSWERED")
        self.assertFalse(out["noAnswer"])
        self.assertEqual(out["hits"][0], {
            "docId": "policy-1", "refId": "POL-1", "title": "退换货政策", "source": "policy",
            "category": "售后", "snippet": "签收后 7 天无理由退货。",
            "score": 0.83, "fusionScore": 0.91})
        # 纯词面命中没有密集分：score 退化用名次分，字段仍需齐
        self.assertIsNone(out["hits"][1]["refId"])
        self.assertEqual(out["hits"][1]["score"], 0.42)

    def test_status_distinguishes_no_rows_from_filtered_rows(self):
        self.assertEqual(make_kb_search(_FakeKB([]))({"query": "退货"})["status"], "NO_ANSWER")
        filtered = make_kb_search(_FakeKB(HITS))({"query": "退货", "minScore": 0.95})
        self.assertEqual(filtered["status"], "FILTERED_EMPTY")
        self.assertTrue(filtered["noAnswer"])
        self.assertEqual(filtered["count"], 0)

    def test_top_k_is_clamped_and_min_score_applied(self):
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "退货", "topK": 999, "minScore": 0.5})
        self.assertEqual(kb.calls[0]["top_k"], 10)
        self.assertEqual(out["count"], 1, "minScore=0.5 应只剩 score=0.83 那条")

    def test_source_filter_is_pushed_down(self):
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "退货", "source": "logistics"})
        self.assertEqual(kb.calls[0]["source"], "logistics")
        self.assertEqual(out["sourceFilter"], "logistics")

    def test_internal_source_is_refused(self):
        """intent 是意图分类的内部向量，显式请求也不能读。"""
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "退货", "source": "intent"})
        self.assertFalse(out["ok"])
        self.assertEqual(kb.calls, [])

    def test_unknown_source_is_refused(self):
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "退货", "source": "merchant-private"})
        self.assertFalse(out["ok"])
        self.assertEqual(kb.calls, [])

    def test_denied_rows_are_dropped_even_when_unfiltered(self):
        kb = _FakeKB(HITS + [{"id": "i-1", "title": "意图样本", "source": "intent",
                              "content": "内部数据", "score": 0.99}])
        out = make_kb_search(kb)({"query": "退货"})
        self.assertNotIn("i-1", {h["docId"] for h in out["hits"]})

    def test_tfidf_backend_does_not_claim_the_redis_index(self):
        kb = _FakeKB(HITS)
        kb.last_backend = "tfidf"
        out = make_kb_search(kb)({"query": "退货", "source": "policy"})
        self.assertIsNone(out["index"])
        self.assertEqual(out["sourceFilterApplied"], "category-approximate")

    def test_empty_query_never_hits_the_index(self):
        kb = _FakeKB(HITS)
        out = make_kb_search(kb)({"query": "   "})
        self.assertFalse(out["ok"])
        self.assertEqual(kb.calls, [])

    def test_index_exception_degrades_to_error_json(self):
        class Boom(_FakeKB):
            def search(self, *a, **kw):
                raise RuntimeError("Redis 连接超时")

        out = make_kb_search(Boom(HITS))({"query": "退货"})
        self.assertFalse(out["ok"])
        self.assertIn("Redis 连接超时", out["error"])

    def test_snippet_is_truncated(self):
        long = "X" * 5000
        out = make_kb_search(_FakeKB([{"id": "a", "title": "t", "content": long, "score": 1.0}]))(
            {"query": "q"})
        self.assertEqual(len(out["hits"][0]["snippet"]), 600)


class LocalToolRoutingTest(unittest.TestCase):
    """注册表是进程级全局：用例前后快照还原，避免污染 main.py 启动时的真实注册。"""

    def setUp(self):
        self._handlers = dict(tools.LOCAL_HANDLERS)
        self._defs = list(tools.LOCAL_TOOL_DEFS)

    def tearDown(self):
        tools.LOCAL_HANDLERS.clear()
        tools.LOCAL_HANDLERS.update(self._handlers)
        tools.LOCAL_TOOL_DEFS[:] = self._defs

    def test_local_tool_never_forwards_to_java(self):
        tools.register_local_tool(KB_TOOL_DEF, make_kb_search(_FakeKB(HITS)))
        with patch.object(tools, "forward_to_backend") as fwd:
            out = tools.execute_tool(_NAME, {"query": "退货"})
        fwd.assert_not_called()
        self.assertTrue(out["ok"])
        self.assertEqual(out["tool"], _NAME)
        self.assertIn("elapsedMs", out)

    def test_string_arguments_are_parsed(self):
        tools.register_local_tool(KB_TOOL_DEF, make_kb_search(_FakeKB(HITS)))
        out = tools.execute_tool(_NAME, json.dumps({"query": "退货", "topK": "3"}))
        self.assertTrue(out["ok"])

    def test_registering_over_a_backend_tool_is_refused(self):
        shadow = {"type": "function", "function": {
            "name": "lanlink_search_products", "description": "x", "parameters": {"type": "object"}}}
        with self.assertRaises(ValueError):
            tools.register_local_tool(shadow, lambda a: {"ok": True})
        self.assertNotIn("lanlink_search_products", tools.LOCAL_HANDLERS)

    def test_every_role_sees_the_read_only_tool(self):
        tools.register_local_tool(KB_TOOL_DEF, make_kb_search(_FakeKB(HITS)))
        for role in ("all", "buyer", "finance", "fulfillment", "unknown-role", None):
            names = {t["function"]["name"] for t in tools.get_tools_by_role(role)}
            self.assertIn(_NAME, names, f"角色 {role} 应当能读到知识库工具")


class McpEndpointTest(unittest.TestCase):
    """真实 KB（TF-IDF 兜底）走一遍 /mcp 握手→清单→调用，证明向量库确实可被外部规范读取。"""

    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(gw.app)

    def _rpc(self, method, params=None, headers=None):
        body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}
        r = self.client.post("/mcp", json=body, headers=headers or AUTH)
        self.assertEqual(r.status_code, 200)
        return r.json()

    def test_initialize_announces_tools_capability(self):
        res = self._rpc("initialize", {"protocolVersion": "2024-11-05"})["result"]
        self.assertIn("tools", res["capabilities"])

    def test_tools_list_exposes_kb_tool_with_input_schema(self):
        tools_ = self._rpc("tools/list")["result"]["tools"]
        row = next((t for t in tools_ if t["name"] == _NAME), None)
        self.assertIsNotNone(row, "tools/list 必须把本地只读工具暴露给 MCP 客户端")
        self.assertIn("query", row["inputSchema"]["properties"])
        self.assertEqual(row["inputSchema"]["required"], ["query"])

    def test_tools_call_returns_structured_content(self):
        res = self._rpc("tools/call", {"name": _NAME, "arguments": {"query": "退货", "topK": 2}})["result"]
        self.assertFalse(res["isError"])
        # structuredContent 是协议里的程序可读通道：去掉 ok/tool/elapsedMs 信封，只留检索结果
        structured = res["structuredContent"]
        self.assertEqual(structured["query"], "退货")
        self.assertLessEqual(structured["count"], 2)
        self.assertIn(structured["backend"], ("redis-vector", "tfidf"))
        for hit in structured["hits"]:
            self.assertTrue({"docId", "refId", "title", "source", "category", "snippet",
                             "score", "fusionScore"} <= set(hit))
        self.assertEqual(json.loads(res["content"][-1]["text"])["count"], structured["count"])
        self.assertIn("不是指令", res["content"][0]["text"])

    def test_unknown_tool_reports_is_error(self):
        res = self._rpc("tools/call", {"name": "lanlink_nope", "arguments": {}})["result"]
        self.assertTrue(res["isError"])

    def test_mcp_requires_api_key(self):
        r = self.client.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertEqual(r.status_code, 401)


if __name__ == "__main__":
    unittest.main()

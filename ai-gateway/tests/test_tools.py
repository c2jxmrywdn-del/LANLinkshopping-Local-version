# -*- coding: utf-8 -*-
"""工具执行器 + agent 循环单测（不触网：HTTP 全部 mock，模型走 patch）。"""
import json
import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import tools  # noqa: E402
from app import main as gw  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


BING_HTML = """
<html><body>
<li class="b_algo"><h2><a href="https://example.com/a" h="ID=SERP,1">钢材<b>价格</b>走势</a></h2>
<div><p class="b_lineclamp">2026年10月钢材价格整体上行，螺纹钢均价3850元。</p></div></li>
<li class="b_algo"><h2><a href="https://example.com/b">水泥行情</a></h2>
<div><p>水泥指数上涨 2.3%。</p></div></li>
</body></html>
"""

NOISE_HTML = """
<html><head><title>测试页 - 示例站</title></head><body>
<nav><a href="/">首页</a><a href="/cat">分类</a></nav>
<script>var tracking = 1;</script>
<div class="ad">广告位招租点击这里</div>
<p>LANLink 平台支持企业账期支付，最长 60 天信用账期，需要企业实名认证。</p>
<p>平台还提供增值税专用发票与对公转账通道。</p>
<footer>备案号 | 联系我们</footer>
</body></html>
"""


class ToolsRegistryTest(unittest.TestCase):
    def test_tool_defs_complete(self):
        names = {d["function"]["name"] for d in tools.TOOL_DEFS}
        self.assertEqual(names, {"web_search", "fetch_page", "context7_docs", "think"})
        for d in tools.TOOL_DEFS:
            self.assertEqual(d["type"], "function")
            self.assertIn("parameters", d["function"])

    def test_unknown_tool_is_safe_error(self):
        out = tools.execute_tool("nope", "{}")
        self.assertFalse(out["ok"])
        self.assertIn("未知工具", out["error"])

    def test_bad_json_args_is_safe_error(self):
        out = tools.execute_tool("think", "{not json")
        self.assertFalse(out["ok"])
        self.assertIn("JSON", out["error"])

    def test_think_modes(self):
        for mode in ("cot", "reflection", "multipath"):
            out = tools.execute_tool("think", json.dumps({"thought": "先算基准价", "mode": mode}))
            self.assertTrue(out["ok"], out)
            self.assertEqual(out["mode"], mode)
            self.assertTrue(out["guidance"])
        out = tools.execute_tool("think", json.dumps({"thought": ""}))
        self.assertFalse(out["ok"])

    def test_bing_parse(self):
        results = tools._parse_bing(BING_HTML, 8)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0]["url"], "https://example.com/a")
        self.assertIn("钢材", results[0]["title"])
        self.assertIn("3850", results[0]["snippet"])

    def test_baidu_parse(self):
        html = '<h3 class="t"><a href="https://e.com/1">螺纹钢<em>价格</em></a></h3>' \
               '<div class="c-abstract c-abstract-1">均价 3850 元上行</div>'
        results = tools._parse_baidu(html, 8)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["url"], "https://e.com/1")
        self.assertIn("价格", results[0]["title"])
        self.assertIn("3850", results[0]["snippet"])

    def test_fallback_extractor_filters_noise(self):
        text = tools._SimpleTextExtractor.extract(NOISE_HTML)
        self.assertIn("企业账期支付", text)
        self.assertIn("增值税专用发票", text)
        self.assertNotIn("tracking", text)
        self.assertNotIn("广告位", text)

    def test_ssrf_guard_blocks_internal(self):
        for url in ("http://127.0.0.1:8090/", "http://192.168.1.1/", "file:///etc/passwd",
                    "http://169.254.169.254/meta"):
            out = tools.execute_tool("fetch_page", json.dumps({"url": url}))
            self.assertFalse(out["ok"], url)

    def test_fetch_page_fallback_and_compression_flag(self):
        class FakeResp:
            text = NOISE_HTML
            headers = {"content-type": "text/html; charset=utf-8"}
            def raise_for_status(self): pass

        class FakeClient:
            def __init__(self, *a, **k): pass
            def __enter__(self): return self
            def __exit__(self, *a): return False
            def get(self, url, **k): return FakeResp()

        with patch.object(tools.httpx, "Client", FakeClient), \
             patch.object(tools, "trafilatura", None):
            out = tools.execute_tool("fetch_page", json.dumps({"url": "https://example.com/x"}))
            self.assertTrue(out["ok"], out)
            self.assertIn("企业账期", out["content"])
            self.assertFalse(out["compressed"])

            # 超长正文走注入的摘要回调
            long_html = NOISE_HTML.replace("平台还提供增值税专用发票与对公转账通道。",
                                           "长内容。" * 800)
            FakeResp.text = long_html
            out2 = tools.execute_tool("fetch_page",
                                      json.dumps({"url": "https://example.com/x", "max_chars": 300}),
                                      summarize=lambda t, n: "摘要：" + t[:50])
            self.assertTrue(out2["ok"])
            self.assertTrue(out2["compressed"])
            self.assertLessEqual(len(out2["content"]), 400)


class AgentLoopTest(unittest.TestCase):
    def setUp(self):
        os.environ["LLM_BACKEND"] = "mock"
        gw.LLM.backend_pref = "mock"
        self.client = TestClient(gw.app)

    def test_auto_route_executes_tool_and_fuses_context(self):
        """模型第一轮自主发起 web_search，结果回灌后第二轮给出最终回答（上下文融合）。"""
        calls = []

        def fake_chat_raw(messages, temperature=0.3, max_tokens=512, model=None,
                          tools=None, tool_choice=None):
            calls.append([dict(m) for m in messages])
            if not any(m.get("role") == "tool" for m in messages):
                return ({"role": "assistant", "content": "", "tool_calls": [
                    {"id": "call_1", "type": "function",
                     "function": {"name": "web_search",
                                  "arguments": json.dumps({"query": "螺纹钢价格"}, ensure_ascii=False)}}]},
                    "tool_calls", "fake", 0.01)
            tool_msgs = [m for m in messages if m.get("role") == "tool"]
            self.assertEqual(len(tool_msgs), 1)
            self.assertEqual(tool_msgs[0]["tool_call_id"], "call_1")
            self.assertIn("3850", tool_msgs[0]["content"])  # 工具结果进入上下文
            return ({"role": "assistant",
                     "content": "根据检索结果，螺纹钢均价 3850 元。"}, "stop", "fake", 0.01)

        with patch.object(gw.TOOLBOX, "execute_tool",
                          side_effect=lambda n, a, summarize=None: {"ok": True, "tool": n,
                                                                    "elapsedMs": 1, "results": [
                                                                        {"title": "钢材", "url": "u",
                                                                         "snippet": "均价3850元"}]}), \
             patch.object(gw.LLM, "chat_raw", side_effect=fake_chat_raw):
            r = self.client.post("/v1/chat/completions", json={
                "model": "qwen2.5-7b",
                "messages": [{"role": "user", "content": "现在螺纹钢什么价格"}],
                "use_kb": False, "tools": "auto"})
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertIn("3850", body["choices"][0]["message"]["content"])
        info = body["lanlink"]["tools"]
        self.assertTrue(info["enabled"])
        self.assertEqual(info["count"], 1)
        self.assertEqual(info["trace"][0]["tool"], "web_search")
        self.assertTrue(info["trace"][0]["ok"])
        self.assertEqual(len(calls), 2)  # 两轮推理

    def test_no_tool_needed_single_round(self):
        def fake_chat_raw(messages, temperature=0.3, max_tokens=512, model=None,
                          tools=None, tool_choice=None):
            return {"role": "assistant", "content": "直接回答，不需要工具。"}, "stop", "fake", 0.01

        with patch.object(gw.LLM, "chat_raw", side_effect=fake_chat_raw):
            r = self.client.post("/v1/chat/completions", json={
                "model": "qwen2.5-7b", "use_kb": False, "tools": "auto",
                "messages": [{"role": "user", "content": "企业账期怎么申请"}]})
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertIn("直接回答", body["choices"][0]["message"]["content"])
        self.assertEqual(body["lanlink"]["tools"]["count"], 0)
        self.assertEqual(body["choices"][0]["finish_reason"], "stop")

    def test_thinking_forced_off_with_tools(self):
        """tools 与两阶段 thinking 互斥（_reasoning_messages 会破坏 tool 消息序列）。"""
        seen = {}

        def fake_chat_raw(messages, temperature=0.3, max_tokens=512, model=None,
                          tools=None, tool_choice=None):
            seen["n"] = seen.get("n", 0) + 1
            return {"role": "assistant", "content": "ok"}, "stop", "fake", 0.01

        with patch.object(gw.LLM, "chat_raw", side_effect=fake_chat_raw), \
             patch.object(gw.LLM, "chat", side_effect=AssertionError("thinking 不应触发")):
            r = self.client.post("/v1/chat/completions", json={
                "model": "qwen2.5-7b", "use_kb": False, "tools": "auto", "thinking": True,
                "messages": [{"role": "user", "content": "复杂规划问题"}]})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertFalse(r.json()["lanlink"]["thinking"])

    def test_named_tool_choice_object_downgrades_to_required(self):
        captured = {}

        def fake_chat_raw(messages, temperature=0.3, max_tokens=512, model=None,
                          tools=None, tool_choice=None):
            captured["choice"] = tool_choice
            return {"role": "assistant", "content": "答案"}, "stop", "fake", 0.01

        with patch.object(gw.LLM, "chat_raw", side_effect=fake_chat_raw):
            r = self.client.post("/v1/chat/completions", json={
                "model": "qwen2.5-7b", "use_kb": False,
                "tools": [{"type": "function", "function": {"name": "web_search",
                                                            "parameters": {}}}],
                "tool_choice": {"type": "function", "function": {"name": "web_search"}},
                "messages": [{"role": "user", "content": "搜一下"}]})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(captured["choice"], "required")
        self.assertEqual(r.json()["lanlink"]["tools"]["choice"], "required")

    def test_invalid_tools_string_rejected(self):
        r = self.client.post("/v1/chat/completions", json={
            "model": "qwen2.5-7b", "tools": "whatever",
            "messages": [{"role": "user", "content": "hi"}]})
        self.assertEqual(r.status_code, 400)

    def test_tool_loop_exhaustion_forces_final_answer(self):
        """迭代上限耗尽时强制收尾（不再给工具），保证必有最终文本。"""
        def fake_chat_raw(messages, temperature=0.3, max_tokens=512, model=None,
                          tools=None, tool_choice=None):
            if tools:  # 只要给了工具就一直要调用 → 触发迭代耗尽
                return ({"role": "assistant", "content": "", "tool_calls": [
                    {"id": "c", "type": "function",
                     "function": {"name": "web_search", "arguments": "{}"}}]},
                    "tool_calls", "fake", 0.01)
            return {"role": "assistant", "content": "收尾回答"}, "stop", "fake", 0.01

        old = gw._AGENT_MAX_ITERS
        gw._AGENT_MAX_ITERS = 2
        try:
            with patch.object(gw.LLM, "chat_raw", side_effect=fake_chat_raw), \
                 patch.object(gw.TOOLBOX, "execute_tool",
                              side_effect=lambda n, a, summarize=None: {"ok": True, "tool": n,
                                                                        "elapsedMs": 1}):
                r = self.client.post("/v1/chat/completions", json={
                    "model": "qwen2.5-7b", "use_kb": False, "tools": "auto",
                    "messages": [{"role": "user", "content": "一直要搜索的问题"}]})
            self.assertEqual(r.status_code, 200, r.text)
            body = r.json()
            self.assertIn("收尾回答", body["choices"][0]["message"]["content"])
            self.assertEqual(body["lanlink"]["tools"]["count"], 2)  # 两轮工具后强制收尾
        finally:
            gw._AGENT_MAX_ITERS = old


if __name__ == "__main__":
    unittest.main()

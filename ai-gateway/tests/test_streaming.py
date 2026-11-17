# -*- coding: utf-8 -*-
"""真流式（llama.cpp SSE）单测：分片语义 / 首片早于末片 / [DONE] / tail 元数据 / 锁释放。

单测强制离线：`_post_stream` 的 HTTP 层用假 SSE 响应替换，不依赖真实 GPU。
真实链路（llama.cpp 逐 token、首片延迟）由容器接口实测覆盖，见
`docs/architecture-audit/_parts/gateway-real-streaming.md`。
"""
import json
import os
import sys
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
os.environ["GATEWAY_API_KEY"] = "lanlink-local-ai-gateway"
os.environ["GATEWAY_RATE_LIMIT_PER_MIN"] = "100000"
os.environ["GATEWAY_RATE_LIMIT_BURST"] = "100000"
for _name in ("REDIS_URL", "REDIS_URLS", "REDIS_PASSWORD"):
    os.environ.pop(_name, None)

from app import llm as llm_mod  # noqa: E402
from app import main as gw  # noqa: E402
from app.llm import LLMClient, LlamaCppBackend  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

AUTH = {"Authorization": f"Bearer {gw.GATEWAY_API_KEY}", "Cache-Control": "no-cache"}


def _sse_line(obj) -> bytes:
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")


class _FakeSSEResponse:
    """最小化的 `urlopen` 响应：按行迭代，模拟 llama.cpp 的增量 SSE。"""

    status = 200

    def __init__(self, pieces, finish_reason="stop", delay=0.0):
        self._pieces = list(pieces)
        self._finish = finish_reason
        self._delay = delay
        self.closed = False

    def __iter__(self):
        yield _sse_line({"choices": [{"index": 0, "delta": {"role": "assistant"},
                                      "finish_reason": None}]})
        for piece in self._pieces:
            if self._delay:
                time.sleep(self._delay)
            yield _sse_line({"choices": [{"index": 0, "delta": {"content": piece},
                                          "finish_reason": None}]})
        yield _sse_line({"choices": [{"index": 0, "delta": {},
                                      "finish_reason": self._finish}]})
        yield b"data: [DONE]\n"

    def close(self):
        self.closed = True


class _ExplodingSSEResponse(_FakeSSEResponse):
    """建连成功、发完首个 delta 后断流：验证中途失败不会被静默吞掉。"""

    def __iter__(self):
        yield _sse_line({"choices": [{"index": 0, "delta": {"content": "第一段"},
                                      "finish_reason": None}]})
        raise OSError("connection reset by peer")


class LlamaCppPostStreamTest(unittest.TestCase):
    """`LlamaCppBackend._post_stream`：SSE 解析 / 熔断器 / finish_reason 回传。"""

    def setUp(self):
        self.backend = LlamaCppBackend("http://llama-cpp:8123", "qwen2.5-7b")

    def test_yields_each_delta_and_records_finish_reason(self):
        resp = _FakeSSEResponse(["第一", "第二", "第三"], finish_reason="length")
        sink = {}
        with patch.object(llm_mod.urllib.request, "urlopen", return_value=resp):
            pieces = list(self.backend._post_stream(
                [{"role": "user", "content": "hi"}], 0.3, 64, sink=sink))
        self.assertEqual(pieces, ["第一", "第二", "第三"])
        self.assertEqual(sink["finish_reason"], "length")
        self.assertTrue(resp.closed, "流结束后必须关闭底层连接")

    def test_payload_requests_stream_true(self):
        captured = {}

        def fake_urlopen(req, timeout=None):
            captured["payload"] = json.loads(req.data.decode("utf-8"))
            return _FakeSSEResponse(["x"])

        with patch.object(llm_mod.urllib.request, "urlopen", side_effect=fake_urlopen):
            list(self.backend._post_stream([{"role": "user", "content": "hi"}], 0.3, 64))
        self.assertIs(captured["payload"]["stream"], True)
        self.assertEqual(captured["payload"]["model"], "qwen2.5-7b")

    def test_circuit_breaker_open_fails_fast(self):
        with patch.object(self.backend.circuit_breaker, "allow_request", return_value=False):
            with self.assertRaises(Exception):
                list(self.backend._post_stream([{"role": "user", "content": "hi"}], 0.3, 64))

    def test_midstream_failure_records_failure_and_raises(self):
        resp = _ExplodingSSEResponse(["第一段"])
        before = self.backend.circuit_breaker.failure_count
        with patch.object(llm_mod.urllib.request, "urlopen", return_value=resp):
            with self.assertRaises(OSError):
                list(self.backend._post_stream([{"role": "user", "content": "hi"}], 0.3, 64))
        self.assertEqual(self.backend.circuit_breaker.failure_count, before + 1)


class ChatStreamContractTest(unittest.TestCase):
    """`LLMClient.chat_stream`：门锁释放 / 降级为 buffered / 异常契约。"""

    def test_buffered_mock_stream_splits_sentences(self):
        client = LLMClient()
        client.backend_pref = "mock"
        stream = client.chat_stream([{"role": "user", "content": "螺纹钢多少钱一吨"}],
                                    model="qwen2.5-7b")
        self.assertEqual(stream.mode, "buffered")
        pieces = [piece for piece, _ in stream]
        self.assertGreater(len(pieces), 1, "整段降级也要切句，不能只发一片")
        self.assertTrue(stream.backend.startswith("mock"))

    def test_gate_released_when_llama_path_fails(self):
        """llama.cpp 无首片时必须在函数内放锁，否则后续请求全部排队到超时。"""
        client = LLMClient()
        client.backend_pref = "llamacpp"
        client.require_gpu = False
        with patch.object(client, "_llama_models", return_value=[{"id": "qwen2.5-7b"}]), \
             patch.object(client.manager, "ensure", return_value={"ok": True}), \
             patch.object(client.llama, "_post_stream",
                          side_effect=OSError("connection refused")), \
             patch.object(client.ollama, "available", return_value=False):
            stream = client.chat_stream([{"role": "user", "content": "hi"}], model="qwen2.5-7b")
        self.assertEqual(stream.mode, "buffered")
        self.assertFalse(client.gate.status()["busy"], "降级路径必须释放全局模型锁")

    def test_gate_released_after_incremental_stream_closed(self):
        client = LLMClient()
        client.backend_pref = "llamacpp"
        # 单测环境 LLAMA_CPP_URL 为空：不补 base_url 的话 `urllib.request.Request`
        # 会以 "unknown url type" 在**建请求阶段**就失败，走不到被 patch 的 urlopen。
        client.llama.base_url = "http://llama-cpp:8123"
        resp = _FakeSSEResponse(["a", "b", "c"])
        with patch.object(client, "_llama_models", return_value=[{"id": "qwen2.5-7b"}]), \
             patch.object(client.manager, "ensure", return_value={"ok": True}), \
             patch.object(llm_mod.urllib.request, "urlopen", return_value=resp):
            stream = client.chat_stream([{"role": "user", "content": "hi"}], model="qwen2.5-7b")
            self.assertEqual(stream.mode, "incremental")
            self.assertTrue(client.gate.status()["busy"], "流进行中必须持锁")
            pieces = [piece for piece, _ in stream]
        self.assertEqual(pieces, ["a", "b", "c"])
        self.assertFalse(client.gate.status()["busy"], "流结束必须释放全局模型锁")

    def test_require_gpu_raises_when_llama_unavailable(self):
        client = LLMClient()
        client.backend_pref = "llamacpp"
        client.require_gpu = True
        with patch.object(client, "_llama_models", return_value=[]):
            with self.assertRaises(RuntimeError):
                client.chat_stream([{"role": "user", "content": "hi"}], model="qwen2.5-7b")


class ChatStreamEndpointTest(unittest.TestCase):
    """`/v1/chat/completions` 的流式分支：分片数 / 首片 / [DONE] / tail 元数据。"""

    def setUp(self):
        self.client = TestClient(gw.app)
        # `LLM.last_route` 是进程级共享可变字段，且 `_agent_loop` 会把它里面的
        # `fallback_trace` 并入工具轨迹（main.py:474-477）。流式请求在 mock 降级时
        # 会写入带工具轨迹的 last_route，若不还原，后续 tools 用例会多算一次工具调用。
        self._route_backup = dict(getattr(gw.LLM, "last_route", None) or {})
        self._pref_backup = gw.LLM.backend_pref

    def tearDown(self):
        gw.LLM.last_route = dict(self._route_backup)
        gw.LLM.backend_pref = self._pref_backup

    def _collect(self, payload):
        with patch.object(gw.CACHE, "get", return_value=None):
            r = self.client.post("/v1/chat/completions", json=payload, headers=AUTH)
        self.assertEqual(r.status_code, 200, r.text)
        events = [ln for ln in r.text.splitlines() if ln.startswith("data:")]
        return r, events

    def test_stream_has_multiple_chunks_done_and_tail_metadata(self):
        r, events = self._collect({
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": "螺纹钢多少钱一吨"}],
            "stream": True,
            "use_kb": True,
        })
        self.assertEqual(events[-1], "data: [DONE]")
        head = json.loads(events[0][len("data: "):])
        self.assertEqual(head["object"], "chat.completion.chunk")
        self.assertEqual(head["choices"][0]["delta"], {"role": "assistant"})

        body_chunks = [json.loads(e[len("data: "):]) for e in events[1:-2]]
        self.assertGreater(len(body_chunks), 1, "内容分片必须多于 1 个")
        text = "".join(c["choices"][0]["delta"].get("content") or "" for c in body_chunks)
        self.assertTrue(text.strip(), "流式内容不能为空")

        tail = json.loads(events[-2][len("data: "):])
        self.assertIn("usage", tail)
        self.assertGreater(tail["usage"]["completion_tokens"], 0)
        lanlink = tail["lanlink"]
        for key in ("backend", "answer_generator", "stream", "agent_role"):
            self.assertIn(key, lanlink)
        self.assertEqual(lanlink["stream"]["chunks"], len(body_chunks))
        self.assertEqual(lanlink["answer_generator"], f"local:{lanlink['backend']}")
        self.assertEqual(r.headers.get("X-Lanlink-Stream-Mode"), "buffered")

    def test_first_chunk_arrives_before_last_chunk(self):
        """增量流：首片时间必须早于末片时间（用假 SSE 的延迟拉开间隔）。

        注意：TestClient 会把整个响应缓冲后再返回，因此不能用它的墙钟时间判定；
        这里直接迭代流对象，测量「拿到首片」与「拿到末片」的相对顺序。
        """
        resp = _FakeSSEResponse(["一", "二", "三", "四"], delay=0.02)
        client = LLMClient()
        client.backend_pref = "llamacpp"
        with patch.object(client, "_llama_models", return_value=[{"id": "qwen2.5-7b"}]), \
             patch.object(client.manager, "ensure", return_value={"ok": True}), \
             patch.object(llm_mod.urllib.request, "urlopen", return_value=resp):
            stream = client.chat_stream([{"role": "user", "content": "hi"}], model="qwen2.5-7b")
            t0 = time.time()
            it = iter(stream)
            next(it)
            first_at = time.time() - t0
            for _ in it:
                pass
            last_at = time.time() - t0
        self.assertLess(first_at, last_at, "首片必须早于末片到达")

    def test_tools_mode_stays_buffered_and_does_not_crash(self):
        with patch.object(gw.CACHE, "get", return_value=None), \
             patch.object(gw.TOOLBOX, "get_tools_by_role", return_value=[]), \
             patch.object(gw, "_agent_loop", return_value=(
                 "工具模式回答。第二句。", "stop", [], "llamacpp:qwen2.5-7b", 0.1)):
            r = self.client.post("/v1/chat/completions", headers=AUTH, json={
                "model": "qwen2.5-7b",
                "messages": [{"role": "user", "content": "查一下订单"}],
                "stream": True,
                "tools": "auto",
            })
        self.assertEqual(r.status_code, 200, r.text)
        self.assertIn("data: [DONE]", r.text)
        self.assertNotIn("X-Lanlink-Stream-Mode", r.headers,
                         "tools 模式应保持整段生成后切句，不进入增量流分支")


class StreamPolisherTest(unittest.TestCase):
    """`_StreamPolisher`：跨分片去重 / 提问回声清理 / 来源补齐。"""

    def test_dedupes_repeated_lines_across_chunks(self):
        p = gw._StreamPolisher([])
        out = p.feed("重复句\n重复") + p.feed("句\n新内容\n") + p.flush()
        self.assertEqual(out.count("重复句"), 1)
        self.assertIn("新内容", out)

    def test_drops_question_echo(self):
        p = gw._StreamPolisher([])
        out = p.feed("用户问题：螺纹钢多少钱\n实际回答在这里\n") + p.flush()
        self.assertNotIn("用户问题", out)
        self.assertIn("实际回答在这里", out)

    def test_appends_source_when_docs_present(self):
        docs = [{"title": "售后政策"}, {"title": "运费规则"}]
        p = gw._StreamPolisher(docs)
        text = p.feed("按平台规则处理。\n") + p.flush()
        self.assertEqual(p.source_suffix(text), "\n来源：《售后政策》、《运费规则》")

    def test_no_source_suffix_when_already_cited(self):
        docs = [{"title": "售后政策"}]
        p = gw._StreamPolisher(docs)
        text = "按平台规则处理。\n来源：《售后政策》"
        self.assertEqual(p.source_suffix(text), "")


if __name__ == "__main__":
    unittest.main()

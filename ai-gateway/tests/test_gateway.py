# -*- coding: utf-8 -*-
"""AI 网关全链路测试：知识库检索 / 场景识别 / Prompt 组装 / OpenAI 兼容接口。

运行：python -m unittest discover -s tests -v
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.prompts import build_messages, detect_scene
from app.retriever import KnowledgeBase, default_kb_path, tokenize


class TestRetriever(unittest.TestCase):
    """向量检索：命中准确性 / 分数排序 / 上下文关联。"""

    @classmethod
    def setUpClass(cls):
        cls.kb = KnowledgeBase(default_kb_path())

    def test_kb_loaded(self):
        stats = self.kb.stats()
        self.assertGreaterEqual(stats["document_count"], 14)
        self.assertIn("商品", stats["categories"])
        self.assertGreater(stats["vocab_size"], 100)

    def test_tokenize_chinese(self):
        toks = tokenize("螺纹钢多少钱一吨")
        self.assertTrue(any("螺纹" in t or "钢" in t for t in toks))

    def test_product_query_hits_product_doc(self):
        hits = self.kb.search("螺纹钢价格多少钱", top_k=3, with_context=False)
        self.assertTrue(hits)
        self.assertIn("product-construction", [h["id"] for h in hits])

    def test_aftersales_query_hits_policy(self):
        hits = self.kb.search("退货退款政策怎么规定", top_k=3, with_context=False)
        self.assertIn("after-sales-policy", [h["id"] for h in hits])

    def test_logistics_query_hits_logistics(self):
        hits = self.kb.search("发货用什么快递，运费怎么算", top_k=3, with_context=False)
        self.assertIn("logistics-policy", [h["id"] for h in hits])

    def test_scores_descending(self):
        hits = self.kb.search("积分抵扣规则", top_k=4, with_context=False)
        scores = [h["score"] for h in hits]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_context_expansion(self):
        hits = self.kb.search("螺纹钢价格", top_k=2, with_context=True)
        self.assertTrue(any(h.get("related") for h in hits) or len(hits) > 2)


class TestPrompts(unittest.TestCase):
    """场景路由与 Prompt 组装。"""

    def test_scene_product(self):
        self.assertEqual(detect_scene("螺纹钢多少钱一吨"), "product")

    def test_scene_order(self):
        self.assertEqual(detect_scene("我的订单什么状态了"), "order")

    def test_scene_logistics(self):
        self.assertEqual(detect_scene("什么时候发货，运费多少"), "logistics")

    def test_scene_aftersales(self):
        self.assertEqual(detect_scene("我要退货退款"), "aftersales")

    def test_scene_general(self):
        self.assertEqual(detect_scene("你好"), "general")

    def test_build_messages_shape(self):
        kb = KnowledgeBase(default_kb_path())
        docs = kb.search("螺纹钢价格", top_k=2)
        msgs, scene = build_messages("螺纹钢多少钱", docs)
        self.assertEqual(len(msgs), 2)
        self.assertEqual(msgs[0]["role"], "system")
        self.assertEqual(msgs[1]["role"], "user")
        self.assertIn("知识库内容", msgs[0]["content"])
        self.assertIn("螺纹钢多少钱", msgs[1]["content"])
        self.assertEqual(scene, "product")


class TestOpenAICompat(unittest.TestCase):
    """OpenAI 接口契约：请求校验 / 响应格式 / 错误格式。"""

    @classmethod
    def setUpClass(cls):
        from fastapi.testclient import TestClient
        from app.main import app
        cls.client = TestClient(app)

    def test_list_models(self):
        r = self.client.get("/v1/models")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual(body["object"], "list")
        self.assertTrue(body["data"])
        self.assertIn("id", body["data"][0])

    def test_chat_completions_shape(self):
        r = self.client.post("/v1/chat/completions", json={
            "model": "qwen2.5:0.5b",
            "messages": [{"role": "user", "content": "螺纹钢多少钱一吨"}],
        })
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertTrue(body["id"].startswith("chatcmpl-"))
        self.assertEqual(body["object"], "chat.completion")
        self.assertIn("created", body)
        self.assertEqual(body["choices"][0]["finish_reason"], "stop")
        self.assertEqual(body["choices"][0]["message"]["role"], "assistant")
        self.assertTrue(body["choices"][0]["message"]["content"])
        for k in ("prompt_tokens", "completion_tokens", "total_tokens"):
            self.assertIn(k, body["usage"])

    def test_chat_invalid_role_rejected(self):
        r = self.client.post("/v1/chat/completions", json={
            "model": "qwen2.5:0.5b",
            "messages": [{"role": "hacker", "content": "hi"}],
        })
        self.assertEqual(r.status_code, 422)

    def test_chat_empty_user_rejected(self):
        r = self.client.post("/v1/chat/completions", json={
            "model": "qwen2.5:0.5b",
            "messages": [{"role": "system", "content": "hi"}],
        })
        self.assertEqual(r.status_code, 400)
        self.assertEqual(r.json()["error"]["type"], "invalid_request_error")

    def test_chat_answer_matches_kb(self):
        r = self.client.post("/v1/chat/completions", json={
            "model": "qwen2.5:0.5b",
            "messages": [{"role": "user", "content": "螺纹钢多少钱一吨"}],
        })
        body = r.json()
        text = body["choices"][0]["message"]["content"]
        self.assertIn("3850", text)
        self.assertTrue(any(h["id"] == "product-construction" for h in body["lanlink"]["kb_hits"]))

    def test_chat_stream(self):
        with self.client.stream("POST", "/v1/chat/completions", json={
            "model": "qwen2.5:0.5b",
            "messages": [{"role": "user", "content": "运费怎么算"}],
            "stream": True,
        }) as r:
            self.assertEqual(r.status_code, 200)
            lines = [ln for ln in r.iter_lines() if ln.startswith("data:")]
        self.assertTrue(lines[-1].endswith("[DONE]"))
        head = json.loads(lines[0][len("data: "):])
        self.assertEqual(head["object"], "chat.completion.chunk")

    def test_embeddings(self):
        r = self.client.post("/v1/embeddings", json={
            "model": "lanlink-ecommerce-rag",
            "input": ["螺纹钢", "运费"],
        })
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual(len(body["data"]), 2)
        self.assertTrue(body["data"][0]["embedding"])

    def test_kb_search(self):
        r = self.client.get("/kb/search", params={"q": "七天无理由退货", "top_k": 2})
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual(body["scene"], "aftersales")
        self.assertTrue(body["results"])

    def test_health(self):
        r = self.client.get("/health")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual(body["status"], "UP")
        self.assertIn("llm", body["components"])
        self.assertIn("knowledge_base", body["components"])


if __name__ == "__main__":
    unittest.main()

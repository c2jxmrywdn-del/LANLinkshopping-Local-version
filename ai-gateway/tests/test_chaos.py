# -*- coding: utf-8 -*-
"""混沌容灾演练与启发式降级单元测试（Phase 4 - Step 2）。

覆盖场景：
1. 启发式降级引擎意图识别与槽位抽取（询价、搜索、物流、政策问答）；
2. 大模型故障快速失败与熔断器（CircuitBreaker）状态机（CLOSED -> OPEN -> HALF_OPEN -> CLOSED）；
3. Redis 宕机双轨容灾（DistributedRateLimiter 平滑回退本地内存令牌桶）；
4. AI 网关 /v1/chat/completions 降级响应头（X-Lanlink-Fallback）与 Prometheus 埋点。
"""
import os
import unittest
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from app.fallback_engine import FALLBACK_ENGINE, HeuristicFallbackEngine
from app.rate_limiter import CircuitBreaker, DistributedRateLimiter, InMemoryTokenBucket
from app.metrics import REGISTRY
from app.main import app


class TestHeuristicFallbackEngine(unittest.TestCase):
    """启发式规则降级引擎单元测试"""

    def setUp(self):
        self.engine = FALLBACK_ENGINE

    def test_quotation_intent_extraction(self):
        """测试不同自然语言句式下的商品 ID 与数量抽取"""
        cases = [
            ("计算商品 1 买 500 件的价格", 1, 500),
            ("商品 2 询价 100 个", 2, 100),
            ("skuId: 3, 数量 200", 3, 200),
            ("帮我核算产品编号 5，采购量 50 批", 5, 50),
        ]
        for query, expected_pid, expected_qty in cases:
            res = self.engine.extract_quotation_intent(query)
            self.assertIsNotNone(res, f"未能识别询价意图: {query}")
            self.assertEqual(res["productId"], expected_pid)
            self.assertEqual(res["quantity"], expected_qty)

    def test_search_intent_extraction(self):
        """测试商品搜索意图与品类关键词抽取"""
        res = self.engine.extract_search_intent("推荐几款建筑钢材")
        self.assertIsNotNone(res)
        self.assertIn("钢材", res["keyword"])

        res2 = self.engine.extract_search_intent("我想采购5款管材")
        self.assertIsNotNone(res2)
        self.assertEqual(res2["pageSize"], 5)
        self.assertIn("管材", res2["keyword"])

    def test_logistics_intent_extraction(self):
        """测试物流订单号与运单号抽取"""
        res = self.engine.extract_logistics_intent("查询订单 20261022170813519896 的物流")
        self.assertIsNotNone(res)
        self.assertEqual(res["orderNo"], "20261022170813519896")

        res2 = self.engine.extract_logistics_intent("运单单号 LL202610230001 目前到哪了")
        self.assertIsNotNone(res2)
        self.assertEqual(res2["orderNo"], "LL202610230001")

    def test_policy_faq_retrieval(self):
        """测试离线知识库制度检索（无需向量服务）"""
        ans = self.engine.handle_policy("商城的售后质保政策是什么？")
        self.assertIsNotNone(ans)
        self.assertIn("售后政策", ans)
        self.assertIn("【离线应急服务】", ans)

    def test_general_guide_fallback(self):
        """测试模糊意图兜底为操作指引"""
        res = self.engine.handle("你好，今天天气怎么样")
        self.assertEqual(res["intent"], "general_guide")
        self.assertEqual(res["fallback"], "heuristic")
        self.assertIn("工业品选型与推荐", res["text"])


class TestLlamaCircuitBreaker(unittest.TestCase):
    """大模型熔断器状态机单元测试"""

    def test_circuit_breaker_transition(self):
        """验证 CLOSED -> 连续失败 -> OPEN -> 超时 -> HALF_OPEN -> 成功恢复 CLOSED"""
        cb = CircuitBreaker(failure_threshold=3, recovery_timeout=0.2)
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        self.assertTrue(cb.allow_request())

        # 连续失败 2 次，未达阈值
        cb.record_failure()
        cb.record_failure()
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        self.assertTrue(cb.allow_request())

        # 第 3 次失败，触发熔断 OPEN
        cb.record_failure()
        self.assertEqual(cb.state, CircuitBreaker.OPEN)
        self.assertFalse(cb.allow_request(), "OPEN 状态下应拒绝试探")

        # 模拟等待恢复超时 (0.2s)
        import time
        time.sleep(0.25)
        self.assertTrue(cb.allow_request(), "超时后应进入 HALF_OPEN 允许试探请求")
        self.assertEqual(cb.state, CircuitBreaker.HALF_OPEN)

        # 试探成功，恢复为 CLOSED
        cb.record_success()
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        self.assertEqual(cb.failure_count, 0)


class TestRedisDownDoubleTrack(unittest.TestCase):
    """Redis 宕机双轨限流单元测试"""

    def test_redis_connection_error_graceful_fallback(self):
        """当绑定的 Redis 抛出网络异常时，限流器无缝回退本地内存桶"""
        limiter = DistributedRateLimiter(rate_per_min=60, burst=5)

        # 模拟故障的 Redis client
        mock_redis = MagicMock()
        mock_redis.evalsha.side_effect = Exception("Connection refused to Redis:6379")
        limiter._redis = mock_redis
        limiter._lua_sha = "dummy_sha"

        # 第一次调用，Redis 抛异常，但 acquire 依然返回 True（平滑回退本地内存）
        allowed, remaining, retry_after = limiter.acquire("test_client_1", cost=1)
        self.assertTrue(allowed, "Redis 故障时应无缝回退本地内存限流，保障服务放行")
        self.assertGreater(remaining, 0)

        # 本地连续消耗至上限
        for _ in range(10):
            limiter.acquire("test_client_1", cost=10)

        # 超限时本地内存正确拦截
        allowed_blocked, _, retry_after = limiter.acquire("test_client_1", cost=100)
        self.assertFalse(allowed_blocked)
        self.assertGreaterEqual(retry_after, 1)


class TestGatewayFallbackApi(unittest.TestCase):
    """AI 网关 /v1/chat/completions 降级响应与指标埋点测试"""

    @classmethod
    def setUpClass(cls):
        from app.main import GATEWAY_API_KEY, LLM
        cls.client = TestClient(app)
        cls.auth_header = {"Authorization": f"Bearer {GATEWAY_API_KEY}"}
        cls.orig_backend = LLM.backend_pref
        LLM.backend_pref = "mock"

    @classmethod
    def tearDownClass(cls):
        from app.main import LLM
        LLM.backend_pref = cls.orig_backend

    @patch("app.tools.forward_to_backend")
    def test_chat_completions_fallback_response(self, mock_forward):
        """测试在 mock/降级模式下，网关返回 X-Lanlink-Fallback 响应头与启发式文本"""
        mock_forward.return_value = {
            "ok": True,
            "data": {
                "productId": 1,
                "title": "太钢304不锈钢无缝管",
                "quantity": 500,
                "originalUnitPrice": 52.0,
                "effectiveUnitPrice": 46.5,
                "appliedTierDesc": "大宗批发满300件",
                "goodsTotalAmount": 23250.0,
                "estimatedFreight": 0.0,
                "estimatedVatTax": 2674.78,
                "finalTotalAmount": 23250.0,
                "totalSavings": 2750.0,
                "quotationSummary": "阶梯优惠8.9折，满额包邮"
            }
        }
        payload = {
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": "计算商品 1 买 500 件的价格"}],
            "stream": False
        }
        with patch.dict(os.environ, {"LLM_BACKEND": "mock"}):
            resp = self.client.post("/v1/chat/completions", json=payload, headers=self.auth_header)
            self.assertEqual(resp.status_code, 200)

            # 验证响应头
            self.assertEqual(resp.headers.get("X-Lanlink-Fallback"), "heuristic")
            self.assertIsNotNone(resp.headers.get("X-Lanlink-Fallback-Reason"))

            data = resp.json()
            lanlink_meta = data.get("lanlink", {})
            self.assertEqual(lanlink_meta.get("fallback"), "heuristic")
            self.assertEqual(lanlink_meta.get("fallback_intent"), "quotation")

            content = data["choices"][0]["message"]["content"]
            self.assertIn("【核价结果】", content)

    def test_stream_chat_completions_fallback(self):
        """测试流式 SSE 模式下的降级响应输出"""
        payload = {
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": "商城的售后质保政策是什么？"}],
            "stream": True
        }
        with patch.dict(os.environ, {"LLM_BACKEND": "mock"}):
            resp = self.client.post("/v1/chat/completions", json=payload, headers=self.auth_header)
            self.assertEqual(resp.status_code, 200)
            self.assertEqual(resp.headers.get("X-Lanlink-Fallback"), "heuristic")

            body = resp.text
            self.assertIn("data: [DONE]", body)
            self.assertIn("售后政策", body)

    def test_prometheus_fallback_metric_exported(self):
        """验证 Prometheus /metrics 端点输出 ai_gateway_fallback_total 指标"""
        metrics_resp = self.client.get("/metrics")
        self.assertEqual(metrics_resp.status_code, 200)
        self.assertIn("ai_gateway_fallback_total", metrics_resp.text)


if __name__ == "__main__":
    unittest.main()

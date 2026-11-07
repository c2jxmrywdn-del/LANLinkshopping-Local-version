# -*- coding: utf-8 -*-
"""生产环境安全加固与高可用测试（API Key 密钥环平滑轮换 / 分布式令牌桶限流 / 微服务熔断器）。"""
import os
import time
import unittest
from unittest.mock import patch, MagicMock

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
for _name in ("REDIS_URL", "REDIS_URLS", "REDIS_PASSWORD"):
    os.environ.pop(_name, None)

from fastapi.testclient import TestClient
from app.main import app, require_gateway_key, VALID_GATEWAY_KEYS, PRIMARY_GATEWAY_KEY
from app.rate_limiter import (
    InMemoryTokenBucket,
    DistributedRateLimiter,
    CircuitBreaker,
    client_fingerprint,
    BACKEND_CIRCUIT_BREAKER
)
from app import tools


class TestKeyRingRotation(unittest.TestCase):
    """测试多 Key 密钥环与平滑过渡轮换。"""

    def setUp(self):
        self.client = TestClient(app)

    def test_key_ring_primary_accepted(self):
        """主 Key 请求正常通过。"""
        headers = {"Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}"}
        resp = self.client.get("/v1/models", headers=headers)
        self.assertEqual(resp.status_code, 200)

    def test_key_ring_secondary_accepted(self):
        """密钥环中的备选/过渡期 Key 同样正常通过。"""
        with patch("app.main.VALID_GATEWAY_KEYS", [PRIMARY_GATEWAY_KEY, "test-retiring-key-old"]):
            headers = {"Authorization": "Bearer test-retiring-key-old"}
            resp = self.client.get("/v1/models", headers=headers)
            self.assertEqual(resp.status_code, 200)

    def test_key_ring_invalid_rejected(self):
        """不在密钥环内的未知 Key 严格 401。"""
        headers = {"Authorization": "Bearer totally-fake-invalid-key"}
        resp = self.client.get("/v1/models", headers=headers)
        self.assertEqual(resp.status_code, 401)
        self.assertEqual(resp.json()["error"]["type"], "authentication_error")


class TestRateLimiter(unittest.TestCase):
    """测试令牌桶限流算法与多租户指纹隔离。"""

    def test_client_fingerprint_deterministic(self):
        """客户端指纹哈希确定性与隔离性。"""
        fp1 = client_fingerprint("key-alpha", "127.0.0.1")
        fp2 = client_fingerprint("key-alpha", "192.168.1.100")
        fp3 = client_fingerprint("key-beta", "127.0.0.1")
        # 只要 key 一致，指纹一致（按租户隔离）
        self.assertEqual(fp1, fp2)
        # 不同 key 指纹隔离
        self.assertNotEqual(fp1, fp3)

    def test_in_memory_token_bucket(self):
        """本地内存令牌桶消耗与超限计算。"""
        bucket = InMemoryTokenBucket(capacity=3, fill_rate_per_sec=1.0)
        # 前 3 次消费成功
        for _ in range(3):
            allowed, remaining, wait = bucket.consume(1)
            self.assertTrue(allowed)
        # 第 4 次耗尽
        allowed, remaining, wait = bucket.consume(1)
        self.assertFalse(allowed)
        self.assertGreaterEqual(wait, 1)

    def test_rate_limiter_acquire_flow(self):
        """限流管理器请求流程与内存降级。"""
        limiter = DistributedRateLimiter(rate_per_min=60, burst=5)
        # 初始请求放行
        allowed, rem, wait = limiter.acquire("tenant-001", cost=1)
        self.assertTrue(allowed)
        self.assertGreater(rem, 0)


class TestCircuitBreaker(unittest.TestCase):
    """测试微服务调用熔断器。"""

    def test_circuit_breaker_transitions(self):
        """测试 CLOSED -> OPEN -> HALF_OPEN -> CLOSED 状态机流转。"""
        cb = CircuitBreaker(failure_threshold=3, recovery_timeout=0.1)
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        self.assertTrue(cb.allow_request())

        # 连续记录 3 次失败
        cb.record_failure()
        cb.record_failure()
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        cb.record_failure()
        self.assertEqual(cb.state, CircuitBreaker.OPEN)

        # 熔断打开期间，不允许请求
        self.assertFalse(cb.allow_request())

        # 睡眠等待恢复超时
        time.sleep(0.15)
        # 进入半开状态
        self.assertTrue(cb.allow_request())
        self.assertEqual(cb.state, CircuitBreaker.HALF_OPEN)

        # 探测成功，重置为 CLOSED
        cb.record_success()
        self.assertEqual(cb.state, CircuitBreaker.CLOSED)
        self.assertTrue(cb.allow_request())

    def test_forward_to_backend_respects_circuit_breaker(self):
        """当熔断器处于 OPEN 时，forward_to_backend 快速失败，不发起真实网络请求。"""
        with patch.object(BACKEND_CIRCUIT_BREAKER, "allow_request", return_value=False):
            res = tools.forward_to_backend("lanlink_search_products", {"keyword": "钢"})
            self.assertFalse(res["ok"])
            self.assertTrue(res.get("circuit_open"))
            self.assertIn("熔断", res["error"])


class TestRateLimitBypassHeader(unittest.TestCase):
    """限流绕过头必须显式开启，外部客户端不能靠伪造 Header 绕过令牌桶。"""

    def setUp(self):
        self.client = TestClient(app)
        self.auth = {"Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}"}

    def test_external_bypass_header_is_ignored_by_default(self):
        with patch.dict(os.environ, {"GATEWAY_DISABLE_RATE_LIMIT": "0"}, clear=False):
            with patch("app.main._BYPASS_HEADER_ALLOWED", False):
                with patch("app.main.RATE_LIMITER.acquire", return_value=(False, 0, 1)):
                    resp = self.client.get("/v1/models", headers={
                        **self.auth, "X-Bypass-Rate-Limit": "true"})
                    self.assertEqual(resp.status_code, 429)

    def test_bypass_header_allowed_only_when_explicitly_enabled(self):
        with patch.dict(os.environ, {"GATEWAY_DISABLE_RATE_LIMIT": "0"}, clear=False):
            with patch("app.main._BYPASS_HEADER_ALLOWED", True):
                with patch("app.main.RATE_LIMITER.acquire", return_value=(False, 0, 1)):
                    resp = self.client.get("/v1/models", headers={
                        **self.auth, "X-Bypass-Rate-Limit": "true"})
                    self.assertEqual(resp.status_code, 200)


class TestMetricsToken(unittest.TestCase):
    """/metrics 独立令牌：未设置保持公开，设置后必须携带有效令牌。"""

    def setUp(self):
        self.client = TestClient(app)

    def test_metrics_public_when_token_not_configured(self):
        with patch("app.main._METRICS_TOKEN", ""):
            resp = self.client.get("/metrics")
            self.assertEqual(resp.status_code, 200)

    def test_metrics_requires_token_when_configured(self):
        token = "metrics-test-token"
        with patch("app.main._METRICS_TOKEN", token):
            self.assertEqual(self.client.get("/metrics").status_code, 401)
            self.assertEqual(self.client.get(
                "/metrics", headers={"X-Metrics-Token": "wrong-token"}).status_code, 401)
            self.assertEqual(self.client.get(
                "/metrics", headers={"X-Metrics-Token": token}).status_code, 200)
            self.assertEqual(self.client.get(
                "/metrics", headers={"Authorization": f"Bearer {token}"}).status_code, 200)


if __name__ == "__main__":
    unittest.main()

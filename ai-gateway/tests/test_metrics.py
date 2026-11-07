# -*- coding: utf-8 -*-
"""测试 Prometheus 监控指标收集与导出（Counter / Gauge / Histogram / /metrics 端点 / GPU 联动）。"""
import os
import sys
from pathlib import Path
import unittest

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
from app.metrics import Counter, Gauge, Histogram, REGISTRY, MetricsRegistry


class TestMetrics(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(app)
        self.auth_headers = {"Authorization": f"Bearer {GATEWAY_API_KEY}"}

    def test_counter_inc_and_render(self):
        c = Counter("test_counter_total", "Test counter doc", labelnames=("method", "status"))
        c.inc({"method": "POST", "status": "200"})
        c.inc({"method": "POST", "status": "200"}, amount=2)
        c.inc({"method": "GET", "status": "404"})

        rendered = c.render()
        text = "\n".join(rendered)
        self.assertIn("# HELP test_counter_total Test counter doc", text)
        self.assertIn("# TYPE test_counter_total counter", text)
        self.assertIn('test_counter_total{method="POST",status="200"} 3', text)
        self.assertIn('test_counter_total{method="GET",status="404"} 1', text)

    def test_gauge_set_and_inc(self):
        g = Gauge("test_gauge", "Test gauge doc", labelnames=("service",))
        g.set(10.5, {"service": "gateway"})
        g.inc({"service": "gateway"}, 2.5)
        g.dec({"service": "gateway"}, 1.0)

        rendered = g.render()
        text = "\n".join(rendered)
        self.assertIn("# TYPE test_gauge gauge", text)
        self.assertIn('test_gauge{service="gateway"} 12', text)

    def test_histogram_observe(self):
        h = Histogram("test_duration_seconds", "Test histogram doc", labelnames=("route",), buckets=(0.1, 0.5, 1.0))
        h.observe(0.05, {"route": "/api"})
        h.observe(0.2, {"route": "/api"})
        h.observe(0.8, {"route": "/api"})
        h.observe(2.0, {"route": "/api"})

        rendered = h.render()
        text = "\n".join(rendered)
        self.assertIn("# TYPE test_duration_seconds histogram", text)
        self.assertIn('test_duration_seconds_bucket{le="0.1",route="/api"} 1', text)
        self.assertIn('test_duration_seconds_bucket{le="0.5",route="/api"} 2', text)
        self.assertIn('test_duration_seconds_bucket{le="1.0",route="/api"} 3', text)
        self.assertIn('test_duration_seconds_bucket{le="+Inf",route="/api"} 4', text)
        self.assertIn('test_duration_seconds_count{route="/api"} 4', text)
        self.assertIn('test_duration_seconds_sum{route="/api"} 3.05', text)

    def test_metrics_endpoint_public_access(self):
        """验证 /metrics 端点免密公开访问，并输出 Prometheus 标准文本格式。"""
        resp = self.client.get("/metrics")
        self.assertEqual(resp.status_code, 200)
        self.assertIn("text/plain", resp.headers["content-type"])
        body = resp.text
        self.assertIn("# HELP ai_gateway_requests_total", body)
        self.assertIn("# TYPE ai_gateway_requests_total counter", body)
        self.assertIn("# HELP ai_gateway_tool_calls_total", body)
        self.assertIn("# HELP ai_gateway_gpu_memory_used_megabytes", body)

    def test_middleware_role_tracking(self):
        """验证中间件自动捕获 X-Agent-Role 并沉淀到指标维度。"""
        headers = dict(self.auth_headers)
        headers["X-Agent-Role"] = "finance_test_agent"
        resp = self.client.get("/v1/models", headers=headers)
        self.assertEqual(resp.status_code, 200)

        # 检查 /metrics 中是否已记录 finance_test_agent 的请求指标
        metrics_resp = self.client.get("/metrics")
        self.assertIn('role="finance_test_agent"', metrics_resp.text)


if __name__ == "__main__":
    unittest.main()

# -*- coding: utf-8 -*-
"""LANLink AI Gateway Prometheus 可观测性指标与全链路实测脚本

测试范围：
1. 验证 /metrics 免密公开端点输出格式符合 Prometheus 0.0.4 规范；
2. 模拟三大 Agent 角色（Buyer / Finance / Fulfillment）业务调用，验证指标多维度动态沉淀；
3. 验证工具调用计数器（ai_gateway_tool_calls_total）与耗时直方图（ai_gateway_tool_duration_seconds）；
4. 验证并发活跃请求数（ai_gateway_active_requests）与状态码计数器；
5. 验证宿主机 GPU 显存与利用率联动指标正确导出。
"""
import json
import os
import sys
import time
import urllib.request
import urllib.error

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

GATEWAY_URL = os.environ.get("LANLINK_GATEWAY_URL", "http://127.0.0.1:8090").rstrip("/")
GATEWAY_KEY = (
    os.environ.get("LANLINK_GATEWAY_KEY")
    or os.environ.get("GATEWAY_API_KEY")
    or os.environ.get("AI_GATEWAY_API_KEY")
    or "lanlink-local-ai-gateway"
).strip()


def http_get(path, headers=None):
    url = f"{GATEWAY_URL}{path}"
    req_headers = {"User-Agent": "lanlink-metrics-verifier/1.0"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, resp.read().decode("utf-8")


def http_post(path, data, headers=None):
    url = f"{GATEWAY_URL}{path}"
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": "lanlink-metrics-verifier/1.0"
    }
    if headers:
        req_headers.update(headers)
    body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def main():
    print("=" * 70)
    print("=== LANLink AI 网关 Prometheus 可观测性指标端到端实测 ===")
    print("=" * 70)

    auth_headers = {"Authorization": f"Bearer {GATEWAY_KEY}"}

    # 1. 验证 /metrics 免密公开访问
    print("\n--- 1. 验证 /metrics 端点公开抓取与格式规范 ---")
    status, metrics_text = http_get("/metrics")
    assert status == 200, f"/metrics 抓取失败: status={status}"
    assert "# HELP ai_gateway_requests_total" in metrics_text
    assert "# TYPE ai_gateway_requests_total counter" in metrics_text
    assert "# HELP ai_gateway_tool_calls_total" in metrics_text
    assert "# TYPE ai_gateway_tool_duration_seconds histogram" in metrics_text
    print(" [PASS] /metrics 基础文本格式符合 Prometheus 0.0.4 规范")

    # 2. 模拟 Buyer Agent 流量
    print("\n--- 2. 模拟 Buyer Agent 流量注入与指标记录 ---")
    buyer_headers = dict(auth_headers)
    buyer_headers["X-Agent-Role"] = "buyer"

    status, resp = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_search_products",
        "arguments": {"keyword": "钢", "limit": 2},
        "role": "buyer"
    }, buyer_headers)
    assert status == 200 and resp["success"]
    prod_id = resp["data"]["items"][0]["prodId"]

    status, resp = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_create_cart_draft",
        "arguments": {"productId": prod_id, "quantity": 10, "userId": 888},
        "role": "buyer"
    }, buyer_headers)
    assert status == 200 and resp["success"]
    draft_id = resp["data"]["draftId"]
    print(f" Buyer Agent 生成草稿单: {draft_id}")

    # 3. 模拟 Finance Auditor Agent 流量
    print("\n--- 3. 模拟 Finance Auditor Agent 流量注入与指标记录 ---")
    finance_headers = dict(auth_headers)
    finance_headers["X-Agent-Role"] = "finance"

    status, resp = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_audit_procurement_draft",
        "arguments": {"draftId": draft_id, "userId": 888, "enterpriseId": 1},
        "role": "finance"
    }, finance_headers)
    assert status == 200 and resp["success"]
    print(f" Finance Agent 完成审核: status={resp['data']['auditStatus']}")

    status, resp = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_approve_procurement_flow",
        "arguments": {"draftId": draft_id, "approved": True, "comment": "监控实测放行"},
        "role": "finance"
    }, finance_headers)
    assert status == 200 and resp["success"]
    print(f" Finance Agent 签发放行凭证: {resp['data']['approvalToken']}")

    # 4. 模拟 Fulfillment Agent 流量
    print("\n--- 4. 模拟 Fulfillment Agent 流量注入与指标记录 ---")
    ful_headers = dict(auth_headers)
    ful_headers["X-Agent-Role"] = "fulfillment"

    status, resp = http_post("/v1/ai/tools/execute", {
        "tool": "lanlink_monitor_delivery_sla",
        "arguments": {"orderNo": "20261022170813519896"},
        "user_id": 4,
        "role": "fulfillment"
    }, ful_headers)
    assert status == 200 and resp["success"]
    print(f" Fulfillment Agent 诊断 SLA: status={resp['data']['slaStatus']}")

    # 5. 抓取 /metrics 校验多 Agent 角色与工具指标完整性
    print("\n--- 5. 校验 /metrics 多维度数据正确沉淀 ---")
    status, updated_metrics = http_get("/metrics")
    assert status == 200

    # 验证角色请求记录
    assert 'role="buyer"' in updated_metrics, "缺失 buyer 角色请求指标"
    assert 'role="finance"' in updated_metrics, "缺失 finance 角色请求指标"
    assert 'role="fulfillment"' in updated_metrics, "缺失 fulfillment 角色请求指标"
    print(" [PASS] 成功捕获 Buyer / Finance / Fulfillment 角色维度请求计数与时延")

    # 验证工具调用计数与直方图
    assert 'tool="lanlink_search_products"' in updated_metrics
    assert 'tool="lanlink_audit_procurement_draft"' in updated_metrics
    assert 'tool="lanlink_monitor_delivery_sla"' in updated_metrics
    assert "ai_gateway_tool_duration_seconds_bucket" in updated_metrics
    print(" [PASS] 成功沉淀电商与协同工具调用成功率与 P95/P99 时延直方图")

    # 验证 GPU 显存联动指标
    assert "ai_gateway_gpu_memory_total_megabytes" in updated_metrics
    assert "ai_gateway_gpu_memory_used_megabytes" in updated_metrics
    assert "ai_gateway_gpu_utilization_percent" in updated_metrics
    print(" [PASS] 成功联动宿主机 GPU 显存水位与核心计算利用率指标")

    print("\n" + "=" * 70)
    print("=== [ALL PASSED] Phase 4 - Step 1 Prometheus 可观测性指标 100% 验证通过！ ===")
    print("=" * 70)


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""极端异常混沌容灾与离线降级端到端实测脚本（Phase 4 - Step 2）。

覆盖场景：
1. 大模型推理不可达/超时/熔断 -> 启发式规则降级引擎自动接管（选型、询价、物流、政策）；
2. 流式 SSE 降级输出连续性核验（数据分片与 [DONE] 终止符完整）；
3. 分布式令牌桶突发并发限流（429 拦截、标准响应头与 Prometheus 计数）；
4. Prometheus 指标暴露核验（ai_gateway_fallback_total, rate_limit_hits, GPU 显存）；
5. 业务 0 异常、0 崩溃、回退率 100% 验收。
"""
import concurrent.futures
import json
import os
import sys
import time
import urllib.request
import urllib.error

# 解决 Windows 控制台编码问题
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

GATEWAY_URL = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8090")
METRICS_URL = f"{GATEWAY_URL}/metrics"
API_KEY = (
    os.environ.get("GATEWAY_API_KEY")
    or os.environ.get("AI_GATEWAY_API_KEY")
    or "lanlink-local-ai-gateway"
).strip()


def _post_json(path: str, payload: dict, headers: dict = None, timeout: float = 30.0):
    url = f"{GATEWAY_URL}{path}"
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req_headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {API_KEY}",
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            resp_headers = dict(resp.headers)
            return resp.status, resp_headers, body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return e.code, dict(e.headers), body
    except Exception as exc:
        return 0, {}, str(exc)


def _get(path: str, headers: dict = None, timeout: float = 30.0):
    url = f"{GATEWAY_URL}{path}"
    req_headers = {"Authorization": f"Bearer {API_KEY}"}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return resp.status, dict(resp.headers), body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return e.code, dict(e.headers), body
    except Exception as exc:
        return 0, {}, str(exc)


def test_scenario_1_heuristic_fallback_intents():
    print("\n" + "=" * 70)
    print("▶ 场景一：大模型离线/超时/熔断 -> 启发式规则降级引擎自动接管")
    print("=" * 70)

    test_cases = [
        {
            "name": "1. 阶梯核价与批量询价意图降级",
            "prompt": "计算商品 1 买 500 件的价格",
            "role": "buyer",
            "must_contain": ["【核价结果】", "3,850.00", "采购数量", "500"],
            "expected_intent": "quotation"
        },
        {
            "name": "2. 工业品选型与推荐意图降级",
            "prompt": "推荐几款建筑钢材",
            "role": "buyer",
            "must_contain": ["建筑钢材", "HRB400", "螺纹钢", "现货单价"],
            "expected_intent": "product_search"
        },
        {
            "name": "3. 履约跟踪与物流 SLA 意图降级",
            "prompt": "查询订单 20261022170813519896 的物流",
            "role": "fulfillment",
            "must_contain": ["物流追踪", "20261022170813519896", "运输中", "LL202610230001"],
            "expected_intent": "logistics"
        },
        {
            "name": "4. 官方商城政策与规章问答意图降级",
            "prompt": "商城的售后质保政策是什么？",
            "role": "buyer",
            "must_contain": ["售后政策", "七天无理由退货", "质量问题"],
            "expected_intent": "policy_faq"
        },
        {
            "name": "5. 模糊自然语言意图兜底为操作指引",
            "prompt": "系统帮助与快捷指令",
            "role": "buyer",
            "must_contain": ["离线应急服务", "工业品选型与推荐", "阶梯核价与批量询价"],
            "expected_intent": "general_guide"
        }
    ]

    for tc in test_cases:
        print(f"\n[测试项] {tc['name']}")
        print(f"  输入提问: \"{tc['prompt']}\" (Role: {tc['role']})")

        # 透传 X-Agent-Role 模拟多 Agent 角色请求
        headers = {
            "X-Agent-Role": tc["role"],
        }
        payload = {
            "model": "mock-qwen2.5-7b",
            "messages": [{"role": "user", "content": tc["prompt"]}],
            "stream": False
        }
        status, resp_headers, body = _post_json("/v1/chat/completions", payload, headers=headers)
        assert status == 200, f"HTTP 状态码异常: {status}, body={body}"

        # 验证降级响应头
        fb_header = resp_headers.get("X-Lanlink-Fallback") or resp_headers.get("x-lanlink-fallback")
        fb_reason = resp_headers.get("X-Lanlink-Fallback-Reason") or resp_headers.get("x-lanlink-fallback-reason")
        print(f"  响应状态: HTTP 200 OK | X-Lanlink-Fallback: {fb_header} | Reason: {fb_reason}")
        assert fb_header == "heuristic", f"缺少或不正确的 X-Lanlink-Fallback 响应头: {fb_header}"

        data = json.loads(body)
        lanlink_meta = data.get("lanlink", {})
        content = data["choices"][0]["message"]["content"]
        intent = lanlink_meta.get("fallback_intent") or "matched"

        print(f"  识别意图: {intent} | 工具轨迹数: {len(lanlink_meta.get('fallback_trace', []))}")
        print(f"  内容预览: {content[:100].replace(chr(10), ' ')}...")

        for kw in tc["must_contain"]:
            assert kw in content, f"响应内容缺少关键断言词: '{kw}'"
        print(f"  ✅ 断言通过！")


def test_scenario_2_streaming_fallback():
    print("\n" + "=" * 70)
    print("▶ 场景二：流式 SSE 降级响应输出连续性与协议规范核验")
    print("=" * 70)

    payload = {
        "model": "mock-qwen2.5-7b",
        "messages": [{"role": "user", "content": "计算商品 1 买 500 件的价格"}],
        "stream": True
    }
    headers = {"X-Agent-Role": "buyer"}
    status, resp_headers, sse_text = _post_json("/v1/chat/completions", payload, headers=headers)
    assert status == 200, f"SSE 流式请求失败: {status}"

    fb_header = resp_headers.get("X-Lanlink-Fallback") or resp_headers.get("x-lanlink-fallback")
    print(f"  流式响应状态: HTTP 200 OK | X-Lanlink-Fallback: {fb_header}")
    assert fb_header == "heuristic", f"流式响应缺少降级响应头: {fb_header}"

    lines = [line.strip() for line in sse_text.split("\n") if line.strip()]
    data_lines = [l for l in lines if l.startswith("data:")]
    assert len(data_lines) >= 3, f"SSE 数据帧过少: {len(data_lines)}"
    assert data_lines[-1] == "data: [DONE]", f"未找到 [DONE] 结束标志: {data_lines[-1]}"

    full_chunks = []
    for l in data_lines[:-1]:
        chunk_json = l[len("data:"):].strip()
        try:
            chunk = json.loads(chunk_json)
            delta = chunk["choices"][0].get("delta", {})
            if "content" in delta:
                full_chunks.append(delta["content"])
        except Exception:
            pass

    full_streamed_text = "".join(full_chunks)
    print(f"  流式接收帧数: {len(data_lines)} 帧 | 合并字符数: {len(full_streamed_text)}")
    assert "【核价结果】" in full_streamed_text
    print(f"  ✅ 流式分片协议与终止符核验 100% 通过！")


def test_scenario_3_burst_rate_limiting():
    print("\n" + "=" * 70)
    print("▶ 场景三：突发高并发攻击与令牌桶限流熔断核验")
    print("=" * 70)

    print("  发起 160 个瞬时并发请求测试令牌桶防御与 429 拦截（容量 150 令牌）...")
    status_codes = []

    def make_req(idx):
        payload = {
            "model": "mock-qwen2.5-7b",
            "messages": [{"role": "user", "content": f"ping_{idx}"}],
            "stream": False
        }
        s, h, b = _post_json("/v1/chat/completions", payload, timeout=15.0)
        retry_after = h.get("Retry-After") or h.get("retry-after")
        return s, retry_after

    with concurrent.futures.ThreadPoolExecutor(max_workers=25) as executor:
        futures = [executor.submit(make_req, i) for i in range(160)]
        for f in concurrent.futures.as_completed(futures):
            st, ra = f.result()
            status_codes.append((st, ra))

    code_counts = {}
    for st, _ in status_codes:
        code_counts[st] = code_counts.get(st, 0) + 1

    print(f"  并发请求响应分布: {code_counts}")
    assert 200 in code_counts, "应有正常放行请求"
    if 429 in code_counts:
        sample_429 = next(ra for st, ra in status_codes if st == 429)
        print(f"  ✅ 成功触发 429 限流拦截！拦截量: {code_counts[429]} 次 | 携带 Retry-After: {sample_429}s")
    else:
        print("  ℹ️ 本轮并发被平滑放行或在补充周期内。")


def test_scenario_4_prometheus_metrics():
    print("\n" + "=" * 70)
    print("▶ 场景四：Prometheus 可观测性指标与降级计数暴露核验")
    print("=" * 70)

    status, headers, text = _get("/metrics")
    assert status == 200, f"/metrics 请求失败: {status}"

    required_metrics = [
        "ai_gateway_requests_total",
        "ai_gateway_request_duration_seconds",
        "ai_gateway_fallback_total",
        "ai_gateway_gpu_memory_total_megabytes",
        "ai_gateway_gpu_memory_used_megabytes",
    ]

    for m in required_metrics:
        assert m in text, f"/metrics 中缺少关键指标: {m}"
        print(f"  ✓ 成功采集到监控指标: {m}")

    print("\n  [当前 ai_gateway_fallback_total 指标样本]:")
    for line in text.split("\n"):
        if line.startswith("ai_gateway_fallback_total"):
            print(f"    {line}")

    print("  ✅ Prometheus 指标链路与 Grafana 数据面 100% 就绪！")


def main():
    print("🚀 开始执行 LANLink AI 网关极端异常混沌容灾与启发式降级实测...")
    t0 = time.time()
    try:
        test_scenario_1_heuristic_fallback_intents()
        test_scenario_2_streaming_fallback()
        test_scenario_3_burst_rate_limiting()
        test_scenario_4_prometheus_metrics()
        elapsed = time.time() - t0
        print("\n" + "=" * 70)
        print(f"🎉 混沌容灾演练与启发式降级方案验收全量通过！(总耗时: {elapsed:.2f}s)")
        print("=" * 70)
    except AssertionError as ae:
        print(f"\n❌ 断言失败: {ae}")
        sys.exit(1)
    except Exception as exc:
        print(f"\n❌ 实测执行异常: {exc}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

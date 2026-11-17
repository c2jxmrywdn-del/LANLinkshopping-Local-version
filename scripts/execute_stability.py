"""AI 网关只读工具 (lanlink_ask_data) 连续调用稳定性压测脚本。

用于验证在高频/连续调用下，网关与后端 SQLBot 的连接池、鉴权以及流式响应稳定性。
"""

import argparse
import os
import sys
import time
from pathlib import Path
import requests

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def _read_env(key: str, default: str = "") -> str:
    root = Path(__file__).resolve().parent.parent
    try:
        for line in (root / ".env").read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line.startswith(f"{key}="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return os.environ.get(key, default)


GATEWAY_URL = _read_env("AI_GATEWAY_URL", "http://127.0.0.1:8090").rstrip("/")
API_KEY = _read_env("AI_GATEWAY_KEY", "lanlink-gateway-secret-2026")


def run_benchmark(rounds: int = 10, question: str = "平台一共有多少个商品？"):
    print(f"=== 开始稳定性测试：共 {rounds} 轮，目标: {GATEWAY_URL} ===")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "tool_name": "lanlink_ask_data",
        "arguments": {"question": question},
    }

    latencies = []
    success_count = 0
    fail_count = 0

    for i in range(1, rounds + 1):
        t0 = time.time()
        try:
            r = requests.post(
                f"{GATEWAY_URL}/v1/ai/tools/execute",
                headers=headers,
                json=payload,
                timeout=60,
            )
            cost = time.time() - t0
            latencies.append(cost)
            if r.status_code == 200:
                success_count += 1
                print(f"  [Round {i:02d}/{rounds:02d}] 成功 (耗时: {cost:.2f}s, status: 200)")
            else:
                fail_count += 1
                print(f"  [Round {i:02d}/{rounds:02d}] 失败 (耗时: {cost:.2f}s, status: {r.status_code}, error: {r.text[:100]})")
        except Exception as e:
            cost = time.time() - t0
            latencies.append(cost)
            fail_count += 1
            print(f"  [Round {i:02d}/{rounds:02d}] 异常 (耗时: {cost:.2f}s, exc: {e})")

    print("\n--- 稳定性测试统计报告 ---")
    print(f"总请求数: {rounds}")
    print(f"成功次数: {success_count} ({success_count/rounds*100:.1f}%)")
    print(f"失败次数: {fail_count} ({fail_count/rounds*100:.1f}%)")
    if latencies:
        latencies.sort()
        avg_lat = sum(latencies) / len(latencies)
        min_lat = latencies[0]
        max_lat = latencies[-1]
        p95_idx = int(len(latencies) * 0.95)
        p95_lat = latencies[min(p95_idx, len(latencies) - 1)]
        print(f"延迟: Min={min_lat:.2f}s, Avg={avg_lat:.2f}s, Max={max_lat:.2f}s, P95={p95_lat:.2f}s")

    return 0 if fail_count == 0 else 1


def main():
    parser = argparse.ArgumentParser(description="AI 网关工具执行稳定性压测")
    parser.add_argument("--rounds", type=int, default=10, help="执行轮数")
    parser.add_argument("--question", default="平台一共有多少个商品？", help="提问内容")
    args = parser.parse_args()
    sys.exit(run_benchmark(rounds=args.rounds, question=args.question))


if __name__ == "__main__":
    main()

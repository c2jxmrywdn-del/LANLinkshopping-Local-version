# -*- coding: utf-8 -*-
"""RAG 服务压测脚本（针对 rag-service，非 AI 网关）。

与 scripts/stress_benchmark.py 的区别：
  - 那个脚本压的是 ai-gateway 的三级缓存；
  - 本脚本压的是 rag-service 的 /health、/ingest、/query 三个端点。

设计要点：
  1. **分端点统计**：/health 是纯内存检查，/query 要过 embedding + LLM，
     两者混在一起算 P99 毫无意义，必须分开报。
  2. **并发用线程池**：服务端是 async，但压测端用同步 requests + 线程池
     更接近真实客户端（浏览器/后端 HTTP 调用），也能避免 asyncio 单线程瓶颈
     把服务端能力低估。
  3. **预热**：首次请求会触发 LightRAG 存储加载与 embedding worker 启动，
     不预热的话第一个样本会拉高整体均值。

用法：
    python scripts/rag_service_benchmark.py --base-url http://127.0.0.1:18093 \
        --secret loadtest --endpoint health --concurrency 20 --requests 200
    python scripts/rag_service_benchmark.py --endpoint query --concurrency 10 --requests 50
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

import requests

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


@dataclass
class Result:
    latencies: list[float] = field(default_factory=list)
    statuses: dict[int, int] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    def add(self, ms: float, status: int, err: str | None = None) -> None:
        self.latencies.append(ms)
        self.statuses[status] = self.statuses.get(status, 0) + 1
        if err:
            self.errors.append(err)


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    k = (len(ordered) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(ordered) - 1)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (k - lo)


def one_call(session: requests.Session, url: str, secret: str,
             payload: dict | None) -> tuple[float, int, str | None]:
    headers = {"X-Internal-Secret": secret, "Content-Type": "application/json"}
    start = time.perf_counter()
    try:
        if payload is None:
            resp = session.get(url, headers=headers, timeout=300)
        else:
            resp = session.post(url, headers=headers, json=payload, timeout=300)
        elapsed = (time.perf_counter() - start) * 1000
        err = None if resp.status_code < 400 else resp.text[:200]
        return elapsed, resp.status_code, err
    except Exception as e:  # noqa: BLE001
        return (time.perf_counter() - start) * 1000, 0, f"{type(e).__name__}: {e}"[:200]


def run(base: str, secret: str, endpoint: str, concurrency: int, total: int,
        warmup: int) -> Result:
    paths = {
        "health": ("/health", None),
        "query": ("/query", {"question": "单笔充值上限是多少？", "mode": "naive", "topK": 5}),
    }
    path, payload = paths[endpoint]
    url = base.rstrip("/") + path

    result = Result()
    session = requests.Session()

    # 预热：让服务端完成存储加载/worker 启动，避免首样本污染统计
    for _ in range(warmup):
        one_call(session, url, secret, payload)

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(one_call, session, url, secret, payload) for _ in range(total)]
        for f in as_completed(futures):
            ms, status, err = f.result()
            result.add(ms, status, err)
    wall = time.perf_counter() - started

    lat = result.latencies
    ok = sum(c for s, c in result.statuses.items() if 200 <= s < 300)
    print(f"\n=== {endpoint} | concurrency={concurrency} total={total} ===")
    print(f"wall={wall:.2f}s  qps={total / wall:.1f}  success={ok}/{total} "
          f"({ok / total * 100:.1f}%)")
    print(f"min={min(lat):.1f}ms  mean={statistics.mean(lat):.1f}ms  "
          f"p50={percentile(lat, 0.50):.1f}ms  p90={percentile(lat, 0.90):.1f}ms  "
          f"p95={percentile(lat, 0.95):.1f}ms  p99={percentile(lat, 0.99):.1f}ms  "
          f"max={max(lat):.1f}ms")
    print(f"status distribution: {dict(sorted(result.statuses.items()))}")
    if result.errors:
        print("sample errors:")
        for e in result.errors[:3]:
            print("  -", e)
    return result


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://127.0.0.1:18093")
    p.add_argument("--secret", default="loadtest")
    p.add_argument("--endpoint", choices=["health", "query"], default="health")
    p.add_argument("--concurrency", type=int, default=20)
    p.add_argument("--requests", type=int, default=200)
    p.add_argument("--warmup", type=int, default=3)
    p.add_argument("--json-out", default="")
    args = p.parse_args()

    r = run(args.base_url, args.secret, args.endpoint,
            args.concurrency, args.requests, args.warmup)
    if args.json_out:
        lat = r.latencies
        summary = {
            "endpoint": args.endpoint,
            "concurrency": args.concurrency,
            "requests": args.requests,
            "success": sum(c for s, c in r.statuses.items() if 200 <= s < 300),
            "p50": round(percentile(lat, 0.50), 1),
            "p95": round(percentile(lat, 0.95), 1),
            "p99": round(percentile(lat, 0.99), 1),
            "max": round(max(lat), 1),
            "statuses": r.statuses,
        }
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(summary, fh, ensure_ascii=False, indent=2)
        print(f"\njson written: {args.json_out}")


if __name__ == "__main__":
    main()

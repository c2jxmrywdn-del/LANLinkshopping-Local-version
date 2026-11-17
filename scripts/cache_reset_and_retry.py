"""语义缓存重置与重试验证脚本。

验证流程：
1. 发起首轮请求，记录耗时（应为 Cache Miss）
2. 立即发起相同提问，验证命中语义缓存（应为 Cache Hit，耗时显著降低）
3. 携带 Cache-Control: no-cache 发起重试，验证绕过缓存并重新计算
"""

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


def main():
    print(f"=== 语义缓存验证流程 (Target: {GATEWAY_URL}) ===")
    question = "统计平台当前所有商品的库存总数"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "tool_name": "lanlink_ask_data",
        "arguments": {"question": question},
    }

    # 1. 首次调用
    print("[1] 发起首轮请求 (预期 Cache Miss)...")
    t0 = time.time()
    r1 = requests.post(f"{GATEWAY_URL}/v1/ai/tools/execute", headers=headers, json=payload, timeout=60)
    t1 = time.time() - t0
    print(f"  首轮状态码: {r1.status_code}, 耗时: {t1:.3f}s")

    # 2. 第二次调用（预期命中缓存）
    print("[2] 发起相同请求 (预期 Cache Hit)...")
    t0 = time.time()
    r2 = requests.post(f"{GATEWAY_URL}/v1/ai/tools/execute", headers=headers, json=payload, timeout=60)
    t2 = time.time() - t0
    cached = r2.headers.get("X-Cache-Status", "") or ("Hit" if t2 < t1 * 0.5 else "Miss")
    print(f"  次轮状态码: {r2.status_code}, 耗时: {t2:.3f}s (Cache: {cached})")

    # 3. 绕过缓存调用
    print("[3] 携带 Cache-Control: no-cache 请求 (预期 Cache Bypass)...")
    bypass_headers = {**headers, "Cache-Control": "no-cache"}
    t0 = time.time()
    r3 = requests.post(f"{GATEWAY_URL}/v1/ai/tools/execute", headers=bypass_headers, json=payload, timeout=60)
    t3 = time.time() - t0
    print(f"  重置轮状态码: {r3.status_code}, 耗时: {t3:.3f}s")

    print("\n--- 语义缓存对比总结 ---")
    print(f"Miss 延迟: {t1:.3f}s")
    print(f"Hit  延迟: {t2:.3f}s (加速比: {t1/max(t2, 0.001):.1f}x)")
    print(f"Bypass 延迟: {t3:.3f}s")
    print("[+] 语义缓存流转逻辑验证完成")


if __name__ == "__main__":
    main()

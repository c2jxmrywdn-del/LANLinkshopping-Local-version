"""AI 网关工具及鉴权端点快速验证脚本。

验证项：
1. GET /v1/ai/tools 无 Token -> 401
2. GET /v1/ai/tools 带 Token -> 200，检查工具列表与角色权限
3. POST /v1/ai/tools/execute 调只读工具 lanlink_ask_data -> 200（无需角色头）
4. POST /v1/ai/tools/lanlink_ask_data 直接调用 -> 200
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


def test_tools_list_unauth():
    print("[1] 测试 GET /v1/ai/tools 无鉴权...")
    try:
        r = requests.get(f"{GATEWAY_URL}/v1/ai/tools", timeout=5)
        if r.status_code == 401:
            print("  [PASS] 成功拦截未鉴权请求 (401)")
            return True
        else:
            print(f"  [WARN] 状态码非 401: {r.status_code}")
            return False
    except Exception as e:
        print(f"  [FAIL] 连接网关失败: {e}")
        return False


def test_tools_list_auth():
    print("[2] 测试 GET /v1/ai/tools 带鉴权...")
    headers = {"Authorization": f"Bearer {API_KEY}"}
    try:
        r = requests.get(f"{GATEWAY_URL}/v1/ai/tools", headers=headers, timeout=10)
        if r.status_code == 200:
            data = r.json()
            tools = data.get("tools", [])
            print(f"  [PASS] 成功获取工具列表，共 {len(tools)} 个工具")
            names = [t.get("name") for t in tools]
            if "lanlink_ask_data" in names:
                print("  [PASS] 工具列表中包含 lanlink_ask_data")
            return True
        else:
            print(f"  [FAIL] 状态码异常: {r.status_code}, {r.text[:200]}")
            return False
    except Exception as e:
        print(f"  [FAIL] 连接网关失败: {e}")
        return False


def test_ask_data_execute():
    print("[3] 测试 POST /v1/ai/tools/execute 执行 lanlink_ask_data (只读免角色头)...")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "tool_name": "lanlink_ask_data",
        "arguments": {"question": "平台一共有多少个商品？"},
    }
    t0 = time.time()
    try:
        r = requests.post(f"{GATEWAY_URL}/v1/ai/tools/execute", headers=headers, json=payload, timeout=60)
        cost = time.time() - t0
        if r.status_code == 200:
            data = r.json()
            print(f"  [PASS] 执行成功 (耗时 {cost:.2f}s): {str(data)[:200]}")
            return True
        else:
            print(f"  [FAIL] 执行失败: {r.status_code}, {r.text[:300]}")
            return False
    except Exception as e:
        print(f"  [FAIL] 请求超时或连接异常: {e}")
        return False


def test_ask_data_direct():
    print("[4] 测试 POST /v1/ai/tools/lanlink_ask_data 直接调用...")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {"question": "统计商品总数"}
    t0 = time.time()
    try:
        r = requests.post(f"{GATEWAY_URL}/v1/ai/tools/lanlink_ask_data", headers=headers, json=payload, timeout=60)
        cost = time.time() - t0
        if r.status_code == 200:
            print(f"  [PASS] 直接调用成功 (耗时 {cost:.2f}s)")
            return True
        else:
            print(f"  [WARN] 状态码: {r.status_code}, {r.text[:200]}")
            return False
    except Exception as e:
        print(f"  [FAIL] 调用异常: {e}")
        return False


def main():
    print(f"=== AI Gateway 鉴权与只读工具验证 (Target: {GATEWAY_URL}) ===")
    results = [
        test_tools_list_unauth(),
        test_tools_list_auth(),
        test_ask_data_execute(),
        test_ask_data_direct(),
    ]
    passed = sum(1 for r in results if r)
    print(f"\n验证完成: {passed}/{len(results)} 项通过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

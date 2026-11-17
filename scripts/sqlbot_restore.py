"""恢复 SQLBot 的 AI 模型与数据源配置。

为什么需要这个脚本
------------------
SQLBot 的配置存在自己的 PG 里（bind mount 到 WSL `/root/data/postgresql`）。
每次重建容器或卷丢了，模型（ai_model）和数据源（datasource）就会清空，
导致前端问数报「请先配置模型」「数据源不可用」。
这个脚本通过 SQLBot 自身的 REST API 一键把配置补齐，不依赖手动点 UI。
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
import requests

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives import padding as sym_padding
    _HAS_CRYPTO = True
except ImportError:
    _HAS_CRYPTO = False

from sqlbot_client import SqlbotClient

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

BASE = "http://127.0.0.1:8000"
AES_KEY = b"SQLBot1234567890"
ROOT = Path(__file__).resolve().parent.parent


def aes_encrypt(plain: str) -> str:
    """AES-128-ECB + PKCS7 + base64。

    为什么不能复用 `sqlbot_encrypt`：那个是 RSA-2048 单块加密，上限 245 字节；
    数据源配置 JSON（含 host/port/password 等）有 300+ 字节，必须用这个。
    """
    if not _HAS_CRYPTO:
        raise RuntimeError("需要 cryptography 库：pip install cryptography")
    padder = sym_padding.PKCS7(128).padder()
    data = padder.update(plain.encode("utf-8")) + padder.finalize()
    cipher = Cipher(algorithms.AES(AES_KEY), modes.ECB())
    enc = cipher.encryptor()
    return base64.b64encode(enc.update(data) + enc.finalize()).decode()


def read_env(name: str) -> str:
    try:
        with open(os.path.join(ROOT, ".env"), "r", encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                m = re.match(r"^" + re.escape(name) + r"=(.*)$", line.strip())
                if m:
                    return m.group(1).strip()
    except FileNotFoundError:
        pass
    return os.environ.get(name, "").strip()


def api(method: str, path: str, token: str, body: dict | None = None, timeout: int = 30) -> tuple[int, dict]:
    req = requests.request(
        method,
        f"{BASE}/api/v1{path}",
        headers={
            "X-SQLBOT-TOKEN": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        json=body,
        timeout=timeout,
    )
    try:
        return req.status_code, req.json()
    except ValueError:
        return req.status_code, {"raw": req.text[:300]}


def _as_list(body: dict | list) -> list[dict]:
    if isinstance(body, list):
        return body
    if isinstance(body, dict):
        data = body.get("data")
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            for key in ("records", "list", "items", "data"):
                if isinstance(data.get(key), list):
                    return data[key]
    return []


def list_models(token: str) -> list[dict]:
    st, body = api("GET", "/system/aimodel", token)
    return _as_list(body)


def list_datasources(token: str) -> list[dict]:
    st, body = api("GET", "/datasource/list", token)
    return _as_list(body)


def create_model(token: str) -> bool:
    """建一个 OpenAI 兼容模型，指向 ai-gateway（SQLBot 的 Text-to-SQL 也走网关）。"""
    payload = {
        "name": "lanlink-gateway",
        "protocol": "OPENAI",
        "supplier": "OPENAI",
        "api_domain": "http://lanlink-ai-gateway:8090/v1",
        "api_key": read_env("SQLBOT_GATEWAY_KEY") or read_env("AI_GATEWAY_KEY"),
        "model_type": "LLM",
        "base_model": read_env("AI_GATEWAY_TEXT_MODEL") or "qwen2.5-7b",
        "default_model": True,
        "config_list": [
            {"key": "extra_body", "val": json.dumps({"use_kb": False})},
            {"key": "default_headers", "val": json.dumps({"Cache-Control": "no-cache"})},
        ],
        "status": 1,
    }
    st, body = api("POST", "/system/aimodel", token, payload)
    print(f"  POST /system/aimodel -> {st}")
    if st != 200:
        print(f"    {body}")
    return st == 200


def create_datasource(token: str) -> bool:
    """建 MySQL 数据源；configuration 必须**整体** AES 加密（不是只加密密码）。"""
    cfg = {
        "host": "mysql",
        "port": 3306,
        "username": read_env("SQLBOT_DB_USER") or "sqlbot_ro",
        "password": read_env("SQLBOT_DB_PASSWORD") or read_env("MYSQL_ROOT_PASSWORD"),
        "database": "lanlink_shopping",
        "charset": "utf8mb4",
        "extraJdbc": "",
        "dbSchema": "",
        "mode": "readOnly",
        "timeout": 30,
        "lowVersion": False,
    }
    payload = {
        "name": "lanlink-shopping",
        "description": "LANLinkShopping 业务库（只读）",
        "type": "mysql",
        "configuration": aes_encrypt(json.dumps(cfg)),
        "sheets": [],
    }
    st, body = api("POST", "/datasource/add", token, payload)
    print(f"  POST /datasource/add -> {st}")
    if st != 200:
        print(f"    {body}")
    return st == 200


def update_datasource(token: str, ds: dict) -> bool:
    """修正已存在数据源的配置（例如 extraJdbc 带前导 `?` 的旧记录）。

    为什么需要单独一个函数：`create_datasource` 只在「数据源不存在」时被调用；
    若之前用错误配置建过一次，就必须走 `/datasource/update` 覆盖，否则改不上。
    """
    cfg = {
        "host": "mysql",
        "port": 3306,
        "username": read_env("SQLBOT_DB_USER") or "sqlbot_ro",
        "password": read_env("SQLBOT_DB_PASSWORD") or read_env("MYSQL_ROOT_PASSWORD"),
        "database": "lanlink_shopping",
        "charset": "utf8mb4",
        "extraJdbc": "",
        "dbSchema": "",
        "mode": "readOnly",
        "timeout": 30,
        "lowVersion": False,
    }
    payload = {
        "id": ds["id"],
        "name": ds.get("name") or "lanlink-shopping",
        "description": ds.get("description") or "LANLinkShopping 业务库（只读）",
        "type": "mysql",
        "configuration": aes_encrypt(json.dumps(cfg)),
        "sheets": [],
    }
    st, body = api("POST", "/datasource/update", token, payload)
    print(f"  POST /datasource/update -> {st}")
    if st != 200:
        print(f"    {body}")
    return st == 200


def bind_tables(token: str, ds_id: int) -> bool:
    """把业务表加入问数范围。

    坑：`getTables` 返回驼峰 `tableName`，`chooseTables` 要 snake_case `table_name`；
    用驼峰会让 `table_name` 落库成空串——静默失败，表选了但模型全当没看见。
    """
    st, body = api("POST", f"/datasource/getTables/{ds_id}", token, {})
    print(f"  getTables -> {st} {len(_as_list(body))} 张表")
    tables = _as_list(body)
    if not tables:
        print("  getTables 返回空，跳过绑表")
        return False
    chosen = []
    for t in tables:
        chosen.append({
            "table_name": t.get("tableName") or t.get("table_name"),
            "table_comment": t.get("tableComment") or t.get("table_comment") or "",
            "checked": True,
        })
    st, body = api("POST", f"/datasource/chooseTables/{ds_id}", token, chosen)
    print(f"  POST /datasource/chooseTables/{ds_id} -> {st} ({len(chosen)} 张表)")
    if st != 200:
        print(f"    {body}")
    return st == 200


def wait_embeddings(timeout_sec: int = 120, interval: int = 5) -> bool:
    """等 `core_table.embedding` 全部就绪。

    为什么必须等：`chooseTables` 在写表结构的同时会调 `invalidate_metadata()`，
    把 155 张表的 `embedding` 全部置 NULL，然后异步发网关重算；
    若此时立刻提问，表选择就会因为没有 embedding 而把 155 张表全塞给模型，
    prompt 瞬间膨胀到 73k tokens，直接把 llama.cpp 打出 400 exceed_context_size_error。
    """
    print("等待表 embedding 计算完成（chooseTables 会先置 NULL 再异步重算）…")
    deadline = time.time() + timeout_sec
    last = -1
    while time.time() < deadline:
        try:
            out = subprocess.check_output(
                [
                    "docker", "exec", "shopping-sqlbot-1",
                    "psql", "-U", "dataease", "-d", "sqlbot", "-t", "-A", "-c",
                    "SELECT COUNT(*) FROM core_table WHERE embedding IS NULL;",
                ],
                stderr=subprocess.DEVNULL,
                text=True,
            ).strip()
            missing = int(out)
            if missing == 0:
                print("  embedding 已就绪（0 张表缺失）")
                return True
            if missing != last:
                print(f"  仍有 {missing} 张表缺 embedding，{interval}s 后重试…")
                last = missing
        except Exception as exc:
            print(f"  查询失败（忽略，继续等）: {exc}")
        time.sleep(interval)
    print(f"  超时（{timeout_sec}s）仍有 {last} 张表缺 embedding；此时提问可能因 prompt 超长而失败，建议稍后重试")
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description="恢复/校验 SQLBot 配置")
    ap.add_argument("--verify", action="store_true", help="只校验现状，不写入")
    args = ap.parse_args()

    client = SqlbotClient(base_url=BASE)
    client.login()
    token = client.token
    print(f"登录成功 token_len={len(token)}")

    models = list_models(token)
    datasources = list_datasources(token)
    print(f"现状：模型 {len(models)} 个，数据源 {len(datasources)} 个")

    if args.verify:
        ok = bool(models) and bool(datasources)
        print(f"校验结果：{'正常' if ok else '缺失，需要执行恢复'}")
        return 0 if ok else 1

    if not models:
        print("建 AI 模型…")
        if not create_model(token):
            return 1
        models = list_models(token)
        print(f"  现有模型 {len(models)} 个")
    else:
        print("模型已存在，跳过")

    if not datasources:
        print("建数据源…")
        if not create_datasource(token):
            return 1
        datasources = list_datasources(token)
        print(f"  现有数据源 {len(datasources)} 个")
    else:
        print("数据源已存在，跳过")

    if datasources:
        ds_id = int(datasources[0].get("id"))
        print(f"修正数据源配置（ds_id={ds_id}）…")
        update_datasource(token, datasources[0])
        print(f"绑定业务表（ds_id={ds_id}）…")
        bind_tables(token, ds_id)
        wait_embeddings()

    print("\n最终状态：")
    print(f"  模型 {len(list_models(token))} 个")
    print(f"  数据源 {len(list_datasources(token))} 个")
    return 0


if __name__ == "__main__":
    sys.exit(main())

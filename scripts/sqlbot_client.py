"""SQLBot 客户端：登录 → 建会话 → 问数（SSE 流式）→ 解析 SQL 与结果。

为什么要有这个脚本：
SQLBot 的问答不是 WebSocket，而是 **SSE（text/event-stream）**。
这一点无论文档还是名字都极具误导性（很容易误以为像 chatgpt 那样用 ws 或单纯的 POST 一问一答）。
不走 SSE 就拿不到后端分步吐出来的 `sql-result`、`brief`、`sql` 事件。

用法：
  python scripts/sqlbot_client.py --question "平台一共有多少个商品？"
  python scripts/sqlbot_client.py --question "订单表里一共有多少条订单？" --json
"""

import argparse
import base64
import json
import os
import sys
from pathlib import Path
import requests
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

_PUBLIC_KEY_PEM: str | None = None


def fetch_public_key(base_url: str) -> str:
    """取 SQLBot 的 RSA 公钥（`GET /system/config/key` 在白名单内，无需 token）。"""
    global _PUBLIC_KEY_PEM
    if _PUBLIC_KEY_PEM is None:
        resp = requests.get(f"{base_url.rstrip('/')}/api/v1/system/config/key", timeout=15)
        resp.raise_for_status()
        _PUBLIC_KEY_PEM = resp.json()["data"]["public_key"]
    return _PUBLIC_KEY_PEM


def sqlbot_encrypt(plain: str, base_url: str) -> str:
    """按 SQLBot 的登录方式加密：RSA-2048 + PKCS#1 v1.5 + base64。

    **这不是 AES**。我最初按前端 bundle 里的 `AES.encrypt(..., "SQLBot1234567890")` 试了半天一直 401，
    后来抓包才发现登录用的不是 AES，而是 `/system/config/key` 下发的 RSA 公钥。
    AES 只用在数据源配置的加密上（见 `sqlbot_restore.py`）。
    """
    pem = fetch_public_key(base_url)
    pub = serialization.load_pem_public_key(pem.encode())
    cipher = pub.encrypt(plain.encode("utf-8"), padding.PKCS1v15())
    return base64.b64encode(cipher).decode()


def _env_value(name: str) -> str:
    """读取根 .env，避免脚本把密码硬编码成镜像默认值。"""
    import os
    from pathlib import Path
    root = Path(__file__).resolve().parent.parent
    try:
        for line in (root / ".env").read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if line.startswith(f"{name}="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return os.environ.get(name, "")


class SqlbotClient:
    def __init__(self, base_url: str = "http://127.0.0.1:8000", account: str | None = None, password: str | None = None):
        self.base = base_url.rstrip("/")
        self.account = account or _env_value("SQLBOT_USERNAME") or "admin"
        self.password = password or _env_value("SQLBOT_PASSWORD")
        self.token: str | None = None
        self.session = requests.Session()

    def login(self) -> str:
        """登录拿 JWT。

        注意：必须用 form 编码（OAuth2PasswordRequestForm），
        且 username/password 都要先用 RSA 公钥加密。
        """
        resp = self.session.post(
            f"{self.base}/api/v1/login/access-token",
            data={
                "username": sqlbot_encrypt(self.account, self.base),
                "password": sqlbot_encrypt(self.password, self.base),
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=30,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise RuntimeError(f"登录失败: {body}")
        self.token = body["data"]["access_token"]
        return self.token

    def _headers(self) -> dict[str, str]:
        if not self.token:
            raise RuntimeError("未登录，先调用 login()")
        return {
            "X-SQLBOT-TOKEN": f"Bearer {self.token}",
            "Content-Type": "application/json",
        }

    def start_chat(self, datasource_id: int = 2, question: str = "") -> int:
        """创建会话，返回 chat_id。"""
        resp = self.session.post(
            f"{self.base}/api/v1/chat/start",
            headers=self._headers(),
            json={"datasource": datasource_id, "question": question, "origin": "chat"},
            timeout=30,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise RuntimeError(f"创建会话失败: {body}")
        return int(body["data"]["id"])

    def ask(self, chat_id: int, question: str) -> list[dict]:
        """提问并收集 SSE 事件。

        服务端返回 text/event-stream，每行形如 `data: {...}`；
        逐行解析而不是一次性读全部，避免长回答时阻塞。
        """
        events: list[dict] = []
        resp = self.session.post(
            f"{self.base}/api/v1/chat/question",
            headers={**self._headers(), "Accept": "text/event-stream"},
            json={"chat_id": chat_id, "question": question},
            stream=True,
            timeout=120,
        )
        resp.raise_for_status()
        with resp:
            for raw in resp.iter_lines():
                if not raw:
                    continue
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    break
                try:
                    events.append(json.loads(payload))
                except Exception:
                    events.append({"raw": payload})
        return events

    def fetch_record_data(self, record_id: int) -> list[dict]:
        """拉取某个回答的真实数据行。

        为什么必须单独拉：`/chat/question` 的 SSE 流只推「SQL + 图表定义」，
        不推数据行。数据要再调 `GET /chat/record/{id}/data` 才拿得到。
        这一点不看源码根本想不到。
        """
        resp = self.session.get(
            f"{self.base}/api/v1/chat/record/{record_id}/data",
            headers=self._headers(),
            timeout=120,
        )
        resp.raise_for_status()
        body = resp.json()
        payload = body.get("data") if isinstance(body, dict) else body
        if isinstance(payload, dict):
            rows = payload.get("data")
            if isinstance(rows, list):
                return rows
            return []
        elif isinstance(payload, list):
            return payload
        return []


def summarize(events: list[dict]) -> dict:
    """从事件流里抽出 SQL、结果行数、结论。

    事件类型（实测）：
      - `type=sql-result`：`content` 是**被切片**的 JSON 字符串，需要拼起来再解析；
      - `type=sql`：`content` 是完整的最终 SQL（最可靠）；
      - `type=brief`：`brief` 字段是自然语言结论；
      - `type=sql-data`：`data` 字段可能附带预览行。
    """
    sql = ""
    brief = ""
    rows: list = []
    columns: list = []
    result_json = ""
    record_id = 0

    for ev in events:
        etype = ev.get("type")
        if etype == "id":
            if ev.get("id") and not record_id:
                record_id = int(ev["id"])
        elif etype == "sql":
            v = ev.get("content")
            if isinstance(v, str) and v.strip():
                sql = v
        elif etype == "brief":
            v = ev.get("brief")
            if isinstance(v, str) and v.strip():
                brief = v
        elif etype == "sql-result":
            v = ev.get("content")
            if isinstance(v, str):
                result_json += v
        elif etype == "sql-data":
            if isinstance(ev.get("data"), list):
                rows = ev["data"]
            if isinstance(ev.get("columns"), list):
                columns = ev["columns"]

    if result_json:
        try:
            parsed = json.loads(result_json)
            sql = sql or parsed.get("sql") or ""
            brief = brief or parsed.get("brief") or ""
        except json.JSONDecodeError:
            pass

    return {
        "sql": sql,
        "rowCount": len(rows),
        "rows": rows[:5],
        "columns": columns,
        "text": brief,
        "recordId": record_id,
    }


def main() -> None:
    p = argparse.ArgumentParser(description="SQLBot 问数测试客户端")
    p.add_argument("--base-url", default="http://127.0.0.1:8000")
    p.add_argument("--account", default=None)
    p.add_argument("--password", default=None)
    p.add_argument("--datasource", type=int, default=2)
    p.add_argument("--question", default="订单表里一共有多少条订单？")
    p.add_argument("--json", action="store_true", help="打印完整事件流")
    args = p.parse_args()

    c = SqlbotClient(base_url=args.base_url, account=args.account, password=args.password)
    c.login()
    print(f"[1] 登录成功 token_len={len(c.token)}")

    chat_id = c.start_chat(datasource_id=args.datasource, question=args.question)
    print(f"[2] 会话已建 chat_id={chat_id}")

    events = c.ask(chat_id, args.question)
    print(f"[3] 收到 {len(events)} 个事件")

    if args.json:
        print(json.dumps(events, ensure_ascii=False, indent=2))

    s = summarize(events)
    if s.get("recordId") and not s.get("rows"):
        try:
            data = c.fetch_record_data(s["recordId"])
            s["rows"] = data[:5]
            s["rowCount"] = len(data)
        except Exception as e:
            print(f"[!] 拉取数据失败: {e}")

    print(f"[4] SQL:\n{s.get('sql') or '<未提取到>'}")
    print(f"[5] 结论: {s.get('text') or '<无>'}")
    print(f"[6] 结果行数: {s.get('rowCount')}")
    if s.get("columns"):
        print(f"[7] 列: {s.get('columns')}")
    if s.get("rows"):
        print(f"[8] 首行: {json.dumps(s['rows'][0], ensure_ascii=False)[:300]}")


if __name__ == "__main__":
    main()

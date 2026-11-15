#!/usr/bin/env python3
"""Alertmanager -> 飞书自建应用机器人 送达适配器。

配置经 compose configs 渲染为 /etc/lanlink-feishu/config.env（KEY=VALUE 行，
与钉钉适配器同纪律：真实凭据只存根 .env，不进仓库也不进容器环境变量）。

端点：
- POST /feishu/ops/send  接收 Alertmanager webhook JSON（alerts[]）拼一条飞书 text 消息；
  也接受 {"text": "..."} 透传（宿主机 curl 自测用）。恒回 200，避免 Alertmanager 重试风暴。
- GET  /feishu/health    自检：凭据是否配置、tenant_access_token 能否下发、机器人所在群列表
  （FEISHU_CHAT_ID 留空时用它发现 chat_id）。

飞书 API：token = POST /auth/v3/tenant_access_token/internal（expire 秒，提前 5min 刷新，
鉴权失败码 99991661/99991663 强制重取一次）；发消息 = POST /im/v1/messages?receive_id_type=chat_id，
content 是 JSON 字符串；群列表 = GET /im/v1/chats（仅机器人所在群）。
"""

import json
import os
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_PATH = os.environ.get("FEISHU_CONFIG_PATH", "/etc/lanlink-feishu/config.env")
LISTEN_PORT = int(os.environ.get("FEISHU_ADAPTER_PORT", "8061"))
FEISHU_BASE = "https://open.feishu.cn/open-apis"
HTTP_TIMEOUT = 15
# token 提前失效窗口：到期前 5 分钟视为过期，避免边界请求撞上残余秒数
TOKEN_EARLY_EXPIRE = 300
# tenant_access_token 鉴权类错误码：命中则强刷 token 重试一次
AUTH_ERROR_CODES = {99991661, 99991663, 99991668}

TZ = timezone(timedelta(hours=8))

_config = {}
_token_lock = threading.Lock()
_token = {"value": None, "expires_at": 0.0}


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            _config[key.strip()] = value.strip()


def _http_json(method, url, body=None, token=None):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_token(force=False):
    with _token_lock:
        now = time.time()
        if not force and _token["value"] and now < _token["expires_at"]:
            return _token["value"]
        resp = _http_json(
            "POST",
            FEISHU_BASE + "/auth/v3/tenant_access_token/internal",
            body={"app_id": _config.get("FEISHU_APP_ID", ""), "app_secret": _config.get("FEISHU_APP_SECRET", "")},
        )
        if resp.get("code") != 0:
            raise RuntimeError("tenant_access_token failed: code=%s msg=%s" % (resp.get("code"), resp.get("msg")))
        _token["value"] = resp["tenant_access_token"]
        _token["expires_at"] = now + int(resp.get("expire", 7200)) - TOKEN_EARLY_EXPIRE
        return _token["value"]


def list_chats(token):
    resp = _http_json("GET", FEISHU_BASE + "/im/v1/chats?page_size=50", token=token)
    if resp.get("code") != 0:
        raise RuntimeError("list chats failed: code=%s msg=%s" % (resp.get("code"), resp.get("msg")))
    return [{"chat_id": it.get("chat_id"), "name": it.get("name", "")} for it in resp.get("data", {}).get("items", [])]


def send_text(receive_id, text):
    body = {
        "receive_id": receive_id,
        "msg_type": "text",
        "content": json.dumps({"text": text}, ensure_ascii=False),
    }
    token = get_token()
    resp = _http_json("POST", FEISHU_BASE + "/im/v1/messages?receive_id_type=chat_id", body=body, token=token)
    if resp.get("code") in AUTH_ERROR_CODES:
        token = get_token(force=True)
        resp = _http_json("POST", FEISHU_BASE + "/im/v1/messages?receive_id_type=chat_id", body=body, token=token)
    if resp.get("code") != 0:
        raise RuntimeError("send failed: code=%s msg=%s" % (resp.get("code"), resp.get("msg")))
    return resp.get("data", {}).get("message_id", "")


def fmt_ts(value):
    if not value:
        return "-"
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(TZ).strftime("%m-%d %H:%M")
    except ValueError:
        return value


def format_alerts(payload):
    alerts = payload.get("alerts", [])
    if not alerts:
        return None
    lines = []
    for alert in alerts:
        labels = alert.get("labels", {})
        annos = alert.get("annotations", {})
        status = alert.get("status", "firing")
        head = "【LANLink 告警·触发】" if status == "firing" else "【LANLink 告警·已恢复】"
        lines.append(
            "%s %s\n服务: %s  级别: %s\n%s\n开始: %s  结束: %s"
            % (
                head,
                labels.get("alertname", "unknown"),
                labels.get("service", labels.get("job", "-")),
                labels.get("severity", "-"),
                annos.get("description", annos.get("summary", "-")),
                fmt_ts(alert.get("startsAt")),
                fmt_ts(alert.get("endsAt")),
            )
        )
    return "\n\n".join(lines)


class Handler(BaseHTTPRequestHandler):
    def _reply(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/feishu/health":
            self._reply({"ok": False, "error": "not found"}, 404)
            return
        result = {
            "app_id_configured": bool(_config.get("FEISHU_APP_ID")),
            "app_secret_configured": bool(_config.get("FEISHU_APP_SECRET")),
            "chat_id_configured": _config.get("FEISHU_CHAT_ID", ""),
        }
        try:
            token = get_token()
            result["token_ok"] = True
            result["chats"] = list_chats(token)
            if not result["chats"]:
                result["hint"] = "机器人尚未加入任何群：在告警群 群设置→群机器人→添加应用机器人，然后重试本端点"
        except Exception as exc:  # noqa: BLE001 自检端点吞异常只报事实
            result["token_ok"] = False
            result["error"] = str(exc)
        self._reply(result)

    def do_POST(self):
        if self.path != "/feishu/ops/send":
            self._reply({"ok": False, "error": "not found"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8")) if length else {}
            text = payload.get("text") if isinstance(payload.get("text"), str) else format_alerts(payload)
            if not text:
                self._reply({"ok": False, "error": "no alerts and no text in payload"})
                return
            chat_id = _config.get("FEISHU_CHAT_ID", "")
            if not chat_id:
                chats = list_chats(get_token())
                print("[feishu-adapter] FEISHU_CHAT_ID 未配置，机器人所在群: %s" % json.dumps(chats, ensure_ascii=False), flush=True)
                self._reply({"ok": False, "error": "FEISHU_CHAT_ID not set", "chats": chats})
                return
            message_id = send_text(chat_id, text)
            print("[feishu-adapter] delivered message_id=%s" % message_id, flush=True)
            self._reply({"ok": True, "message_id": message_id})
        except Exception as exc:  # noqa: BLE001 告警通道失败不应 5xx 触发 Alertmanager 重试风暴
            print("[feishu-adapter] send error: %s" % exc, flush=True)
            self._reply({"ok": False, "error": str(exc)})

    def log_message(self, fmt, *args):  # noqa: A003 保留默认访问日志但走 stdout 单行
        print("[feishu-adapter] %s" % (fmt % args), flush=True)


if __name__ == "__main__":
    load_config()
    print(
        "[feishu-adapter] start port=%d app_id=%s chat_id=%s"
        % (LISTEN_PORT, _config.get("FEISHU_APP_ID", "") or "(empty)", _config.get("FEISHU_CHAT_ID", "") or "(empty)"),
        flush=True,
    )
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler).serve_forever()

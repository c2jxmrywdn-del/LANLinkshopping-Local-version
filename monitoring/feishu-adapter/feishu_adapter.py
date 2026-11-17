#!/usr/bin/env python3
"""Alertmanager -> 飞书自建应用机器人 送达适配器。

配置经 compose configs 渲染为 /etc/lanlink-feishu/config.env（KEY=VALUE 行，
与钉钉适配器同纪律：真实凭据只存根 .env，不进仓库也不进容器环境变量）。

端点：
- POST /feishu/ops/send  接收 Alertmanager webhook JSON（alerts[]）拼一条飞书 text 消息；
  也接受 {"text": "..."} 透传（宿主机 curl 自测用）。恒回 200，避免 Alertmanager 重试风暴。
- GET  /feishu/health    自检：凭据是否配置、tenant_access_token 能否下发（只返回布尔值，
  不再回显机器人所在群列表——该端点无认证，回显 chat_id/群名等于泄露内部群拓扑）。
  `/feishu/health?chats=1` 在严格模式（配置了 ALERT_ADAPTER_TOKEN）且带正确
  `X-Adapter-Token` 时才会列出机器人所在群，供运维一次性查 chat_id。

入站鉴权（两层，均为 fail-closed）：
1. 配置了 ALERT_ADAPTER_TOKEN（config.env 或容器环境变量）时，POST 必须带
   `X-Adapter-Token: <token>`，常量时间比较；缺失或不匹配一律 401。
2. 未配置 token 时为「降级模式」：只接受来自容器网段（默认 172.19.0.0/16）的 POST，
   宿主机回环/桥接网关（172.19.0.1）与其它来源一律 401。这样 Alertmanager 仍能送达，
   而本机任意进程无法再凭空伪造告警外发。生产应配置 token 走严格模式。

飞书 API：token = POST /auth/v3/tenant_access_token/internal（expire 秒，提前 5min 刷新，
鉴权失败码 99991661/99991663 强制重取一次）；发消息 = POST /im/v1/messages?receive_id_type=chat_id，
content 是 JSON 字符串；群列表 = GET /im/v1/chats（仅机器人所在群）。
"""

import json
import os
import secrets
import ipaddress
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

# 降级模式（未配置 token）下允许的调用网段：Alertmanager 容器走 shopping_default
DEFAULT_TRUSTED_CIDRS = "172.19.0.0/16"
# 降级模式下显式拒绝的来源：Docker 桥接网关 = 宿主机 127.0.0.1 进入时的 remoteAddr
DEFAULT_DENIED_CIDRS = "172.19.0.1/32"
# 健康检查例外：宿主机运维要能 curl 127.0.0.1:8061/feishu/health，
# 而端口映射后 remoteAddr 是桥接网关。health 只回布尔值，放开它不泄露群/凭据。
DEFAULT_HEALTH_ALLOWED_CIDRS = "172.19.0.1/32"

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


def adapter_token():
    """入站共享密钥：优先取 config.env 渲染值，其次容器环境变量。

    留空 = 关闭写入通道（健康检查仍可用），不允许"无密钥放行"。
    每次请求都读，便于运维改 config.env 后只重启容器即可生效。
    """
    return (_config.get("ALERT_ADAPTER_TOKEN") or os.environ.get("ALERT_ADAPTER_TOKEN") or "").strip()


def _cidrs(name, default):
    raw = (_config.get(name) or os.environ.get(name) or default).strip()
    nets = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        try:
            nets.append(ipaddress.ip_network(item, strict=False))
        except ValueError:
            print("[feishu-adapter] 忽略非法 CIDR: %s" % item, flush=True)
    return nets


def source_allowed(remote_addr):
    """降级模式的来源判定：命中白名单且不在拒绝名单内才放行。"""
    try:
        ip = ipaddress.ip_address((remote_addr or "").replace("::ffff:", ""))
    except ValueError:
        return False
    if any(ip in net for net in _cidrs("ALERT_ADAPTER_DENIED_CIDRS", DEFAULT_DENIED_CIDRS)):
        return False
    return any(ip in net for net in _cidrs("ALERT_ADAPTER_ALLOWED_CIDRS", DEFAULT_TRUSTED_CIDRS))


def health_source_allowed(remote_addr):
    """health 专用：回环 + 容器网段 + Docker 桥接网关（仅布尔状态，无敏感数据）。"""
    try:
        ip = ipaddress.ip_address((remote_addr or "").replace("::ffff:", ""))
    except ValueError:
        return False
    if ip.is_loopback:
        return True
    if any(ip in net for net in _cidrs("ALERT_ADAPTER_HEALTH_ALLOWED_CIDRS", DEFAULT_HEALTH_ALLOWED_CIDRS)):
        return True
    return source_allowed(remote_addr)


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
    def _authorized(self):
        """严格模式校验 token；降级模式校验来源网段。两者都不满足即拒绝。"""
        expected = adapter_token()
        if expected:
            supplied = self.headers.get("X-Adapter-Token", "")
            return secrets.compare_digest(supplied, expected)
        allowed = source_allowed(self.client_address[0] if self.client_address else "")
        if not allowed:
            print(
                "[feishu-adapter] 降级模式拒绝来源 %s（未配置 ALERT_ADAPTER_TOKEN）"
                % (self.client_address[0] if self.client_address else "unknown"),
                flush=True,
            )
        return allowed

    def _reply(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path, _, query = (self.path or "").partition("?")
        if path != "/feishu/health":
            self._reply({"ok": False, "error": "not found"}, 404)
            return
        # 健康检查只给本机/容器网段/桥接网关看：只回布尔状态，不含群与凭据
        remote = self.client_address[0] if self.client_address else ""
        if not health_source_allowed(remote):
            self._reply({"ok": False, "error": "unauthorized"}, 401)
            return
        result = {
            "app_id_configured": bool(_config.get("FEISHU_APP_ID")),
            "app_secret_configured": bool(_config.get("FEISHU_APP_SECRET")),
            "chat_id_configured": bool(_config.get("FEISHU_CHAT_ID")),
            "inbound_token_configured": bool(adapter_token()),
        }
        try:
            get_token()
            result["token_ok"] = True
            if not result["chat_id_configured"]:
                result["hint"] = "FEISHU_CHAT_ID 未配置：把应用机器人拉进告警群后，用一次性 token 排查 chat_id"
            if "chats=1" in query:
                # 群列表只对持有入站 token 的调用方开放；降级模式没有 token，一律不返回
                expected = adapter_token()
                supplied = self.headers.get("X-Adapter-Token", "")
                if expected and secrets.compare_digest(supplied, expected):
                    result["chats"] = list_chats(get_token())
                else:
                    result["chats_error"] = "需要 ALERT_ADAPTER_TOKEN 与 X-Adapter-Token 才能列出群"
        except Exception as exc:  # noqa: BLE001 自检端点吞异常只报事实
            result["token_ok"] = False
            result["error"] = str(exc)
        self._reply(result)

    def do_POST(self):
        path, _, _ = (self.path or "").partition("?")
        if path != "/feishu/ops/send":
            self._reply({"ok": False, "error": "not found"}, 404)
            return
        if not self._authorized():
            self._reply({"ok": False, "error": "unauthorized"}, 401)
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
                # 不再把群列表回给调用方：该信息只应在运维排障时一次性查看
                print("[feishu-adapter] FEISHU_CHAT_ID 未配置，告警未发送", flush=True)
                self._reply({"ok": False, "error": "FEISHU_CHAT_ID not set"})
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
        "[feishu-adapter] start port=%d app_id=%s chat_id=%s inbound_token=%s"
        % (
            LISTEN_PORT,
            _config.get("FEISHU_APP_ID", "") or "(empty)",
            _config.get("FEISHU_CHAT_ID", "") or "(empty)",
            "set" if adapter_token() else "MISSING(写入通道已拒绝)",
        ),
        flush=True,
    )
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler).serve_forever()

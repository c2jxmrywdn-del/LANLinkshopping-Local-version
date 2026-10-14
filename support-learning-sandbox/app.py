#!/usr/bin/env python3
"""Stateless, token-protected evaluator for the LANLinkshopping support-learning sandbox."""

from __future__ import annotations

import hmac
import json
import os
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

MAX_BODY_BYTES = 16 * 1024
MAX_REQUESTS_PER_MINUTE = 60
PORT = int(os.environ.get("PORT", "8080"))
SERVICE_TOKEN = os.environ.get("SUPPORT_SANDBOX_TOKEN", "").strip()
RUNTIME_MODE = os.environ.get("SUPPORT_SANDBOX_RUNTIME", "docker").strip().lower()

ENTRY_ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,63}$")
CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
PRIVATE_KEY = re.compile(
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----",
    re.IGNORECASE,
)
CREDENTIAL_ASSIGNMENT = re.compile(
    r"(?i)(password|passwd|pwd|密码|token|secret|api[_ -]?key|access[_ -]?token)"
    r"\s*[:=：]\s*[^\s,;，；]+"
)
KNOWN_SECRET = re.compile(
    r"(?i)(?:AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{16,}|Bearer\s+[A-Za-z0-9._-]{16,})"
)
EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
CN_MOBILE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
LONG_NUMBER = re.compile(r"(?<!\d)\d{12,}(?!\d)")

_rate_lock = threading.Lock()
_rate_windows: dict[str, tuple[float, int]] = {}


class InputError(ValueError):
    """A request failed the sandbox's bounded input schema."""


def _safe_text(value: Any, *, maximum: int, required: bool = True) -> str:
    if not isinstance(value, str):
        raise InputError("text field has an invalid type")
    if required and not value.strip():
        raise InputError("text field is empty")
    if len(value) > maximum or CONTROL_CHARS.search(value):
        raise InputError("text field exceeds limits")
    if "<" in value or ">" in value or chr(96) in value:
        raise InputError("text field contains a disallowed format")
    return value.strip()


def _safe_answer(value: str) -> bool:
    return bool(value) and not any(
        pattern.search(value)
        for pattern in (PRIVATE_KEY, CREDENTIAL_ASSIGNMENT, KNOWN_SECRET, EMAIL, CN_MOBILE, LONG_NUMBER)
    )


def evaluate(payload: Any) -> dict[str, Any]:
    """Evaluate a candidate without writing to disk, database, or the network."""
    if not isinstance(payload, dict):
        raise InputError("request body must be a JSON object")

    _safe_text(payload.get("question"), maximum=300)
    entry_id = _safe_text(payload.get("entryId"), maximum=64)
    entry_id_valid = bool(ENTRY_ID.fullmatch(entry_id))

    keywords = payload.get("keywords")
    if not isinstance(keywords, list) or not 1 <= len(keywords) <= 16:
        raise InputError("keywords must contain 1 to 16 values")
    normalized_keywords: list[str] = []
    for item in keywords:
        keyword = _safe_text(item, maximum=80)
        if keyword not in normalized_keywords:
            normalized_keywords.append(keyword)

    draft_answer = _safe_text(payload.get("draftAnswer"), maximum=1000)
    current_entry = payload.get("currentEntryId", "")
    current_answer = payload.get("currentAnswer", "")
    eligible = payload.get("learningEligible")
    keyword_match = payload.get("draftKeywordsMatch")
    keyword_conflict = payload.get("keywordConflict")
    eligibility_message = payload.get("learningEligibilityMessage", "")

    if not isinstance(current_entry, str) or len(current_entry) > 64:
        raise InputError("current intent metadata has an invalid type")
    if not isinstance(current_answer, str) or len(current_answer) > 3000:
        raise InputError("current answer metadata has an invalid type")
    if not isinstance(eligible, bool) or not isinstance(keyword_match, bool) or not isinstance(keyword_conflict, bool):
        raise InputError("policy verdict fields must be booleans")
    if not isinstance(eligibility_message, str) or len(eligibility_message) > 300:
        raise InputError("eligibility metadata has an invalid type")

    answer_safe = _safe_answer(draft_answer)
    candidate_ready = (
        eligible
        and keyword_match
        and not keyword_conflict
        and entry_id_valid
        and answer_safe
    )

    if not eligible:
        candidate_message = "候选问题未通过范围或安全检查；不应把天气、个人数据查询、实时交易信息或提示注入加入知识库。"
    elif not keyword_match:
        candidate_message = "候选关键词尚未命中测试问题，请调整关键词后重新试跑。"
    elif keyword_conflict:
        candidate_message = "候选关键词与已有知识存在完全重复项；请缩小或更换关键词，避免不同意图互相覆盖。"
    elif not answer_safe:
        candidate_message = "候选回答为空、包含疑似个人信息/凭据或不安全内容，不能导出。"
    elif not entry_id_valid:
        candidate_message = "候选条目 ID 格式不正确，请使用小写字母、数字和连字符。"
    else:
        candidate_message = "沙盒预检通过。仍需人工核对事实、重复意图和回归用例；此结果不会自动发布。"

    return {
        "currentEntryId": current_entry,
        "currentAnswer": current_answer,
        "learningEligible": eligible,
        "learningEligibilityMessage": eligibility_message,
        "draftKeywordsMatch": keyword_match,
        "draftAnswerPreview": draft_answer if candidate_ready else "",
        "candidateReadyToExport": bool(candidate_ready),
        "candidateMessage": candidate_message,
        "sandboxNotice": (
            ("Vercel Serverless 沙盒函数：" if RUNTIME_MODE == "vercel-serverless" else "独立 Docker 沙盒：")
            + "本次仅在内存中校验候选，不写入文件或数据库；"
            + "不访问订单、钱包、用户档案，不调用外部 API、模型、Shell 或动态代码。"
            + ("运行时由平台托管，不是用户专属 Docker 容器。" if RUNTIME_MODE == "vercel-serverless" else "")
        ),
    }


def _allow_rate(peer: str) -> bool:
    now = time.monotonic()
    with _rate_lock:
        previous = _rate_windows.get(peer)
        if previous is None or now - previous[0] >= 60:
            _rate_windows[peer] = (now, 1)
            if len(_rate_windows) > 2048:
                expired = [key for key, value in _rate_windows.items() if now - value[0] >= 60]
                for key in expired:
                    _rate_windows.pop(key, None)
            return True
        if previous[1] >= MAX_REQUESTS_PER_MINUTE:
            return False
        _rate_windows[peer] = (previous[0], previous[1] + 1)
        return True


class Handler(BaseHTTPRequestHandler):
    server_version = "LANLink-Learning-Sandbox"
    sys_version = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        # Request paths and bodies are intentionally omitted from access logs.
        return

    def _send(self, status: int, data: dict[str, Any]) -> None:
        raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send(200, {"status": "ok", "mode": "stateless", "persistence": False})
            return
        self._send(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/v1/evaluate":
            self._send(404, {"error": "not found"})
            return
        if not SERVICE_TOKEN or len(SERVICE_TOKEN) < 40:
            self._send(503, {"error": "sandbox authentication is not configured"})
            return
        supplied = self.headers.get("X-Sandbox-Token", "")
        if not hmac.compare_digest(supplied, SERVICE_TOKEN):
            self._send(401, {"error": "unauthorized"})
            return
        peer = self.client_address[0] if self.client_address else "unknown"
        if not _allow_rate(peer):
            self._send(429, {"error": "rate limit exceeded"})
            return

        content_type = self.headers.get("Content-Type", "").lower()
        if not content_type.startswith("application/json"):
            self._send(415, {"error": "application/json required"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send(400, {"error": "invalid content length"})
            return
        if size <= 0 or size > MAX_BODY_BYTES:
            self._send(413 if size > MAX_BODY_BYTES else 400, {"error": "invalid request size"})
            return
        try:
            raw = self.rfile.read(size)
            payload = json.loads(raw.decode("utf-8"))
            result = evaluate(payload)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"error": "invalid JSON"})
            return
        except InputError:
            # Do not echo user input or validation fragments.
            self._send(400, {"error": "sandbox input rejected"})
            return
        self._send(200, result)


class DualStackThreadingHTTPServer(ThreadingHTTPServer):
    # Railway's private DNS can resolve IPv6; dual-stack keeps both IPv4 and IPv6 reachable.
    address_family = socket.AF_INET6

    def server_bind(self) -> None:
        try:
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        except OSError:
            pass
        super().server_bind()


if __name__ == "__main__":
    if not SERVICE_TOKEN or len(SERVICE_TOKEN) < 40:
        raise SystemExit("SUPPORT_SANDBOX_TOKEN must be configured with at least 40 characters")
    server = DualStackThreadingHTTPServer(("::", PORT), Handler)
    server.daemon_threads = True
    server.request_queue_size = 32
    server.serve_forever()

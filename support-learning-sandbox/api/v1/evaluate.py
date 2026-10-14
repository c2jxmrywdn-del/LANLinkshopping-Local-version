"""Vercel Python Serverless entrypoint for the support-learning evaluator.

This handler is intentionally stateless: it validates bounded JSON and returns a
candidate preview. It does not write to persistent storage or call external APIs.
"""
from __future__ import annotations

import hmac
import json
from http.server import BaseHTTPRequestHandler
from typing import Any

from app import MAX_BODY_BYTES, SERVICE_TOKEN, InputError, _allow_rate, evaluate


class handler(BaseHTTPRequestHandler):
    server_version = "LANLink-Serverless-Sandbox"
    sys_version = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        # Do not log request payloads, candidate answers, or credentials.
        return

    def _send(self, status: int, data: dict[str, Any]) -> None:
        raw = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        self._send(405, {"error": "method not allowed"})

    def do_POST(self) -> None:
        if not SERVICE_TOKEN or len(SERVICE_TOKEN) < 40:
            self._send(503, {"error": "sandbox authentication is not configured"})
            return

        supplied = self.headers.get("X-Sandbox-Token", "")
        if not hmac.compare_digest(supplied, SERVICE_TOKEN):
            self._send(401, {"error": "unauthorized"})
            return

        # Best-effort process-local limiting; the shared token remains the primary gate.
        peer = self.headers.get("x-real-ip", "unknown")
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
            # Deliberately avoid echoing submitted question or answer content.
            self._send(400, {"error": "sandbox input rejected"})
            return

        self._send(200, result)

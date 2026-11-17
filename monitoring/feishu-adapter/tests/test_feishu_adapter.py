"""飞书适配器入站鉴权单测。

覆盖：严格模式（token）与降级模式（来源网段）两条路径，以及 health 不泄露群列表。
不依赖网络：把 send_text / get_token 打桩，只验证鉴权与响应结构。
"""

import io
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import feishu_adapter as adapter  # noqa: E402


class _FakeSocket:
    def __init__(self, body=b""):
        self._body = io.BytesIO(body)

    def makefile(self, *args, **kwargs):
        return self._body


def call(handler_cls, method, path, headers=None, body=None, client=("172.19.0.5", 12345)):
    """直接驱动 handler，避免起真实 socket。"""
    raw = b""
    headers = dict(headers or {})
    if body is not None:
        raw = json.dumps(body).encode("utf-8")
        headers["Content-Length"] = str(len(raw))
    lines = ["%s %s HTTP/1.1" % (method, path)]
    lines += ["%s: %s" % (k, v) for k, v in headers.items()]
    request = ("\r\n".join(lines) + "\r\n\r\n").encode("utf-8") + raw
    handler = handler_cls.__new__(handler_cls)
    handler.rfile = io.BytesIO(raw)
    handler.wfile = io.BytesIO()
    handler.client_address = client
    handler.headers = {}
    for key, value in headers.items():
        handler.headers[key] = value
    handler.command = method
    handler.path = path
    handler.request_version = "HTTP/1.1"
    handler.requestline = "%s %s HTTP/1.1" % (method, path)
    handler.close_connection = True
    if method == "GET":
        handler.do_GET()
    else:
        handler.do_POST()
    out = handler.wfile.getvalue()
    return json.loads(out.split(b"\r\n\r\n", 1)[-1].decode("utf-8"))


class StrictModeTest(unittest.TestCase):
    def setUp(self):
        self._patch = mock.patch.object(adapter, "adapter_token", return_value="s3cret-token")
        self._patch.start()
        adapter._config["FEISHU_CHAT_ID"] = "oc_test"

    def tearDown(self):
        self._patch.stop()
        adapter._config.pop("FEISHU_CHAT_ID", None)

    def test_missing_token_rejected(self):
        result = call(adapter.Handler, "POST", "/feishu/ops/send", body={"text": "hi"})
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "unauthorized")

    def test_wrong_token_rejected(self):
        result = call(
            adapter.Handler,
            "POST",
            "/feishu/ops/send",
            headers={"X-Adapter-Token": "wrong"},
            body={"text": "hi"},
        )
        self.assertEqual(result["error"], "unauthorized")

    def test_correct_token_accepted(self):
        with mock.patch.object(adapter, "send_text", return_value="om_1") as sender:
            result = call(
                adapter.Handler,
                "POST",
                "/feishu/ops/send",
                headers={"X-Adapter-Token": "s3cret-token"},
                body={"text": "hi"},
            )
        self.assertTrue(result["ok"])
        self.assertEqual(result["message_id"], "om_1")
        sender.assert_called_once()


class DegradedModeTest(unittest.TestCase):
    def setUp(self):
        self._patch = mock.patch.object(adapter, "adapter_token", return_value="")
        self._patch.start()
        adapter._config["FEISHU_CHAT_ID"] = "oc_test"

    def tearDown(self):
        self._patch.stop()
        adapter._config.pop("FEISHU_CHAT_ID", None)

    def test_host_bridge_rejected(self):
        result = call(
            adapter.Handler,
            "POST",
            "/feishu/ops/send",
            body={"text": "hi"},
            client=("172.19.0.1", 5000),
        )
        self.assertEqual(result["error"], "unauthorized")

    def test_public_ip_rejected(self):
        result = call(
            adapter.Handler,
            "POST",
            "/feishu/ops/send",
            body={"text": "hi"},
            client=("8.8.8.8", 5000),
        )
        self.assertEqual(result["error"], "unauthorized")

    def test_container_network_accepted(self):
        with mock.patch.object(adapter, "send_text", return_value="om_2"):
            result = call(
                adapter.Handler,
                "POST",
                "/feishu/ops/send",
                body={"text": "hi"},
                client=("172.19.0.7", 5000),
            )
        self.assertTrue(result["ok"])


class HealthTest(unittest.TestCase):
    def setUp(self):
        adapter._config["FEISHU_CHAT_ID"] = "oc_test"

    def tearDown(self):
        adapter._config.pop("FEISHU_CHAT_ID", None)

    def test_health_does_not_leak_chats(self):
        with mock.patch.object(adapter, "get_token", return_value="t"):
            result = call(
                adapter.Handler,
                "GET",
                "/feishu/health",
                client=("127.0.0.1", 5000),
            )
        self.assertNotIn("chats", result)
        self.assertNotIn("oc_test", json.dumps(result))
        self.assertTrue(result["token_ok"])

    def test_health_rejects_foreign_source(self):
        result = call(
            adapter.Handler,
            "GET",
            "/feishu/health",
            client=("8.8.8.8", 5000),
        )
        self.assertEqual(result["error"], "unauthorized")

    def test_health_chats_requires_token(self):
        with mock.patch.object(adapter, "get_token", return_value="t"), \
                mock.patch.object(adapter, "list_chats", return_value=[{"chat_id": "oc_x", "name": "ops"}]):
            result = call(
                adapter.Handler,
                "GET",
                "/feishu/health?chats=1",
                client=("127.0.0.1", 5000),
            )
        self.assertNotIn("chats", result)
        self.assertIn("chats_error", result)

    def test_health_chats_with_valid_token(self):
        with mock.patch.object(adapter, "adapter_token", return_value="s3cret-token"), \
                mock.patch.object(adapter, "get_token", return_value="t"), \
                mock.patch.object(adapter, "list_chats", return_value=[{"chat_id": "oc_x", "name": "ops"}]):
            result = call(
                adapter.Handler,
                "GET",
                "/feishu/health?chats=1",
                headers={"X-Adapter-Token": "s3cret-token"},
                client=("127.0.0.1", 5000),
            )
        self.assertEqual(result["chats"], [{"chat_id": "oc_x", "name": "ops"}])

    def test_health_allows_docker_bridge_gateway(self):
        """宿主机 curl 127.0.0.1:8061 时 remoteAddr 是桥接网关，health 必须可用。"""
        with mock.patch.object(adapter, "get_token", return_value="t"):
            result = call(
                adapter.Handler,
                "GET",
                "/feishu/health",
                client=("172.19.0.1", 5000),
            )
        self.assertTrue(result["token_ok"])
        self.assertNotIn("chats", result)


if __name__ == "__main__":
    unittest.main()

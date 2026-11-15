# -*- coding: utf-8 -*-
"""语音转写降级链测试（B3 + P1-语音）：

1. AI_ASR_API_KEY 未配置 → /v1/asr 静默转本地 asr-service（faster-whisper），不报 5xx；
2. 云通道 key 配置但上游 5xx → 同样转本地；
3. AI_ASR_API_KEY 与视觉通道的 AI_MIMO_API_KEY 互不读取（B3：独立密钥组）；
4. 本地服务返回 usage.seconds / 分段兜底计算；
5. 本地也未配置 → 明确 503，不悬挂。
"""
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

_app_dir = str(Path(__file__).resolve().parent.parent)
if _app_dir not in sys.path:
    sys.path.insert(0, _app_dir)

os.environ["LLM_BACKEND"] = "mock"
os.environ["LLAMA_CPP_URL"] = ""
os.environ["MODEL_MANAGER_URL"] = ""
for _name in ("AI_ASR_API_KEY", "AI_MIMO_API_KEY", "ASR_SERVICE_URL"):
    os.environ.pop(_name, None)

from fastapi.testclient import TestClient

from app.main import app, GATEWAY_API_KEY
from app import perception


AUTH = {"Authorization": f"Bearer {GATEWAY_API_KEY}"}
AUDIO_B64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="  # 最小合法 WAV 头


def _client():
    return TestClient(app)


class AsrFallbackChainTest(unittest.TestCase):

    def setUp(self):
        os.environ["ASR_SERVICE_URL"] = "http://asr-stub:8092"
        self.client = _client()

    def tearDown(self):
        os.environ.pop("ASR_SERVICE_URL", None)

    def _asr_body(self):
        return {"audioBase64": AUDIO_B64, "mime": "audio/wav", "language": "zh"}

    def test_no_key_falls_back_to_local_service(self):
        """AI_ASR_API_KEY 未配置：不报错不阻塞，静默转本地 faster-whisper。"""
        local_payload = {
            "text": "帮我查一下订单",
            "segments": [{"startMs": 0, "endMs": 27000, "text": "帮我查一下订单"}],
            "engine": "faster-whisper",
            "seconds": 27.0,
        }
        with patch("app.perception.httpx.AsyncClient") as MockClient:
            instance = MockClient.return_value.__aenter__.return_value
            instance.post.return_value = _json_response(200, local_payload)
            resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data["engine"], "faster-whisper")
        self.assertEqual(data["text"], "帮我查一下订单")
        self.assertEqual(data["usage"]["seconds"], 27.0)
        # 请求确实发往本地服务 /transcribe
        called_url = instance.post.call_args.args[0]
        self.assertTrue(called_url.endswith("/transcribe"))

    def test_no_key_and_no_local_service_returns_503(self):
        """云通道与本地服务都未配置：明确 503 文案，不悬挂、不编造。"""
        os.environ.pop("ASR_SERVICE_URL", None)
        resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
        self.assertEqual(resp.status_code, 503)
        # main.py 的 HTTPException handler 把 detail 直接作为响应体返回
        body = resp.json()
        self.assertEqual(body["error"]["type"], "not_configured")

    def test_cloud_5xx_also_falls_back_to_local(self):
        """云通道配置但上游 5xx：同样静默转本地（三级降级第二级）。"""
        os.environ["AI_ASR_API_KEY"] = "test-asr-key"
        try:
            local_payload = {"text": "本地结果", "segments": [], "engine": "faster-whisper",
                             "seconds": 5.5}
            with patch("app.perception.httpx.AsyncClient") as MockClient:
                instance = MockClient.return_value.__aenter__.return_value

                async def post_side_effect(url, **kwargs):
                    if url.endswith("/transcribe"):
                        return _json_response(200, local_payload)
                    return _json_response(502, {"error": {"message": "upstream"}})

                instance.post.side_effect = post_side_effect
                resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
            self.assertEqual(resp.status_code, 200)
            data = resp.json()
            self.assertEqual(data["engine"], "faster-whisper")
            self.assertEqual(data["text"], "本地结果")
        finally:
            os.environ.pop("AI_ASR_API_KEY", None)

    def test_cloud_key_used_for_asr_not_vision_key(self):
        """B3：ASR 云通道读 AI_ASR_API_KEY；即便 AI_MIMO_API_KEY 配置了也不用于 ASR。"""
        os.environ["AI_MIMO_API_KEY"] = "vision-channel-key"
        try:
            local_payload = {"text": "本地结果", "segments": [], "engine": "faster-whisper",
                             "seconds": 1.0}
            with patch("app.perception.httpx.AsyncClient") as MockClient:
                instance = MockClient.return_value.__aenter__.return_value
                instance.post.return_value = _json_response(200, local_payload)
                resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
            self.assertEqual(resp.status_code, 200)
            # 未发生任何 /chat/completions 调用（云通道未启用）
            for call in instance.post.call_args_list:
                self.assertFalse(call.args[0].endswith("/chat/completions"))
        finally:
            os.environ.pop("AI_MIMO_API_KEY", None)

    def test_cloud_success_returns_usage_seconds(self):
        """云通道成功：透传上游 usage.seconds（t_support_llm_usage.audio_seconds 来源）。"""
        os.environ["AI_ASR_API_KEY"] = "test-asr-key"
        try:
            cloud_payload = {
                "choices": [{"message": {"content": "云端转写"}, "finish_reason": "stop"}],
                "usage": {"seconds": 27.5},
            }
            with patch("app.perception.httpx.AsyncClient") as MockClient:
                instance = MockClient.return_value.__aenter__.return_value
                instance.post.return_value = _json_response(200, cloud_payload)
                resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
            self.assertEqual(resp.status_code, 200)
            data = resp.json()
            self.assertEqual(data["engine"], "mimo")
            self.assertEqual(data["text"], "云端转写")
            self.assertEqual(data["usage"]["seconds"], 27.5)
        finally:
            os.environ.pop("AI_ASR_API_KEY", None)

    def test_local_seconds_missing_computed_from_segments(self):
        """本地服务缺 seconds 字段（旧版响应）：按分段 endMs 最大值兜底求时长。"""
        local_payload = {
            "text": "分段兜底",
            "segments": [{"startMs": 0, "endMs": 12000, "text": "分段兜底"}],
            "engine": "faster-whisper",
        }
        with patch("app.perception.httpx.AsyncClient") as MockClient:
            instance = MockClient.return_value.__aenter__.return_value
            instance.post.return_value = _json_response(200, local_payload)
            resp = self.client.post("/v1/asr", json=self._asr_body(), headers=AUTH)
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["usage"]["seconds"], 12.0)

    def test_unauthorized_request_rejected(self):
        """网关鉴权仍然生效：不带 key 一律 401。"""
        resp = self.client.post("/v1/asr", json=self._asr_body())
        self.assertEqual(resp.status_code, 401)


def _json_response(status, payload):
    """构造 httpx.Response 形态的桩。"""
    import httpx

    return httpx.Response(status, content=json.dumps(payload).encode("utf-8"),
                          headers={"Content-Type": "application/json"},
                          request=httpx.Request("POST", "http://stub/"))


if __name__ == "__main__":
    unittest.main()

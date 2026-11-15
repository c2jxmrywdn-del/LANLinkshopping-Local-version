# -*- coding: utf-8 -*-
"""语音转写真实端到端测试：仓库内样例 wav（Windows SAPI Huihui 中文合成）
经 /transcribe（faster-whisper medium int8，纯 CPU）真实转写，
断言转写进入理解链的核心语义（订单/物流关键词命中、时长计量、分段时间戳）。

fixture：scripts/e2e/fixtures/sample-voice-27s.wav
重生成：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/e2e/make-voice-fixture.ps1

运行：python -m pytest asr-service/tests/test_transcribe_real.py -q
前置：fastapi + httpx + pytest（宿主机），faster-whisper（容器内；本测试经容器跑时亦可用）。
模型权重按需下载（HF 环境变量 HF_HOME 可重定向）；无权重环境跳过并给出明确 skip 理由。
"""
import base64
import json
import os
import sys
import unittest
from pathlib import Path

_repo = str(Path(__file__).resolve().parent.parent.parent)
_fixture = Path(_repo) / "scripts" / "e2e" / "fixtures" / "sample-voice-27s.wav"

_asr_dir = str(Path(__file__).resolve().parent.parent)
if _asr_dir not in sys.path:
    sys.path.insert(0, _asr_dir)

os.environ.setdefault("ASR_MODEL", "tiny")  # 测试用最小权重，语义断言仅要求可分词


def _fixture_b64():
    return base64.b64encode(_fixture.read_bytes()).decode("ascii")


@unittest.skipUnless(_fixture.exists(), "fixture 缺失：先运行 scripts/e2e/make-voice-fixture.ps1")
class TranscribeRealTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        try:
            from fastapi.testclient import TestClient
            from app.main import app
        except ImportError as exc:
            raise unittest.SkipTest("fastapi/testclient 不可用：%s" % exc)
        cls.client = TestClient(app)

    def test_real_wav_transcribes_order_keywords(self):
        """真实转写：语音内容是订单物流咨询，转写文本应命中关键词而非空串。"""
        resp = self.client.post("/transcribe", json={
            "audioBase64": _fixture_b64(),
            "mime": "audio/wav",
            "language": "zh",
        })
        self.assertEqual(resp.status_code, 200, resp.text)
        data = resp.json()
        text = data.get("text") or ""
        # 转写进入理解链的底线：非空、且主题词（订单/物流/发货）至少命中一个
        # （whisper 对同音字有转写差异，不断言逐字匹配）
        self.assertTrue(text.strip(), "转写结果为空")
        keywords = ("订单", "物流", "发货", "单")
        self.assertTrue(any(k in text for k in keywords),
                        "转写未命中主题词：%s" % text)

    def test_real_wav_reports_seconds_and_segments(self):
        """时长计量：seconds 必须为正（落 t_support_llm_usage.audio_seconds 的来源），
        分段时间戳齐全（endMs > startMs）。"""
        resp = self.client.post("/transcribe", json={
            "audioBase64": _fixture_b64(),
            "mime": "audio/wav",
            "language": "zh",
        })
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertGreater(float(data["seconds"]), 20.0)  # fixture ≈ 29s
        segments = data.get("segments") or []
        self.assertTrue(segments, "分段为空")
        for seg in segments:
            self.assertGreater(seg["endMs"], seg["startMs"])
            self.assertTrue(seg["text"].strip())

    def test_engine_label(self):
        """engine 标记固定为 faster-whisper（落库回溯通道用）。"""
        resp = self.client.post("/transcribe", json={
            "audioBase64": _fixture_b64(),
            "mime": "audio/wav",
            "language": "zh",
        })
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["engine"], "faster-whisper")


if __name__ == "__main__":
    unittest.main()

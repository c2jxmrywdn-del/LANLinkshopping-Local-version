# -*- coding: utf-8 -*-
"""多模态感知路由：MiMo 视觉 / ASR / TTS（OpenAI 兼容 /chat/completions）。

设计要点：
  1. 只做协议适配：把网关的「base64 + 语义参数」翻译成 MiMo 的 content 数组，
     不在这里落任何业务逻辑，避免与主链路 RAG 相互影响；
  2. 密钥只从环境变量读取，日志不打印 Key、音频/图片原文与合成文本；
  3. 出口统一 60s 超时；MiMo 非 2xx 一律 502 + 上游状态码（不回传上游响应全文）；
  4. ASR 兜底：MiMo 失败且配置了 ASR_SERVICE_URL 时转发本地 faster-whisper 服务。
"""
from __future__ import annotations

import logging
import os
import re
import time
from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

logger = logging.getLogger("lanlink.ai-gateway.perception")

UPSTREAM_TIMEOUT = 60.0

DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"
DEFAULT_CHAT_MODEL = "mimo-v2.6-flash"
DEFAULT_ASR_MODEL = "mimo-v2.5-asr"
DEFAULT_TTS_MODEL = "mimo-v2.5-tts"

# ASR 感知通道使用独立密钥组（AI_ASR_*）：与出站文本渠道的 AI_OUTBOUND_MIMO_API_KEY、
# 视觉/TTS 的 AI_MIMO_API_KEY 互不耦合——配置 ASR key 不应改变外呼策略面，反之亦然。
DEFAULT_ASR_BASE_URL = "https://api.xiaomimimo.com/v1"

_BASE64_RE = re.compile(r"^[A-Za-z0-9+/=\s]+$")


# ----------------------------- 鉴权（复用主模块依赖，避免循环导入） -----------------------------

async def _require_gateway_key(request: Request):
    from .main import require_gateway_key
    await require_gateway_key(request)


router = APIRouter(tags=["perception"], dependencies=[Depends(_require_gateway_key)])


# ----------------------------- 配置与上游调用 -----------------------------

def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


class MiMoError(Exception):
    def __init__(self, message: str, *, upstream_status: int | None = None,
                 configured: bool = True):
        super().__init__(message)
        self.message = message
        self.upstream_status = upstream_status
        self.configured = configured


def _to_http_error(exc: MiMoError) -> HTTPException:
    if exc.upstream_status is not None:
        return HTTPException(status_code=502, detail={"error": {
            "message": f"MiMo 上游返回 HTTP {exc.upstream_status}",
            "type": "upstream_error",
            "code": exc.upstream_status,
        }})
    if not exc.configured:
        return HTTPException(status_code=503, detail={"error": {
            "message": "MiMo 未配置：缺少 AI_MIMO_API_KEY",
            "type": "not_configured",
        }})
    return HTTPException(status_code=502, detail={"error": {
        "message": exc.message,
        "type": "upstream_error",
    }})


async def _mimo_chat(payload: dict) -> dict:
    """调用 MiMo OpenAI 兼容 /chat/completions；只回传 JSON，不记录请求/响应正文。"""
    api_key = _env("AI_MIMO_API_KEY")
    if not api_key:
        raise MiMoError("MiMo 未配置", configured=False)

    base_url = _env("AI_MIMO_BASE_URL", DEFAULT_BASE_URL).rstrip("/") or DEFAULT_BASE_URL
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
            resp = await client.post(f"{base_url}/chat/completions", json=payload, headers=headers)
    except httpx.TimeoutException:
        raise MiMoError(f"MiMo 请求超时（>{int(UPSTREAM_TIMEOUT)}s）")
    except httpx.HTTPError as exc:
        raise MiMoError(f"MiMo 网络错误（{type(exc).__name__}）")

    if resp.status_code < 200 or resp.status_code >= 300:
        logger.warning("[perception] MiMo HTTP %s model=%s", resp.status_code,
                       payload.get("model"))
        raise MiMoError(f"MiMo 上游返回 HTTP {resp.status_code}",
                        upstream_status=resp.status_code)
    try:
        return resp.json()
    except ValueError:
        raise MiMoError("MiMo 返回非 JSON 响应")


async def _asr_cloud_chat(payload: dict) -> dict:
    """ASR 云通道：同 _mimo_chat，但密钥与端点走独立的 AI_ASR_* 组（B3 密钥拆分），
    且未配置时不算错误——由调用方直接静默转本地兜底。"""
    api_key = _env("AI_ASR_API_KEY")
    if not api_key:
        raise MiMoError("ASR 云通道未配置", configured=False)

    base_url = _env("AI_ASR_BASE_URL", DEFAULT_ASR_BASE_URL).rstrip("/") or DEFAULT_ASR_BASE_URL
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
            resp = await client.post(f"{base_url}/chat/completions", json=payload, headers=headers)
    except httpx.TimeoutException:
        raise MiMoError(f"ASR 云通道请求超时（>{int(UPSTREAM_TIMEOUT)}s）")
    except httpx.HTTPError as exc:
        raise MiMoError(f"ASR 云通道网络错误（{type(exc).__name__}）")

    if resp.status_code < 200 or resp.status_code >= 300:
        logger.warning("[perception] ASR cloud HTTP %s model=%s", resp.status_code,
                       payload.get("model"))
        raise MiMoError(f"ASR 云通道上游返回 HTTP {resp.status_code}",
                        upstream_status=resp.status_code)
    try:
        return resp.json()
    except ValueError:
        raise MiMoError("ASR 云通道返回非 JSON 响应")


def _data_uri(raw: str, mime: str) -> str:
    value = (raw or "").strip()
    if not value:
        raise HTTPException(status_code=400, detail={
            "error": {"message": "base64 内容不能为空", "type": "invalid_request_error"}})
    if value[:5].lower() == "data:":
        return value
    return f"data:{mime};base64,{value}"


def _extract_text(data: dict) -> str:
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict) and item.get("text"):
                parts.append(str(item["text"]))
        return "".join(parts).strip()
    return str(choices[0].get("text") or "").strip()


def _extract_audio(data: dict) -> str:
    """兼容 audio.data / message.content(base64) / 顶层 data 三种返回形态。"""
    choices = data.get("choices") or []
    if choices:
        message = choices[0].get("message") or {}
        audio = message.get("audio")
        if isinstance(audio, dict):
            for key in ("data", "audio", "base64"):
                if audio.get(key):
                    return str(audio[key])
        content = message.get("content")
        if isinstance(content, str) and len(content) > 64 and _BASE64_RE.match(content.strip()):
            return content.strip()

    audio = data.get("audio")
    if isinstance(audio, dict):
        for key in ("data", "audio", "base64"):
            if audio.get(key):
                return str(audio[key])
    if isinstance(audio, str) and len(audio) > 64:
        return audio

    raw = data.get("data")
    if isinstance(raw, dict) and raw.get("audio"):
        return str(raw["audio"])
    if isinstance(raw, str) and len(raw) > 64 and _BASE64_RE.match(raw.strip()):
        return raw.strip()
    return ""


# ----------------------------- 请求模型 -----------------------------

class VisionMimoRequest(BaseModel):
    prompt: str = Field(..., min_length=1, description="对图片的提问/指令")
    imageBase64: str = Field(..., min_length=1, description="图片 base64（可带 data: 前缀）")
    mime: str | None = Field(None, description="image/jpeg|png|webp，默认 image/jpeg")
    model: str | None = Field(None, description="默认 AI_MIMO_CHAT_MODEL（mimo-v2.6-flash）")


class AsrRequest(BaseModel):
    audioBase64: str = Field(..., min_length=1, description="音频 base64（可带 data: 前缀）")
    mime: str | None = Field(None, description="audio/wav|mpeg|webm|ogg，默认 audio/wav")
    language: str | None = Field(None, description="auto|zh|en，默认 auto")


class TtsRequest(BaseModel):
    text: str = Field(..., min_length=1, description="待合成文本")
    voice: str | None = Field(None, description="音色名（可选，透传给 MiMo）")
    # 自然语言风格指令放在 role=user；不参与朗读，只控制语气/语速/角色感
    style: str | None = Field(None, description="风格指令（知性御姐等），放在 role=user")
    format: str | None = Field(None, description="mp3|wav|opus，默认 mp3")


class OcrProxyRequest(BaseModel):
    imageBase64: str = Field(..., min_length=1)
    lang: str | None = Field(None, description="ch|en")


# ----------------------------- 路由 -----------------------------

@router.post("/v1/vision-mimo")
async def vision_mimo(req: VisionMimoRequest):
    """图片理解：MiMo 多模态模型（默认 mimo-v2.6-flash）。"""
    t0 = time.time()
    model = (req.model or _env("AI_MIMO_CHAT_MODEL", DEFAULT_CHAT_MODEL)).strip()
    mime = (req.mime or "image/jpeg").strip() or "image/jpeg"
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": req.prompt},
            {"type": "image_url", "image_url": {"url": _data_uri(req.imageBase64, mime)}},
        ]}],
        "stream": False,
    }
    try:
        data = await _mimo_chat(payload)
        text = _extract_text(data)
        engine = "mimo"
        usage = data.get("usage") or {}
    except MiMoError as exc:
        logger.warning("[perception] vision MiMo 失败（%s），尝试本地模型兜底", exc.message)
        try:
            import asyncio
            from .main import LLM
            text, backend, _ = await asyncio.to_thread(
                LLM.chat_vision, req.prompt, req.imageBase64, mime, 0.2, 512
            )
            model = LLM.vl_model
            engine = backend or "local-vl"
            usage = {}
        except Exception as local_err:
            logger.warning("[perception] vision 本地兜底亦失败: %s", local_err)
            raise _to_http_error(exc)

    logger.info("[perception] vision engine=%s model=%s chars=%d elapsed=%.0fms",
                engine, model, len(text), (time.time() - t0) * 1000)
    return {"text": text, "engine": engine, "model": model, "usage": usage}


@router.post("/v1/asr")
async def asr(req: AsrRequest):
    """语音转写：AI_ASR_* 云通道（默认 MiMo mimo-v2.5-asr）→ 本地 faster-whisper。

    云通道按 AI_ASR_API_KEY 配置与否自动启停：未配置不报错、不阻塞，直接静默转本地
    兜底（asr-service，CPU）；本地服务未配置/不可达时才返回 503 明确文案。
    """
    t0 = time.time()
    language = (req.language or "auto").strip().lower() or "auto"
    if language not in ("auto", "zh", "en"):
        language = "auto"
    mime = (req.mime or "audio/wav").strip() or "audio/wav"
    model = _env("AI_ASR_MODEL", DEFAULT_ASR_MODEL)

    payload = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "input_audio", "input_audio": {"data": _data_uri(req.audioBase64, mime)}},
        ]}],
        "asr_options": {"language": language},
        "stream": False,
    }
    try:
        data = await _asr_cloud_chat(payload)
        text = _extract_text(data)
        usage = data.get("usage") or {}
        logger.info("[perception] asr engine=asr-cloud model=%s chars=%d elapsed=%.0fms",
                    model, len(text), (time.time() - t0) * 1000)
        return {"text": text, "engine": "mimo", "model": model, "usage": usage}
    except MiMoError as exc:
        # 未配置与上游失败同路：一律尝试本地兜底，密钥缺失属正常部署形态而非错误
        logger.warning("[perception] asr cloud unavailable（%s），fallback to local faster-whisper",
                       exc.message)

    fallback = _env("ASR_SERVICE_URL")
    if not fallback:
        raise HTTPException(status_code=503, detail={"error": {
            "message": "语音转写暂不可用：云通道未配置且本地 ASR 服务未配置",
            "type": "not_configured"}})
    return await _local_asr(fallback, req.audioBase64, mime, language)


async def _local_asr(base_url: str, audio_b64: str, mime: str, language: str) -> dict:
    """转发本地 asr-service（faster-whisper medium int8，CPU）。"""
    url = base_url.rstrip("/") + "/transcribe"
    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
            resp = await client.post(url, json={
                "audioBase64": audio_b64,
                "mime": mime,
                "language": language,
            })
    except httpx.TimeoutException:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"本地 ASR 服务超时（>{int(UPSTREAM_TIMEOUT)}s）",
            "type": "upstream_error"}})
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"本地 ASR 服务不可用（{type(exc).__name__}）",
            "type": "upstream_error"}})

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"本地 ASR 服务返回 HTTP {resp.status_code}",
            "type": "upstream_error",
            "code": resp.status_code}})
    try:
        data = resp.json()
    except ValueError:
        raise HTTPException(status_code=502, detail={"error": {
            "message": "本地 ASR 服务返回非 JSON 响应", "type": "upstream_error"}})

    # 本地链按时长计量：优先上游 probe 时长，缺省按分段覆盖区间求和兜底，
    # 保证 t_support_llm_usage.audio_seconds 在本地腿也有非零落账来源。
    seconds = data.get("seconds")
    if seconds is None:
        spans = data.get("segments") or []
        if spans:
            try:
                seconds = max(float(seg.get("endMs", 0)) for seg in spans) / 1000.0
            except (TypeError, ValueError):
                seconds = None
    return {"text": data.get("text") or "", "engine": "faster-whisper",
            "model": data.get("model") or "medium",
            "usage": {"seconds": seconds}}


@router.post("/v1/tts")
async def tts(req: TtsRequest):
    """语音合成：MiMo mimo-v2.5-tts，待合成文本放在 role=assistant 的消息里。

    音色与风格都走固定配置：voice 默认「茉莉」（中文成熟女声），style 放在 role=user
    只控制语气与节奏，不会出现在朗读内容里；两者共同保证客服全场景音色一致。
    """
    t0 = time.time()
    model = _env("AI_MIMO_TTS_MODEL", DEFAULT_TTS_MODEL)
    fmt = (req.format or "mp3").strip().lower() or "mp3"
    voice = (req.voice or _env("AI_MIMO_TTS_VOICE", "茉莉") or "茉莉").strip()
    style = (req.style or _env("AI_MIMO_TTS_STYLE", "")).strip()

    audio_cfg: dict[str, Any] = {"format": fmt, "voice": voice}

    messages = []
    if style:
        messages.append({"role": "user", "content": style})
    messages.append({"role": "assistant", "content": req.text})

    payload = {
        "model": model,
        "messages": messages,
        "audio": audio_cfg,
        "stream": False,
    }
    try:
        data = await _mimo_chat(payload)
    except MiMoError as exc:
        logger.warning("[perception] tts failed: %s", exc.message)
        raise _to_http_error(exc)

    audio_b64 = _extract_audio(data)
    if not audio_b64:
        raise HTTPException(status_code=502, detail={"error": {
            "message": "MiMo 未返回音频数据", "type": "upstream_error"}})

    logger.info("[perception] tts model=%s format=%s voice=%s style=%s chars=%d elapsed=%.0fms",
                model, fmt, voice, "on" if style else "off", len(req.text), (time.time() - t0) * 1000)
    return {"audioBase64": audio_b64, "format": fmt, "sampleRate": 24000,
            "engine": "mimo", "voice": voice}


@router.post("/v1/ocr")
async def ocr_proxy(req: OcrProxyRequest):
    """OCR 代理：转发独立 ocr-service（RapidOCR/ONNX，不含 torch）。"""
    base_url = _env("OCR_SERVICE_URL")
    if not base_url:
        raise HTTPException(status_code=503, detail={"error": {
            "message": "OCR 服务未配置：缺少 OCR_SERVICE_URL",
            "type": "not_configured"}})

    body = {"imageBase64": req.imageBase64}
    if req.lang:
        body["lang"] = req.lang

    try:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
            resp = await client.post(base_url.rstrip("/") + "/ocr", json=body)
    except httpx.TimeoutException:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"OCR 服务超时（>{int(UPSTREAM_TIMEOUT)}s）", "type": "upstream_error"}})
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"OCR 服务不可用（{type(exc).__name__}）", "type": "upstream_error"}})

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"OCR 服务返回 HTTP {resp.status_code}",
            "type": "upstream_error",
            "code": resp.status_code}})
    try:
        return resp.json()
    except ValueError:
        raise HTTPException(status_code=502, detail={"error": {
            "message": "OCR 服务返回非 JSON 响应", "type": "upstream_error"}})


class VideoFramesRequest(BaseModel):
    videoBase64: str = Field(..., min_length=1)
    fps: int | None = Field(1)
    maxFrames: int | None = Field(8)
    maxSeconds: int | None = Field(30)


@router.post("/v1/frames")
async def video_frames_proxy(req: VideoFramesRequest):
    """视频抽帧代理：转发 asr-service（内置 ffmpeg 抽帧）。"""
    base_url = _env("ASR_SERVICE_URL")
    if not base_url:
        raise HTTPException(status_code=503, detail={"error": {
            "message": "ASR/视频服务未配置：缺少 ASR_SERVICE_URL", "type": "not_configured"}})
    body = {
        "videoBase64": req.videoBase64,
        "fps": req.fps or 1,
        "maxFrames": req.maxFrames or 8,
        "maxSeconds": req.maxSeconds or 30
    }
    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            resp = await client.post(base_url.rstrip("/") + "/frames", json=body)
    except httpx.TimeoutException:
        raise HTTPException(status_code=502, detail={"error": {
            "message": "视频抽帧服务超时（>120s）", "type": "upstream_error"}})
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"视频抽帧服务不可用（{type(exc).__name__}）", "type": "upstream_error"}})

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail={"error": {
            "message": f"视频抽帧服务返回 HTTP {resp.status_code}",
            "type": "upstream_error", "code": resp.status_code}})
    try:
        return resp.json()
    except ValueError:
        raise HTTPException(status_code=502, detail={"error": {
            "message": "视频抽帧服务返回非 JSON 响应", "type": "upstream_error"}})


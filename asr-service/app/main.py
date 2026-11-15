# -*- coding: utf-8 -*-
"""LANLink ASR / 视频抽帧服务（faster-whisper medium int8 + ffmpeg）。

职责：
  1. /transcribe：音频 base64 → 文本 + 分段时间戳（中文/自动识别）；
  2. /frames：视频 base64 → 关键帧（jpg，长边 ≤1280）+ 16k mono wav 音轨，供上层串联 ASR 与视觉；
  3. 资源约束：音频 ≤10MB/≤60s，视频 ≤20MB/≤30s（超时长硬截断，超体积返回 413）。

模型策略：medium / cpu / int8，懒加载 + 全局锁（并发首请求只加载一次）；推理串行化，
避免多请求同时打满 CPU 导致全部超时。
"""
from __future__ import annotations

import base64
import binascii
import logging
import math
import os
import shutil
import tempfile
import threading
import time
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import video as video_tools

logging.basicConfig(level=(os.environ.get("LOG_LEVEL") or "INFO").upper())
logger = logging.getLogger("lanlink.asr-service")

MODEL_NAME = os.environ.get("ASR_MODEL", "medium")
MODEL_DIR = os.environ.get("ASR_MODEL_DIR", "/app/models")

MAX_AUDIO_BYTES = 10 * 1024 * 1024
MAX_AUDIO_SECONDS = 60.0
MAX_VIDEO_BYTES = 20 * 1024 * 1024
MAX_VIDEO_SECONDS = 30.0

app = FastAPI(title="LANLink ASR Service", version="1.0.0")

_MODEL = None
_MODEL_LOCK = threading.Lock()
_INFER_LOCK = threading.Lock()


# ----------------------------- 公共工具 -----------------------------

def _b64_to_bytes(raw: str, limit: int, code: str) -> bytes:
    text = (raw or "").strip()
    if text.lower().startswith("data:") and "," in text:
        text = text.split(",", 1)[1]
    text = "".join(text.split())
    if not text:
        raise HTTPException(status_code=400, detail={
            "error": {"message": "base64 内容不能为空", "type": "invalid_request_error"}})
    try:
        data = base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail={
            "error": {"message": "不是合法的 base64", "type": "invalid_request_error",
                      "code": "INVALID_BASE64"}})
    if len(data) > limit:
        raise HTTPException(status_code=413, detail={
            "error": {"message": f"解码后 {len(data)} 字节，超过 {limit // (1024 * 1024)}MB 上限",
                      "type": "invalid_request_error", "code": code}})
    return data


def _write_temp(data: bytes, workdir: str, name: str) -> str:
    path = os.path.join(workdir, name)
    with open(path, "wb") as fp:
        fp.write(data)
    return path


def _load_model():
    """懒加载 faster-whisper；全局锁保证并发首请求只加载一次模型。"""
    global _MODEL
    if _MODEL is not None:
        return _MODEL
    with _MODEL_LOCK:
        if _MODEL is None:
            from faster_whisper import WhisperModel
            os.makedirs(MODEL_DIR, exist_ok=True)
            _MODEL = WhisperModel(MODEL_NAME, device="cpu", compute_type="int8",
                                  download_root=MODEL_DIR)
            logger.info("[asr] model ready: %s (cpu/int8, dir=%s)", MODEL_NAME, MODEL_DIR)
    return _MODEL


def _transcribe_sync(path: str, language: str | None) -> tuple[str, list[dict], float | None]:
    model = _load_model()
    with _INFER_LOCK:
        segments, _info = model.transcribe(path, language=language, vad_filter=True, beam_size=5)
        items: list[dict] = []
        logprobs: list[float] = []
        for seg in segments:
            text = (seg.text or "").strip()
            if not text:
                continue
            items.append({
                "startMs": int(round(seg.start * 1000)),
                "endMs": int(round(seg.end * 1000)),
                "text": text,
            })
            if getattr(seg, "avg_logprob", None) is not None:
                logprobs.append(float(seg.avg_logprob))

    confidence = None
    if logprobs:
        confidence = round(max(0.0, min(1.0, sum(math.exp(x) for x in logprobs) / len(logprobs))), 4)
    return "".join(item["text"] for item in items), items, confidence


# ----------------------------- 请求模型 -----------------------------

class TranscribeRequest(BaseModel):
    audioBase64: str = Field(..., min_length=1, description="音频 base64（可带 data: 前缀）")
    mime: str | None = Field(None, description="audio/wav、audio/mpeg、audio/webm 等")
    language: Literal["zh", "en", "auto"] = Field("auto", description="zh|en|auto")


class FramesRequest(BaseModel):
    videoBase64: str = Field(..., min_length=1, description="视频 base64（可带 data: 前缀）")
    fps: float = Field(1.0, gt=0, le=5, description="抽帧频率（0.1~5）")
    maxFrames: int = Field(8, ge=1, le=16, description="最多返回帧数")
    maxSeconds: float = Field(MAX_VIDEO_SECONDS, gt=0, le=MAX_VIDEO_SECONDS,
                              description="处理时长上限（≤30s）")


# ----------------------------- 接口 -----------------------------

@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "engine": "faster-whisper"}


@app.post("/transcribe")
async def transcribe(req: TranscribeRequest):
    t0 = time.time()
    data = _b64_to_bytes(req.audioBase64, MAX_AUDIO_BYTES, "AUDIO_TOO_LARGE")

    workdir = tempfile.mkdtemp(prefix="asr-")
    try:
        path = _write_temp(data, workdir, "input.bin")
        media = video_tools.probe_media(path)
        duration = media.get("durationSec")
        if duration is None:
            raise HTTPException(status_code=400, detail={
                "error": {"message": "无法解析音频（ffprobe 失败，可能格式不受支持或文件损坏）",
                          "type": "invalid_request_error", "code": "INVALID_AUDIO"}})
        if duration > MAX_AUDIO_SECONDS + 0.05:
            raise HTTPException(status_code=413, detail={
                "error": {"message": f"音频时长 {duration:.1f}s 超过 {int(MAX_AUDIO_SECONDS)}s 上限",
                          "type": "invalid_request_error", "code": "AUDIO_TOO_LONG"}})

        language = None if req.language == "auto" else req.language
        try:
            text, segments, confidence = await run_in_threadpool(
                _transcribe_sync, path, language)
        except HTTPException:
            raise
        except Exception as exc:
            logger.exception("[asr] transcription failed: %s", type(exc).__name__)
            raise HTTPException(status_code=500, detail={
                "error": {"message": "语音识别失败", "type": "server_error", "code": "ASR_FAILED"}})

        logger.info("[asr] lang=%s duration=%.1fs bytes=%d chars=%d elapsed=%.0fms",
                    req.language, duration, len(data), len(text), (time.time() - t0) * 1000)
        result = {
            "text": text,
            "segments": segments,
            "engine": "faster-whisper",
            "elapsedMs": int((time.time() - t0) * 1000),
            # probe 得到的音频时长（秒）：供上层按量计量（t_support_llm_usage.audio_seconds）
            "seconds": round(float(duration), 2),
        }
        if confidence is not None:
            result["confidence"] = confidence
        return result
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


@app.post("/frames")
async def frames(req: FramesRequest):
    t0 = time.time()
    data = _b64_to_bytes(req.videoBase64, MAX_VIDEO_BYTES, "VIDEO_TOO_LARGE")

    workdir = tempfile.mkdtemp(prefix="frames-")
    try:
        path = _write_temp(data, workdir, "input.bin")
        media = video_tools.probe_media(path)
        duration = media.get("durationSec")
        if duration is None:
            raise HTTPException(status_code=400, detail={
                "error": {"message": "无法解析视频（ffprobe 失败，可能格式不受支持或文件损坏）",
                          "type": "invalid_request_error", "code": "INVALID_VIDEO"}})

        max_seconds = min(float(req.maxSeconds), MAX_VIDEO_SECONDS)
        effective = min(duration, max_seconds)
        truncated = bool(duration > effective + 0.05)

        frame_dir = os.path.join(workdir, "frames")
        frame_paths = await run_in_threadpool(
            video_tools.extract_frames, path, frame_dir, req.fps, req.maxFrames, effective)
        if not frame_paths:
            raise HTTPException(status_code=400, detail={
                "error": {"message": "未能从视频中抽取到任何帧",
                          "type": "invalid_request_error", "code": "FRAME_EXTRACT_FAILED"}})

        out_frames = []
        for index, frame_path in enumerate(frame_paths):
            with open(frame_path, "rb") as fp:
                encoded = base64.b64encode(fp.read()).decode("ascii")
            out_frames.append({
                "index": index,
                "timeMs": int(round(index * 1000.0 / req.fps)),
                "imageBase64": encoded,
            })

        audio_b64 = None
        if media.get("hasAudio"):
            wav_path = os.path.join(workdir, "audio.wav")
            ok = await run_in_threadpool(
                video_tools.extract_audio_wav, path, wav_path, effective)
            if ok:
                with open(wav_path, "rb") as fp:
                    audio_b64 = base64.b64encode(fp.read()).decode("ascii")

        logger.info("[frames] fps=%.2f duration=%.1fs truncated=%s frames=%d audio=%s elapsed=%.0fms",
                    req.fps, duration, truncated, len(out_frames), bool(audio_b64),
                    (time.time() - t0) * 1000)
        result = {
            "frames": out_frames,
            "durationMs": int(round(effective * 1000)),
            "truncated": truncated,
        }
        if audio_b64 is not None:
            result["audioBase64"] = audio_b64
        return result
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# ----------------------------- 错误格式（OpenAI 风格） -----------------------------

@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    detail = exc.detail
    if isinstance(detail, dict) and "error" in detail:
        content = detail
    else:
        content = {"error": {"message": str(detail), "type": "invalid_request_error"}}
    return JSONResponse(status_code=exc.status_code, content=content)


@app.exception_handler(Exception)
async def unhandled(request: Request, exc: Exception):
    logger.exception("[asr] unhandled error")
    return JSONResponse(status_code=500, content={
        "error": {"message": "服务内部错误", "type": "server_error"}})

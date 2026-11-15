# -*- coding: utf-8 -*-
"""LANLink OCR 感知服务（RapidOCR + ONNX Runtime）。

设计约束：
  1. 只做 OCR：不引入 torch / tensorflow，推理后端固定 ONNX Runtime；
  2. 图片以 base64 传入，解码后 ≤10MB、长边 >4096 先等比缩放（防止超大图打爆内存）；
  3. 单请求 20s 超时（超时返回 504，线程可能仍继续跑完，但不再占用请求）；
  4. 引擎懒加载 + 全局锁：并发首请求只初始化一次，模型随 rapidocr wheel 内置，无外网也能启动。
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import io
import logging
import os
import threading
import time
from typing import Literal

import numpy as np
from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from PIL import Image, ImageOps
from pydantic import BaseModel, Field

logging.basicConfig(level=(os.environ.get("LOG_LEVEL") or "INFO").upper())
logger = logging.getLogger("lanlink.ocr-service")

MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_SIDE = 4096
OCR_TIMEOUT_SEC = 20.0

app = FastAPI(title="LANLink OCR Service", version="1.0.0")

_ENGINE = None
_ENGINE_LOCK = threading.Lock()
_ENGINE_ERROR: str | None = None


class OcrRequest(BaseModel):
    imageBase64: str = Field(..., min_length=1, description="图片 base64（可带 data: 前缀）")
    lang: Literal["ch", "en"] = Field("ch", description="ch|en；内置 ch 模型同时识别英文")


def _load_engine():
    """懒加载 RapidOCR 引擎（内置 PP-OCR 模型，首次调用时才载入 onnx 会话）。"""
    global _ENGINE, _ENGINE_ERROR
    if _ENGINE is not None:
        return _ENGINE
    with _ENGINE_LOCK:
        if _ENGINE is None:
            from rapidocr import RapidOCR  # 延迟导入：缺依赖时只让请求失败，不影响进程启动
            _ENGINE = RapidOCR()
            _ENGINE_ERROR = None
            logger.info("[ocr] rapidocr engine ready")
    return _ENGINE


def _b64_to_bytes(raw: str) -> bytes:
    text = (raw or "").strip()
    if text.lower().startswith("data:") and "," in text:
        text = text.split(",", 1)[1]
    text = "".join(text.split())
    if not text:
        raise HTTPException(status_code=400, detail={
            "error": {"message": "imageBase64 不能为空", "type": "invalid_request_error"}})
    try:
        return base64.b64decode(text, validate=True)
    except (binascii.Error, ValueError):
        raise HTTPException(status_code=400, detail={
            "error": {"message": "imageBase64 不是合法的 base64", "type": "invalid_request_error"}})


def _decode_image(raw_b64: str) -> tuple[np.ndarray, dict]:
    data = _b64_to_bytes(raw_b64)
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail={
            "error": {"message": f"图片解码后 {len(data)} 字节，超过 10MB 上限",
                      "type": "invalid_request_error", "code": "IMAGE_TOO_LARGE"}})
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
    except Exception:
        raise HTTPException(status_code=400, detail={
            "error": {"message": "图片无法解码（支持 jpg/png/webp/bmp）",
                      "type": "invalid_request_error", "code": "IMAGE_DECODE_FAILED"}})

    img = ImageOps.exif_transpose(img)
    if img.mode != "RGB":
        img = img.convert("RGB")

    width, height = img.size
    resized = False
    if max(width, height) > MAX_SIDE:
        scale = MAX_SIDE / float(max(width, height))
        new_size = (max(1, int(width * scale)), max(1, int(height * scale)))
        img = img.resize(new_size, Image.Resampling.LANCZOS)
        resized = True

    meta = {"width": img.size[0], "height": img.size[1], "resized": resized}
    return np.array(img), meta


def _normalize_result(result) -> list[dict]:
    """兼容 rapidocr 2.x/3.x 输出：v3 返回 RapidOCROutput，v1 返回 (list, elapse)。"""
    if isinstance(result, tuple) and len(result) == 2 and isinstance(result[0], (list, tuple)):
        result = result[0]

    lines: list[dict] = []
    txts = getattr(result, "txts", None)
    if txts is not None:
        scores = getattr(result, "scores", None) or ()
        boxes = getattr(result, "boxes", None)
        for i, text in enumerate(txts):
            if not str(text or "").strip():
                continue
            score = float(scores[i]) if i < len(scores) else 0.0
            box = []
            if boxes is not None and i < len(boxes):
                box = np.asarray(boxes[i], dtype=float).reshape(-1, 2).tolist()
            lines.append({"text": str(text), "score": score, "box": box})
        return lines

    for item in (result if isinstance(result, (list, tuple)) else []):
        if not item or len(item) < 2:
            continue
        text = str(item[1] or "")
        if not text.strip():
            continue
        box = np.asarray(item[0], dtype=float).reshape(-1, 2).tolist()
        score = float(item[2]) if len(item) > 2 else 0.0
        lines.append({"text": text, "score": score, "box": box})
    return lines


def _run_ocr(arr: np.ndarray) -> list[dict]:
    engine = _load_engine()
    return _normalize_result(engine(arr))


@app.on_event("startup")
def _preload():
    """后台预热：首次启动即加载引擎/模型，避免第一个请求承担初始化耗时。"""
    def _run():
        try:
            _load_engine()
        except Exception as exc:  # 预热失败不阻断服务，首次请求会再次尝试
            logger.warning("[ocr] preload failed: %s", type(exc).__name__)

    threading.Thread(target=_run, daemon=True, name="ocr-preload").start()


@app.get("/health")
def health():
    return {"status": "ok", "engine": "rapidocr"}


@app.post("/ocr")
async def ocr(req: OcrRequest, request: Request):
    t0 = time.time()
    arr, meta = _decode_image(req.imageBase64)
    try:
        lines = await asyncio.wait_for(
            run_in_threadpool(_run_ocr, arr), timeout=OCR_TIMEOUT_SEC)
    except asyncio.TimeoutError:
        logger.warning("[ocr] timeout after %.0fs size=%sx%s", OCR_TIMEOUT_SEC,
                       meta["width"], meta["height"])
        raise HTTPException(status_code=504, detail={
            "error": {"message": f"OCR 处理超时（>{int(OCR_TIMEOUT_SEC)}s）",
                      "type": "timeout_error", "code": "OCR_TIMEOUT"}})
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("[ocr] engine failure: %s", type(exc).__name__)
        raise HTTPException(status_code=503, detail={
            "error": {"message": "OCR 引擎不可用（模型加载或推理失败）",
                      "type": "server_error", "code": "OCR_ENGINE_UNAVAILABLE"}})

    text = "\n".join(ln["text"] for ln in lines)
    logger.info("[ocr] lang=%s image=%sx%s resized=%s lines=%d elapsed=%.0fms",
                req.lang, meta["width"], meta["height"], meta["resized"], len(lines),
                (time.time() - t0) * 1000)
    return {
        "text": text,
        "lines": lines,
        "engine": "rapidocr",
        "elapsedMs": int((time.time() - t0) * 1000),
    }


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
    logger.exception("[ocr] unhandled error")
    return JSONResponse(status_code=500, content={
        "error": {"message": "服务内部错误", "type": "server_error"}})

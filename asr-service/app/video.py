# -*- coding: utf-8 -*-
"""视频抽帧 / 抽音轨（ffmpeg 子进程，不引入 torch）。

约束：
  - 帧统一输出 jpg，长边 ≤1280（等比缩放，不放大）；
  - 音轨统一 16k / mono / pcm_s16le wav，便于 ASR；
  - 所有处理都带 max_seconds 硬截断（-t 放在 -i 之前，提前停止读流）。
"""
from __future__ import annotations

import json
import os
import subprocess

FFMPEG = os.environ.get("FFMPEG_BIN", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE_BIN", "ffprobe")
MAX_FRAME_SIDE = 1280


def _run(cmd: list[str], timeout: float = 120.0) -> subprocess.CompletedProcess:
    """执行子进程；二进制缺失/超时都退化为 returncode=1，由调用方按解析失败处理。"""
    try:
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=timeout, check=False)
    except (OSError, subprocess.SubprocessError):
        return subprocess.CompletedProcess(cmd, returncode=1, stdout=b"", stderr=b"")


def probe_media(path: str) -> dict:
    """返回 {durationSec: float|None, hasAudio: bool}；解析失败时长返回 None。"""
    result = _run([FFPROBE, "-v", "error", "-show_format", "-show_streams",
                   "-of", "json", path], timeout=30.0)
    if result.returncode != 0:
        return {"durationSec": None, "hasAudio": False}
    try:
        data = json.loads(result.stdout.decode("utf-8", "replace") or "{}")
    except ValueError:
        return {"durationSec": None, "hasAudio": False}

    duration = None
    raw_duration = (data.get("format") or {}).get("duration")
    if raw_duration is not None:
        try:
            duration = float(raw_duration)
        except (TypeError, ValueError):
            duration = None

    has_audio = any((s.get("codec_type") == "audio") for s in (data.get("streams") or []))
    return {"durationSec": duration, "hasAudio": has_audio}


def extract_frames(path: str, out_dir: str, fps: float, max_frames: int,
                   max_seconds: float) -> list[str]:
    """按 fps 抽帧并限制总帧数；返回按时间升序的 jpg 文件路径。"""
    scale = (f"scale='min({MAX_FRAME_SIDE},iw)':'min({MAX_FRAME_SIDE},ih)'"
             f":force_original_aspect_ratio=decrease")
    os.makedirs(out_dir, exist_ok=True)
    pattern = os.path.join(out_dir, "frame_%04d.jpg")
    cmd = [
        FFMPEG, "-nostdin", "-v", "error", "-y",
        "-t", f"{max_seconds:.3f}",
        "-i", path,
        "-vf", f"fps={fps},{scale}",
        "-frames:v", str(max_frames),
        "-q:v", "3",
        pattern,
    ]
    _run(cmd, timeout=max(60.0, max_seconds * 3))
    frames = [os.path.join(out_dir, name) for name in sorted(os.listdir(out_dir))
              if name.endswith(".jpg")]
    return frames[:max_frames]


def extract_audio_wav(path: str, out_path: str, max_seconds: float) -> bool:
    """抽取 16k 单声道 wav；源无音轨/失败返回 False。"""
    cmd = [
        FFMPEG, "-nostdin", "-v", "error", "-y",
        "-t", f"{max_seconds:.3f}",
        "-i", path,
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
        out_path,
    ]
    result = _run(cmd, timeout=max(60.0, max_seconds * 3))
    return result.returncode == 0 and os.path.exists(out_path) and os.path.getsize(out_path) > 44

"""把 RAG-Anything 的模型回调接到现有 ai-gateway。

为什么不直接用 `lightrag.llm.openai.openai_complete_if_cache`：
那套 helper 带 LightRAG 自己的磁盘缓存与重试语义，而本项目的统一出口是 ai-gateway
（它已经有硬件调度、限流、缓存、审计）。这里用轻量 httpx 直连网关的
OpenAI 兼容端点，行为可预测，也便于把网关侧的指标纳入现有 Prometheus。
"""
from __future__ import annotations

import logging
from typing import Any, Sequence

import httpx

from .config import settings

log = logging.getLogger("rag.models")


def _headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {settings.llm_api_key}",
        "Content-Type": "application/json",
    }


async def _post(path: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    url = f"{settings.llm_base_url.rstrip('/')}/{path.lstrip('/')}"
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.post(url, json=payload, headers=_headers())
        resp.raise_for_status()
        return resp.json()


def _flatten_messages(
    prompt: str,
    system_prompt: str | None,
    history_messages: Sequence[dict[str, Any]] | None,
) -> list[dict[str, Any]]:
    """把 (system, history, prompt) 折成 OpenAI messages。

    LightRAG 会传入多轮 history；网关是标准 chat/completions，直接透传即可。
    """
    msgs: list[dict[str, Any]] = []
    if system_prompt:
        msgs.append({"role": "system", "content": system_prompt})
    for m in history_messages or []:
        if isinstance(m, dict) and m.get("role") and m.get("content") is not None:
            msgs.append({"role": m["role"], "content": m["content"]})
    msgs.append({"role": "user", "content": prompt})
    return msgs


async def llm_complete(
    prompt: str,
    system_prompt: str | None = None,
    history_messages: Sequence[dict[str, Any]] | None = None,
    **kwargs: Any,
) -> str:
    """文本补全。LightRAG 要求返回纯字符串。"""
    payload = {
        "model": settings.llm_model,
        "messages": _flatten_messages(prompt, system_prompt, history_messages),
        "temperature": float(kwargs.get("temperature", 0.2)),
        "stream": False,
    }
    data = await _post("chat/completions", payload, settings.llm_timeout)
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError(f"网关返回空 choices: {data}")
    return (choices[0].get("message") or {}).get("content") or ""


async def vision_complete(
    prompt: str,
    system_prompt: str | None = None,
    history_messages: Sequence[dict[str, Any]] | None = None,
    image_data: str | None = None,
    messages: Sequence[dict[str, Any]] | None = None,
    **kwargs: Any,
) -> str:
    """多模态补全。

    为什么不走 `/v1/chat/completions`：本项目网关的该端点只接受**字符串** content，
    传 OpenAI 多模态数组（`[{"type":"text"},{"type":"image_url"}]`）会返回 422。
    网关另有专门的 `/v1/vision`，入参是 `{prompt, image_base64, mime, ...}`，
    内部走 llama.cpp 的 Qwen2.5-VL，并且带显存调度保护。

    因此这里做两件事：
      1. 从 messages 或 image_data 中提取出「文本 prompt」与「单张图片 base64」；
      2. 调 `/v1/vision`。

    限制：`/v1/vision` 一次只处理一张图。RAG-Anything 的多图消息会被降级为
    「只带第一张图 + 其余图片以占位文字说明」，避免静默丢图。
    """
    text_parts: list[str] = []
    image_b64 = image_data or ""
    mime = kwargs.get("mime_type") or "image/jpeg"
    extra_images = 0

    def _take(content: Any) -> None:
        nonlocal image_b64, extra_images
        if isinstance(content, str):
            text_parts.append(content)
            return
        if not isinstance(content, list):
            return
        for part in content:
            if not isinstance(part, dict):
                continue
            if part.get("type") == "text" and part.get("text"):
                text_parts.append(str(part["text"]))
            elif part.get("type") == "image_url":
                url = (part.get("image_url") or {}).get("url") or ""
                if url.startswith("data:") and ";base64," in url:
                    if not image_b64:
                        header, b64 = url.split(";base64,", 1)
                        mime = header[5:] or mime
                        image_b64 = b64
                    else:
                        extra_images += 1

    if messages:
        for m in messages:
            if not isinstance(m, dict):
                continue
            role = m.get("role")
            if role == "system" and isinstance(m.get("content"), str):
                system_prompt = system_prompt or m["content"]
                continue
            if role == "user":
                _take(m.get("content"))
    if prompt:
        text_parts.insert(0, prompt)

    if not image_b64:
        # 没有图片时退化为纯文本调用，避免把空图送给 VL 模型
        return await llm_complete("\n".join(text_parts), system_prompt, None, **kwargs)

    if extra_images:
        text_parts.append(f"(注：本次消息另含 {extra_images} 张图片，当前视觉通道一次只处理一张)")

    user_prompt = "\n".join(p for p in text_parts if p).strip() or "请描述这张图片。"
    if system_prompt:
        user_prompt = f"{system_prompt}\n\n{user_prompt}"

    payload = {
        "prompt": user_prompt,
        "image_base64": image_b64,
        "mime": mime,
        "model": settings.vision_model,
        "temperature": float(kwargs.get("temperature", 0.2)),
        "max_tokens": int(kwargs.get("max_tokens", 1024)),
    }
    data = await _post("vision", payload, settings.llm_timeout)
    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError(f"网关 /v1/vision 返回空 choices: {data}")
    return (choices[0].get("message") or {}).get("content") or ""


async def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量向量化。

    维度必须与现有约定一致（bge-m3 = 1024），否则写入 LightRAG 的向量无法与
    已有索引对齐。这里做一次显式校验，避免"维度悄悄变了但没人发现"。

    返回 numpy.ndarray 而不是 list：LightRAG 内部会对结果调用 `.size` / 矩阵运算，
    纯 Python list 会报 `'list' object has no attribute 'size'`。
    """
    import numpy as np

    if not texts:
        return np.zeros((0, settings.embed_dim), dtype=np.float32)
    out: list[list[float]] = []
    # 分批：网关侧 embedding 并发有限，一次全塞会排队超时
    batch = max(1, settings.embed_batch)
    for i in range(0, len(texts), batch):
        chunk = texts[i:i + batch]
        data = await _post(
            "embeddings",
            {"model": settings.embed_model, "input": chunk},
            settings.embed_timeout,
        )
        items = data.get("data") or []
        if len(items) != len(chunk):
            raise RuntimeError(f"embedding 数量不匹配: 期望 {len(chunk)} 实际 {len(items)}")
        for it in items:
            vec = it.get("embedding") or []
            if len(vec) != settings.embed_dim:
                raise RuntimeError(
                    f"embedding 维度不匹配: 期望 {settings.embed_dim} 实际 {len(vec)}；"
                    "请确认网关的 RAG_EMBED_MODEL 与既有 aikb 索引一致"
                )
            out.append(vec)
    return np.asarray(out, dtype=np.float32)

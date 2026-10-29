# -*- coding: utf-8 -*-
"""llama.cpp router 双模型探测（可选外部模型管理器客户端）。

背景（2026-10 实测）：
  - 宿主机 llama.cpp 以 **router 模式**运行（LLAMA_CPP_URL，默认 8123），
    GET /v1/models 同时返回两个 OpenAI 风格模型：
        qwen2.5-7b（文本，architecture.input_modalities=["text"]）
        qwen2.5-vl（视觉，architecture.input_modalities=["text","image"]）
    每个模型带 status.value = loaded / unloaded；router 以 --models-max 1 运行，
    请求里直接透传目标 model 名称即可按需加载并自动卸载另一个模型。
    因此网关**不需要**主动发加载/切换命令，ensure() 只做只读探测。
  - 老版本 llama.cpp（单模型）的 /v1/models 返回 data[0].id = gguf 文件路径/别名，
    没有 status 字段；本模块同样兼容。
  - MODEL_MANAGER_URL 为可选外部模型管理器（例如宿主机上的增强脚本）。
    只有显式配置时才探测；未配置/不可达时任何方法都立即返回，不阻塞、不报错。

本模块只做只读探测，任何网络异常都降级为“不可用”，不会向上抛异常。
"""
import json
import logging
import os
import time
import urllib.error
import urllib.request

logger = logging.getLogger(__name__)

KIND_TEXT = "text"
KIND_VL = "vl"


def _text(value) -> str:
    """bytes/None/其它 -> str。"""
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8", "replace")
        except Exception:  # pragma: no cover - 防御性
            return str(value)
    return "" if value is None else str(value)


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, "") or default)
    except (TypeError, ValueError):
        return default


def model_kind(model: str) -> str:
    """请求模型名 -> 本地模型种类（text|vl）。

    规则：名字里含 vl / vision / visual / image 视为视觉模型（qwen2.5-vl），
    其余（qwen2.5-7b、qwen2.5:7b、lanlink-ecommerce-rag、qwen2.5:0.5b 等）视为文本模型。
    """
    name = _text(model).strip().lower()
    if any(tag in name for tag in ("vl", "vision", "visual", "image")):
        return KIND_VL
    return KIND_TEXT


class ModelManager:
    """llama.cpp router 状态探测 + 可选外部模型管理器（只读，不阻塞）。"""

    def __init__(self):
        self.llama_url = (os.environ.get("LLAMA_CPP_URL") or "").strip().rstrip("/")
        self.manager_url = (os.environ.get("MODEL_MANAGER_URL") or "").strip().rstrip("/")
        self.text_model = (os.environ.get("LLAMA_MODEL_TEXT") or "qwen2.5-7b").strip()
        self.vl_model = (os.environ.get("LLAMA_MODEL_VL") or "qwen2.5-vl").strip()
        # 探测超时/缓存：避免模型或 manager 不可用时每个请求都卡住
        self.probe_timeout = _float_env("MODEL_PROBE_TIMEOUT", 1.5)
        self.probe_cache_sec = _float_env("MODEL_PROBE_CACHE", 5.0)
        self.probe_cooldown_sec = _float_env("MODEL_PROBE_COOLDOWN", 30.0)
        self.manager_timeout = _float_env("MODEL_MANAGER_TIMEOUT", 2.0)
        self.manager_cooldown_sec = _float_env("MODEL_MANAGER_COOLDOWN", 30.0)
        self._models_cache = None
        self._models_cache_ts = 0.0
        self._models_fail_until = 0.0
        self._manager_cache = None
        self._manager_cache_ts = 0.0
        self._manager_fail_until = 0.0

    # ------------------------------ 底层 HTTP ------------------------------

    @staticmethod
    def _get_json(url: str, timeout: float) -> dict:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
        return json.loads(raw) if raw.strip() else {}

    # ------------------------------ 模型列表 ------------------------------

    @staticmethod
    def parse_models(payload) -> list:
        """解析 /v1/models 响应，兼容 router 模式与单模型旧版。

        返回 [{"id": str, "kind": "text"|"vl", "loaded": bool}, ...]
        """
        if isinstance(payload, dict):
            items = payload.get("data") or payload.get("models") or []
        elif isinstance(payload, (list, tuple)):
            items = list(payload)
        else:
            return []
        models = []
        for item in items:
            if isinstance(item, (str, bytes)):
                mid, loaded, arch = _text(item), True, None
            elif isinstance(item, dict):
                mid = _text(item.get("id") or item.get("model") or item.get("name"))
                arch = item.get("architecture")
                status = item.get("status")
                if isinstance(status, dict):
                    loaded = _text(status.get("value")).lower() == "loaded"
                elif isinstance(status, str):
                    loaded = status.strip().lower() == "loaded"
                else:
                    # 旧版 llama.cpp 没有 status 字段：单模型即已加载
                    loaded = True
            else:
                continue
            if not mid:
                continue
            kind = model_kind(mid)
            if isinstance(arch, dict):
                modalities = [m for m in (arch.get("input_modalities") or [])
                              if isinstance(m, str)]
                if any(m.strip().lower() == "image" for m in modalities):
                    kind = KIND_VL
            models.append({"id": mid, "kind": kind, "loaded": bool(loaded)})
        return models

    def models(self, force: bool = False) -> list:
        """llama.cpp 当前可用模型列表（router：loaded+unloaded 都返回）。"""
        if not self.llama_url:
            return []
        now = time.time()
        if not force:
            if now < self._models_fail_until:
                return []
            if (self._models_cache is not None
                    and now - self._models_cache_ts < self.probe_cache_sec):
                return list(self._models_cache)
        try:
            payload = self._get_json(self.llama_url + "/v1/models", self.probe_timeout)
            parsed = self.parse_models(payload)
        except Exception as exc:  # 连不上/超时/JSON 异常都视为不可用
            logger.debug("llama.cpp /v1/models 探测失败: %s", exc)
            self._models_cache = None
            self._models_fail_until = now + self.probe_cooldown_sec
            return []
        self._models_cache = parsed
        self._models_cache_ts = time.time()
        self._models_fail_until = 0.0
        return list(parsed)

    def current_loaded(self, force: bool = False):
        """当前已加载的模型 id；router 模式下为 qwen2.5-7b / qwen2.5-vl。"""
        models = self.models(force=force)
        for item in models:
            if item["loaded"]:
                return item["id"]
        return None

    def available_kinds(self) -> dict:
        """router 中是否存在 text/vl 模型（unloaded 也算可用：请求时自动加载）。"""
        kinds = {KIND_TEXT: False, KIND_VL: False}
        for item in self.models():
            kinds[item["kind"]] = True
        return kinds

    def model_name_for(self, kind: str) -> str:
        return self.vl_model if kind == KIND_VL else self.text_model

    def resolve_model(self, kind: str):
        """在 router 模型列表中挑一个目标 id：优先 env 指定名，其次同 kind 的任意模型。

        返回 (model_id, {"id","kind","loaded"} | None)。
        """
        models = self.models()
        target = self.model_name_for(kind)
        for item in models:
            if item["id"] == target:
                return target, item
        for item in models:
            if item["kind"] == kind:
                return item["id"], item
        return target, None

    # ------------------------- 可选外部模型管理器 -------------------------

    def manager_status(self, force: bool = False):
        """GET {MODEL_MANAGER_URL}/status；未配置/不可达返回 None（带失败冷却）。"""
        if not self.manager_url:
            return None
        now = time.time()
        if not force:
            if now < self._manager_fail_until:
                return None
            if (self._manager_cache is not None
                    and now - self._manager_cache_ts < self.probe_cache_sec):
                return self._manager_cache
        try:
            status = self._get_json(self.manager_url + "/status", self.manager_timeout)
        except Exception as exc:
            logger.debug("model-manager /status 探测失败: %s", exc)
            self._manager_cache = None
            self._manager_fail_until = now + self.manager_cooldown_sec
            return None
        if not isinstance(status, dict):
            return None
        self._manager_cache = status
        self._manager_cache_ts = time.time()
        self._manager_fail_until = 0.0
        return status

    def manager_available(self) -> bool:
        return self.manager_status() is not None

    # ------------------------------ 路由信息 ------------------------------

    def ensure(self, kind: str) -> dict:
        """只读探测目标模型是否可用（不加载、不切换、不阻塞）。

        返回 {"ok", "swapped", "kind", "target", "loaded", "will_autoload",
              "router", "manager_available", "reason"}
        - router 模式：目标模型只要在 /v1/models 列表里（loaded 或 unloaded）即可用，
          unloaded 时 will_autoload=True，由 router 在请求时自动加载；
        - 外部 manager 可用且其 current 等于 kind 时也视为可用；
        - 其余情况 ok=False，调用方自行降级（用当前模型 / Ollama / mock）。
        """
        kind = KIND_VL if kind == KIND_VL else KIND_TEXT
        target, matched = self.resolve_model(kind)
        models = self.models()
        status = self.manager_status()
        info = {
            "ok": False,
            "swapped": False,          # router 自动切换，网关不主动 switch
            "kind": kind,
            "target": target,
            "loaded": self.current_loaded(),
            "will_autoload": False,
            "router": bool(models),
            "manager_available": status is not None,
            "reason": None,
        }
        if matched is not None:
            info["ok"] = True
            info["target"] = matched["id"]
            info["will_autoload"] = not matched["loaded"]
            return info
        if status is not None and _text(status.get("current")).strip().lower() == kind:
            info.update(ok=True, target=target, loaded=target)
            return info
        info["reason"] = "llamacpp_unreachable" if not models else "model_not_listed"
        return info

    def routing_status(self) -> dict:
        """给 /v1/models 与 /health 用的模型路由快照。"""
        models = self.models()
        loaded = self.current_loaded()
        available = {KIND_TEXT: False, KIND_VL: False}
        for item in models:
            available[item["kind"]] = True
        status = self.manager_status()
        manager_available = status is not None
        manager_current = _text(status.get("current")).strip().lower() if status else ""
        if manager_current in (KIND_TEXT, KIND_VL):
            available[manager_current] = True
        loaded_kind = None
        if loaded:
            loaded_kind = KIND_VL if model_kind(loaded) == KIND_VL else KIND_TEXT
        return {
            "llama_cpp_url": self.llama_url or None,
            "router": bool(models),
            "current_loaded": loaded,
            "loaded_kind": loaded_kind,
            "manager_url": self.manager_url or None,
            "manager_available": manager_available,
            "manager_current": manager_current or None,
            "available": available,
            "text_model": self.text_model,
            "vl_model": self.vl_model,
            "models": [{"id": m["id"], "kind": m["kind"], "loaded": m["loaded"]}
                       for m in models],
        }

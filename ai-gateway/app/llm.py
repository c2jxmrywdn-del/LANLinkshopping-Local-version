# -*- coding: utf-8 -*-
"""本地大模型推理客户端（llama.cpp router 双模型 + Ollama + mock 兜底）。

后端优先级（LLM_BACKEND=llamacpp|ollama|auto|mock）：
  1. llama.cpp router（LLAMA_CPP_URL）：/v1/models 同时暴露 qwen2.5-7b（文本）与
     qwen2.5-vl（视觉），请求里透传目标 model 名称即可按需加载/切换（--models-max 1），
     网关不做主动 load/switch；chat_vision 固定走 qwen2.5-vl，超时默认 300s。
  2. Ollama（OLLAMA_BASE_URL，默认 ai-gateway compose 内 ollama 服务）。
  3. mock：离线兜底（基于知识库摘录作答），保证模型未就绪时链路仍可验证。

说明：每次推理都会在 self.last_route 记录本次路由信息（requested_model/model_kind/
resolved_model/swapped/autoload/queued/queue_wait_ms/lock_timeout 等），main.py 会把它们
并入 OpenAI 响应的 lanlink 扩展。

并发控制：单 GPU 且 llama.cpp router 为 --models-max 1，并发 text + VL 会触发 router
反复卸载/加载模型（实测 text 被拖到 40s+）。因此 llama.cpp 的 ensure + 推理通过
ModelCallGate 线程级全局锁串行化：text 请求优先、VL 让行；锁等待带超时
（LLAMA_LOCK_TIMEOUT，默认 180s），超时降级 Ollama/mock 或返回明确错误文本，不挂死、不 500。
"""
import json
import logging
import os
import re
import threading
import time
import urllib.error
import urllib.request

import jieba

from .model_manager import KIND_TEXT, KIND_VL, ModelManager, model_kind
from .hardware_scheduler import HardwareScheduler

logger = logging.getLogger(__name__)


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, "") or default)
    except (TypeError, ValueError):
        return default


class ModelCallGate:
    """llama.cpp 线程级全局调用锁（单 GPU / router --models-max 1 串行化）。

    背景：宿主机 llama.cpp router 同一时刻只能加载一个模型；并发 text + VL 请求时
    router 会反复卸载/加载模型，实测并发下 text 请求被拖到 40s+ 甚至失败。
    因此在 ensure + 推理期间必须串行；排队规则：
      1. text 请求优先：存在进行中的 text 请求（begin_text_request 标记）或排队中的
         text 调用时，VL 调用让行（最多等到自身超时）；
      2. 锁等待带超时（LLAMA_LOCK_TIMEOUT，默认 180s）：超时后调用方降级
         （text -> Ollama/mock，vision -> 明确错误文本），绝不挂死或 500；
      3. 排队信息（queued/queue_wait_ms/lock_timeout）记录进 last_route 并由
         /health 的 llm.gate 暴露，便于观测。
    """

    def __init__(self):
        self._cv = threading.Condition()
        self._locked = False
        self._holder = None
        self._held_since = None
        self._waiters_text = 0
        self._waiters_vl = 0
        self._active_text_requests = 0

    def begin_text_request(self) -> None:
        with self._cv:
            self._active_text_requests += 1

    def end_text_request(self) -> None:
        with self._cv:
            self._active_text_requests = max(0, self._active_text_requests - 1)
            self._cv.notify_all()

    def acquire(self, kind: str, timeout: float):
        """获取调用权；返回 (acquired, waited_sec)。kind=text|vl。"""
        kind = KIND_VL if kind == KIND_VL else KIND_TEXT
        deadline = time.time() + max(0.0, timeout)
        started = time.time()
        with self._cv:
            if kind == KIND_VL:
                self._waiters_vl += 1
            else:
                self._waiters_text += 1
            try:
                while True:
                    remaining = deadline - time.time()
                    if remaining <= 0:
                        return False, time.time() - started
                    can_take = not self._locked
                    if can_take and kind == KIND_VL:
                        # VL 让行于 text 请求/排队中的 text 调用（避免 text 被 VL 冷加载拖慢）
                        can_take = (self._active_text_requests == 0
                                    and self._waiters_text == 0)
                    if can_take:
                        self._locked = True
                        self._holder = kind
                        self._held_since = time.time()
                        return True, time.time() - started
                    self._cv.wait(timeout=min(remaining, 0.2))
            finally:
                if kind == KIND_VL:
                    self._waiters_vl -= 1
                else:
                    self._waiters_text -= 1

    def release(self) -> None:
        with self._cv:
            self._locked = False
            self._holder = None
            self._held_since = None
            self._cv.notify_all()

    def status(self) -> dict:
        with self._cv:
            held = self._holder is not None
            return {
                "busy": held,
                "holder": self._holder,
                "waiters_text": self._waiters_text,
                "waiters_vl": self._waiters_vl,
                "active_text_requests": self._active_text_requests,
                "held_ms": int((time.time() - self._held_since) * 1000)
                if held and self._held_since else 0,
            }


class OllamaBackend:
    """Ollama 本地推理后端（OpenAI 兼容层内部调用 /api/chat）。"""

    def __init__(self, base_url: str, model: str):
        self.base_url = base_url.rstrip("/")
        self.model = model

    def available(self) -> bool:
        try:
            with urllib.request.urlopen(self.base_url + "/api/tags", timeout=5) as r:
                data = json.loads(r.read().decode("utf-8"))
            names = [m.get("name", "") for m in data.get("models", [])]
            return any(self.model.split(":")[0] in n for n in names)
        except Exception:
            return False

    def chat(self, messages: list, temperature: float = 0.3, max_tokens: int = 512) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            # 采样收紧（与 llama.cpp 后端一致）：top_p 收窄尾部分布、repeat_penalty 抑制复读
            "options": {
                "temperature": temperature,
                "num_predict": max_tokens,
                "top_p": _float_env("LLAMA_TOP_P", 0.9),
                "repeat_penalty": _float_env("LLAMA_REPEAT_PENALTY", 1.05),
            },
        }
        req = urllib.request.Request(
            self.base_url + "/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read().decode("utf-8"))
        return data.get("message", {}).get("content", "").strip()

    def chat_raw(self, messages, temperature=0.3, max_tokens=512, tools=None, tool_choice=None):
        """工具调用版：走 Ollama OpenAI 兼容层 /v1（tools 的 arguments 与 llama.cpp 一致为字符串，
        避免原生 /api/chat 的对象形参与主链路结构不一致）。返回 (message, finish_reason)。"""
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "top_p": _float_env("LLAMA_TOP_P", 0.9),
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = tool_choice or "auto"
        req = urllib.request.Request(
            self.base_url.rstrip("/") + "/v1/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=180) as r:
            data = json.loads(r.read().decode("utf-8"))
        choices = data.get("choices") or []
        if not choices:
            return {"role": "assistant", "content": ""}, "stop"
        choice = choices[0]
        return (choice.get("message") or {"role": "assistant", "content": ""}), \
            choice.get("finish_reason") or "stop"


class MockBackend:
    """离线兜底后端：从知识库上下文中抽取句子组装回答，用于链路可用性验证。"""

    def available(self) -> bool:
        return True

    def chat(self, messages: list, temperature: float = 0.3, max_tokens: int = 512) -> str:
        system = next((m["content"] for m in messages if m["role"] == "system"), "")
        user = next((m["content"] for m in messages if m["role"] == "user"), "")
        m = re.search(r"用户问题：(.+)", user)
        question = m.group(1).strip() if m else user
        ctx = re.search(r"【知识库内容】\n(.*?)\n——【知识库内容结束】——", system, re.S)
        if not ctx:
            return "（知识库为空）来源：无"
        q_tokens = set(jieba.cut(question))
        best, best_score, best_title = "", 0.0, ""
        for block in ctx.group(1).split("\n\n"):
            if "：" not in block:
                continue
            title_part, content = block.split("：", 1)
            score = sum(1 for t in q_tokens if len(t) > 1 and t in block)
            if score > best_score:
                best, best_score, best_title = content, score, title_part.strip("《》（）分类：")
        if not best:
            return "该问题超出知识库范围，可转人工客服（工作日 9:00-18:00）。来源：无"
        sentences = [s.strip() for s in re.split(r"[。；]", best) if s.strip()]
        picked = [s for s in sentences if any(t in s for t in q_tokens if len(t) > 1)][:3] or sentences[:2]
        return "；".join(picked) + f"。来源：《{best_title}》"


class LlamaCppBackend:
    """llama.cpp llama-server（router 模式）后端，OpenAI 兼容 /v1/chat/completions。"""

    def __init__(self, base_url: str, model: str | None = None):
        self.base_url = base_url.rstrip("/")
        self.model = model or "qwen2.5-7b"
        self.timeout = _float_env("LLAMA_TIMEOUT", 240.0)
        self.vision_timeout = _float_env("LLAMA_VISION_TIMEOUT", 300.0)
        # 采样收紧（"提高注意力"）：top_p 0.9 收窄尾部分布降低跑偏，repeat_penalty 1.05 事前防复读
        self.top_p = _float_env("LLAMA_TOP_P", 0.9)
        self.repeat_penalty = _float_env("LLAMA_REPEAT_PENALTY", 1.05)

    def available(self) -> bool:
        try:
            with urllib.request.urlopen(self.base_url + "/health", timeout=2) as r:
                return r.status == 200
        except Exception:
            return False

    def _post(self, messages, temperature, max_tokens, model=None, timeout=None) -> str:
        payload = {
            "model": model or self.model,
            "messages": messages,
            "stream": False,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "top_p": self.top_p,
            "repeat_penalty": self.repeat_penalty,
        }
        req = urllib.request.Request(
            self.base_url + "/v1/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        choices = data.get("choices") or []
        if not choices:
            return ""
        return (choices[0].get("message") or {}).get("content", "").strip()

    def chat_raw(self, messages, temperature=0.3, max_tokens=512, model=None, timeout=None,
                 tools=None, tool_choice=None):
        """工具调用版：返回 (完整 message dict, finish_reason)；message 可能含 tool_calls。

        tools/tool_choice 只在非空时携带（llama.cpp b11913 实测：tool_choice 仅支持
        none/auto/required 字符串，对象形式会 400 降级）。
        模型生成的 tool_call JSON 被 max_tokens 截断时 llama-server 会 500（#16420 形态）——
        此时去掉工具重试一轮，基于已回灌的工具结果直接续写文本。
        """
        try:
            return self._post_raw(messages, temperature, max_tokens, model=model,
                                  timeout=timeout, tools=tools, tool_choice=tool_choice)
        except urllib.error.HTTPError as exc:
            body = ""
            try:
                body = exc.read().decode("utf-8", "replace")[:400]
            except Exception:  # pragma: no cover - 防御性
                pass
            if exc.code == 500 and tools and "parse" in body.lower():
                logger.warning("tool_call JSON 解析 500（截断形态），去工具重试一轮: %s", body[:160])
                return self._post_raw(messages, temperature, min(max_tokens, 768),
                                      model=model, timeout=timeout)
            raise

    def _post_raw(self, messages, temperature, max_tokens, model=None, timeout=None,
                  tools=None, tool_choice=None):
        payload = {
            "model": model or self.model,
            "messages": messages,
            "stream": False,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "top_p": self.top_p,
            "repeat_penalty": self.repeat_penalty,
        }
        if tools:
            payload["tools"] = tools
            if tool_choice:
                payload["tool_choice"] = tool_choice
        req = urllib.request.Request(
            self.base_url + "/v1/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        choices = data.get("choices") or []
        if not choices:
            return {"role": "assistant", "content": ""}, "stop"
        choice = choices[0]
        return (choice.get("message") or {"role": "assistant", "content": ""}), \
            choice.get("finish_reason") or "stop"

    def chat(self, messages, temperature=0.3, max_tokens=512, model=None, timeout=None) -> str:
        return self._post(messages, temperature, max_tokens, model=model, timeout=timeout)

    def chat_vision(self, prompt, image_b64, mime="image/jpeg", temperature=0.3,
                    max_tokens=512, model=None, timeout=None) -> str:
        messages = [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image_b64}"}}]}]
        return self._post(messages, temperature, max_tokens, model=model,
                          timeout=timeout or self.vision_timeout)


class LLMClient:
    """统一入口：llama.cpp router（双模型按需加载）→ Ollama → mock。"""

    def __init__(self):
        self.backend_pref = (os.environ.get("LLM_BACKEND") or "auto").strip().lower()
        self.manager = ModelManager()
        self.llama = LlamaCppBackend(self.manager.llama_url, self.manager.text_model)
        self.ollama = OllamaBackend(
            os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434"),
            os.environ.get("LLM_MODEL", "qwen2.5:0.5b"))
        self.mock = MockBackend()
        self.text_model = self.manager.text_model
        self.vl_model = self.manager.vl_model
        # 单 GPU 串行调用锁 + 排队超时（超时降级，不挂死、不 500）
        self.gate = ModelCallGate()
        self.lock_timeout = _float_env("LLAMA_LOCK_TIMEOUT", 180.0)
        # 硬件调度 + 智能模型切换：GPU 优先、长文本固定文本模型、显存不足时 CPU 轻量辅助
        self.scheduler = HardwareScheduler(self.manager)
        # 兼容旧接口：默认文本模型
        self.model = self.text_model
        self.last_route = {}
        self._last_backend = None

    # ------------------------------ 辅助 ------------------------------

    def begin_text_request(self) -> None:
        """标记一个 text HTTP 请求开始（让并发 VL 请求让行，text 优先拿 GPU）。"""
        self.gate.begin_text_request()

    def end_text_request(self) -> None:
        """标记 text HTTP 请求结束（唤等待中的 VL 请求）。"""
        self.gate.end_text_request()

    @property
    def base_url(self) -> str:
        if self.backend_pref in ("llamacpp", "auto"):
            return self.llama.base_url
        return self.ollama.base_url

    def _llama_models(self) -> list:
        return self.manager.models()

    def _resolve_model(self, kind: str, requested: str | None = None) -> str:
        models = self._llama_models()
        if requested:
            for item in models:
                if item["id"] == requested:
                    return requested
        target, matched = self.manager.resolve_model(kind)
        if matched is not None:
            return target
        return requested or target

    def backend_name(self) -> str:
        """当前应使用的后端名（兼容旧接口）。"""
        if self.backend_pref == "mock":
            return "mock"
        if self.backend_pref in ("llamacpp", "auto") and self._llama_models():
            return "llamacpp"
        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            return "ollama"
        return "mock"

    def routing_status(self) -> dict:
        """模型路由快照：/v1/models 与 /health 复用。"""
        status = self.manager.routing_status()
        status["backend_pref"] = self.backend_pref
        status["text_model"] = self.text_model
        status["vl_model"] = self.vl_model
        return status

    # ------------------------------ 硬件调度 ------------------------------

    def schedule_text(self, prompt_chars: int, requested_model: str | None = None) -> dict:
        """文本请求的硬件感知调度：GPU 优先；显存不足时 CPU 轻量辅助。"""
        try:
            return self.scheduler.decide_text(prompt_chars, requested_model)
        except Exception as exc:  # 调度失败绝不影响推理：回退文本模型
            logger.warning("硬件调度失败，回退文本模型: %s", exc)
            return {"mode": "fallback", "model": self.text_model, "reason": "scheduler_error",
                    "long_text": False, "allow": True, "vram": {}}

    def schedule_vision(self) -> dict:
        """视觉请求的硬件感知调度：显存足够才加载 VL。"""
        try:
            return self.scheduler.decide_vision()
        except Exception as exc:
            logger.warning("视觉硬件调度失败，按默认尝试: %s", exc)
            return {"mode": "gpu", "model": self.vl_model, "reason": "scheduler_error",
                    "long_text": False, "allow": True, "vram": {}}

    def hardware_status(self) -> dict:
        data = self.scheduler.summary()
        data["backend"] = self.backend_name()
        data["lock_timeout_sec"] = self.lock_timeout
        data["gate"] = self.gate.status()
        return data

    def health(self) -> dict:
        status = self.routing_status()
        try:
            hardware = self.scheduler.summary()
        except Exception:
            hardware = {}
        return {
            "backend": self.backend_name(),
            "model": self.text_model,
            "text_model": self.text_model,
            "vl_model": self.vl_model,
            "current_loaded": status.get("current_loaded"),
            "loaded_kind": status.get("loaded_kind"),
            "manager_available": status.get("manager_available"),
            "manager_url": status.get("manager_url"),
            "llama_cpp_url": status.get("llama_cpp_url"),
            "ollama_url": self.ollama.base_url,
            "router": status.get("router"),
            "available": status.get("available"),
            "lock_timeout_sec": self.lock_timeout,
            "gate": self.gate.status(),
            "last_route": dict(self.last_route or {}),
            "hardware": hardware,
        }

    def _record_route(self, kind, requested, target, ensure, extra=None) -> dict:
        route = {
            "requested_model": requested,
            "model_kind": kind,
            "resolved_model": target,
            "swapped": bool((ensure or {}).get("swapped")),
            "autoload": bool((ensure or {}).get("will_autoload")),
            "ensure_ok": bool((ensure or {}).get("ok")),
            "current_loaded": (ensure or {}).get("loaded"),
            "reason": (ensure or {}).get("reason"),
        }
        if extra:
            route.update(extra)
        self.last_route = route
        return route

    # ------------------------------ 文本推理 ------------------------------

    def chat(self, messages, temperature=0.3, max_tokens=512, model=None):
        """返回 (text, backend_label, latency_sec)；按请求 model 路由到 text/vl。

        单 GPU 约束：llama.cpp 的 ensure + 推理在全局锁内串行，避免并发请求让
        router 反复卸载/加载模型；锁等待超时则降级 Ollama/mock（route.lock_timeout=True），
        保证不挂死、不 500。
        """
        start = time.time()
        kind = model_kind(model) if model else KIND_TEXT
        if self.backend_pref == "mock":
            self._record_route(kind, model, None, {"reason": "mock_backend"})
            self._last_backend = "mock"
            return self.mock.chat(messages, temperature, max_tokens), "mock", time.time() - start

        models = self._llama_models()
        if self.backend_pref in ("llamacpp", "auto") and models:
            acquired, waited = self.gate.acquire(kind, self.lock_timeout)
            try:
                if not acquired:
                    self._record_route(
                        kind, model, None, {"ok": False, "reason": "lock_timeout"},
                        {"lock_timeout": True, "queued": True,
                         "queue_wait_ms": int(waited * 1000)})
                    logger.warning("llama.cpp 模型锁等待超时（%.1fs），降级 Ollama/mock", waited)
                else:
                    # ensure + 推理在同一把锁内，串行化避免 router 反复换模型
                    ensure = self.manager.ensure(kind)
                    live_models = self._llama_models()
                    target = self._resolve_model(kind, model)
                    route_extra = {"queued": waited > 0.05,
                                   "queue_wait_ms": int(waited * 1000)}
                    self._record_route(kind, model, target, ensure, route_extra)
                    if live_models:
                        try:
                            text = self.llama.chat(messages, temperature, max_tokens,
                                                   model=target)
                            self._last_backend = "llamacpp"
                            return text, "llamacpp:" + target, time.time() - start
                        except urllib.error.HTTPError as exc:
                            body = ""
                            try:
                                body = exc.read().decode("utf-8", "replace")[:300]
                            except Exception:  # pragma: no cover - 防御性
                                pass
                            logger.warning("llamacpp 推理失败 HTTP %s model=%s: %s",
                                           exc.code, target, body)
                            if exc.code == 400:
                                # 兼容老版本 llama.cpp：模型名不匹配时用当前已加载模型重试一次
                                alt = self.manager.current_loaded(force=True)
                                if alt and alt != target:
                                    try:
                                        text = self.llama.chat(
                                            messages, temperature, max_tokens, model=alt)
                                        self._record_route(
                                            kind, model, alt, ensure,
                                            dict(route_extra, model_fallback=True))
                                        self._last_backend = "llamacpp"
                                        return text, "llamacpp:" + alt, time.time() - start
                                    except Exception as retry_exc:
                                        logger.warning("llamacpp 重试失败: %s", retry_exc)
                        except Exception as exc:
                            logger.warning("llamacpp 推理失败: %s", exc)
            finally:
                self.gate.release()

        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            try:
                text = self.ollama.chat(messages, temperature, max_tokens)
                self._last_backend = "ollama"
                return text, "ollama:" + self.ollama.model, time.time() - start
            except Exception as exc:
                logger.warning("ollama 推理失败: %s", exc)
        self._last_backend = "mock"
        return self.mock.chat(messages, temperature, max_tokens), "mock", time.time() - start

    def chat_raw(self, messages, temperature=0.3, max_tokens=512, model=None,
                 tools=None, tool_choice=None):
        """工具调用版推理：返回 (message dict, finish_reason, backend_label, latency)。

        与 chat() 相同的降级链（llamacpp → ollama[auto/ollama 时] → mock），但保留完整
        message（含 tool_calls）与 finish_reason；agent 循环在主链路之外逐轮调用，
        每轮各自进出 ModelCallGate（工具执行发生在锁外，不嵌套取锁）。
        """
        start = time.time()
        kind = model_kind(model) if model else KIND_TEXT
        if self.backend_pref == "mock":
            self._record_route(kind, model, None, {"reason": "mock_backend"})
            self._last_backend = "mock"
            return ({"role": "assistant", "content": self.mock.chat(messages, temperature, max_tokens)},
                    "stop", "mock", time.time() - start)

        models = self._llama_models()
        if self.backend_pref in ("llamacpp", "auto") and models:
            acquired, waited = self.gate.acquire(kind, self.lock_timeout)
            try:
                if acquired:
                    ensure = self.manager.ensure(kind)
                    live_models = self._llama_models()
                    target = self._resolve_model(kind, model)
                    self._record_route(kind, model, target, ensure,
                                       {"queued": waited > 0.05,
                                        "queue_wait_ms": int(waited * 1000)})
                    if live_models:
                        try:
                            msg, finish = self.llama.chat_raw(
                                messages, temperature, max_tokens, model=target,
                                tools=tools, tool_choice=tool_choice)
                            self._last_backend = "llamacpp"
                            return msg, finish, "llamacpp:" + target, time.time() - start
                        except Exception as exc:
                            logger.warning("llamacpp chat_raw 失败: %s", exc)
            finally:
                self.gate.release()

        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            try:
                msg, finish = self.ollama.chat_raw(messages, temperature, max_tokens,
                                                   tools=tools, tool_choice=tool_choice)
                self._last_backend = "ollama"
                return msg, finish, "ollama:" + self.ollama.model, time.time() - start
            except Exception as exc:
                logger.warning("ollama chat_raw 失败: %s", exc)
        self._last_backend = "mock"
        return ({"role": "assistant", "content": self.mock.chat(messages, temperature, max_tokens)},
                "stop", "mock", time.time() - start)

    # ------------------------------ 视觉推理 ------------------------------

    def chat_vision(self, prompt, image_b64, mime="image/jpeg", temperature=0.3, max_tokens=512):
        """VL 推理：router 自动加载 qwen2.5-vl；与文本推理串行（单 GPU）。

        - 先让行于并发 text 请求（VL 冷加载约 10-30s、推理 40-50s，text 优先）；
        - 模型锁等待超时/后端不可用都返回明确错误文本（HTTP 仍 200，不抛异常）。
        """
        start = time.time()
        models = self._llama_models()
        if not models:
            self._record_route(KIND_VL, self.vl_model, None,
                               {"ok": False, "reason": "llamacpp_unreachable"})
            return ("（视觉后端不可用：llama.cpp router 未就绪，"
                    "请确认 LLAMA_CPP_URL 上的 llama-server 在运行）"), "none", time.time() - start

        # 给同时到达的 text 请求一个优先窗口：VL 本来就要 40s+，这点延迟可忽略，
        # 但能让并发 text 请求先拿到 GPU（避免被 VL 冷加载拖到 40s+）。
        yield_sec = _float_env("LLAMA_VISION_YIELD_SEC", 0.3)
        if yield_sec > 0:
            time.sleep(yield_sec)

        acquired, waited = self.gate.acquire(KIND_VL, self.lock_timeout)
        try:
            if not acquired:
                self._record_route(KIND_VL, self.vl_model, None,
                                   {"ok": False, "reason": "lock_timeout"},
                                   {"lock_timeout": True, "queued": True,
                                    "queue_wait_ms": int(waited * 1000)})
                logger.warning("视觉请求模型锁等待超时（%.1fs）", waited)
                return ("（视觉请求排队超时：本地 GPU 正被文本推理占用，请稍后重试）"), "none", time.time() - start
            ensure = self.manager.ensure(KIND_VL)
            live_models = self._llama_models()
            target = self._resolve_model(KIND_VL, self.vl_model)
            self._record_route(KIND_VL, self.vl_model, target, ensure,
                               {"queued": waited > 0.05,
                                "queue_wait_ms": int(waited * 1000)})
            if not live_models:
                return ("（视觉后端不可用：llama.cpp router 未就绪）"), "none", time.time() - start
            has_vl = any(item["kind"] == KIND_VL for item in live_models)
            if not has_vl and not ensure.get("ok"):
                return (f"（视觉模型不可用：llama.cpp 未提供视觉模型（需要 {self.vl_model}），"
                        f"当前已加载 {ensure.get('loaded') or '未知'}）"), "none", time.time() - start
            try:
                text = self.llama.chat_vision(
                    prompt, image_b64, mime, temperature, max_tokens, model=target)
                self._last_backend = "llamacpp"
                return text, "llamacpp:" + target, time.time() - start
            except urllib.error.HTTPError as exc:
                body = ""
                try:
                    body = exc.read().decode("utf-8", "replace")[:300]
                except Exception:  # pragma: no cover - 防御性
                    pass
                logger.warning("llamacpp 视觉推理失败 HTTP %s: %s", exc.code, body)
                return (f"（视觉模型调用失败：HTTP {exc.code} {body}）"), "none", time.time() - start
            except Exception as exc:
                logger.warning("llamacpp 视觉推理失败: %s", exc)
                return (f"（视觉模型调用失败：{exc}）"), "none", time.time() - start
        finally:
            self.gate.release()


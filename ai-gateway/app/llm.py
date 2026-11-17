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
from .rate_limiter import CircuitBreaker
from .fallback_engine import FALLBACK_ENGINE

logger = logging.getLogger(__name__)


class ContextOverflowError(RuntimeError):
    """Prompt 超出 llama.cpp 的 `n_ctx`。

    单独定义异常类型的原因：这个错误必须能被 `main.py` 精确识别并转成 **413**，
    而不是混进「GPU 不可用」或「服务内部错误」。

    背景：SQLBot 的 Text-to-SQL 会把 155 张表的 schema 拼进 prompt，
    实测约 73k tokens，而 llama.cpp 的 `n_ctx_slot = 16384`。
    历史上这个 400 被吞掉后抛出「GPU 推理不可用」，最终变成 500
    「服务内部错误」——调用方完全看不出是 prompt 太长。
    """

    def __init__(self, actual_tokens: int | None, limit_tokens: int | None,
                 model: str | None = None):
        self.actual_tokens = actual_tokens
        self.limit_tokens = limit_tokens
        self.model = model
        parts = ["prompt 超出模型上下文窗口"]
        if actual_tokens is not None:
            parts.append(f"请求 {actual_tokens} tokens")
        if limit_tokens is not None:
            parts.append(f"上限 {limit_tokens} tokens")
        if model:
            parts.append(f"模型 {model}")
        super().__init__("，".join(parts))


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, "") or default)
    except (TypeError, ValueError):
        return default


# 与 main.py 流式分支的历史切句规则保持一致（按句末标点/换行切）。
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？\n])")


def _split_pieces(text: str) -> list:
    """把整段文本切成句子片段。

    只用于**没有增量接口**的后端（mock / Ollama 降级）：它们必须先生成全文，
    网关再切句。这类降级路径的首片延迟≈总时长，属于后端能力限制，
    不是网关在攒包——真流式只对 llama.cpp 的 SSE 生效。
    """
    parts = [p for p in _SENTENCE_SPLIT_RE.split(text or "") if p]
    return parts or ([text] if text else [])


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
    """离线兜底后端：结合启发式规则降级引擎与离线知识库，实现高可用业务兜底。"""

    def available(self) -> bool:
        return True

    def chat(self, messages: list, temperature: float = 0.3, max_tokens: int = 512, role: str = "buyer") -> str:
        user = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
        question = str(user or "").strip()
        m = re.search(r"用户问题：(.+)", question)
        if m:
            question = m.group(1).strip()
        res = FALLBACK_ENGINE.handle(question, messages=messages, role=role)
        return res.get("text", "")


class LlamaCppBackend:
    """llama.cpp llama-server（router 模式）后端，OpenAI 兼容 /v1/chat/completions。"""

    def __init__(self, base_url: str, model: str | None = None):
        self.base_url = base_url.rstrip("/")
        self.model = model or "qwen2.5-7b"
        # 快速失败与超时保护：大模型常规推理超时默认 3.0s（避免故障时拖垮网关），可通过 LLAMA_FAST_TIMEOUT 调控
        self.timeout = _float_env("LLAMA_FAST_TIMEOUT", 3.0)
        self.vision_timeout = _float_env("LLAMA_VISION_TIMEOUT", 300.0)
        # 采样收紧（"提高注意力"）：top_p 0.9 收窄尾部分布降低跑偏，repeat_penalty 1.05 事前防复读
        self.top_p = _float_env("LLAMA_TOP_P", 0.9)
        self.repeat_penalty = _float_env("LLAMA_REPEAT_PENALTY", 1.05)
        # 熔断器：连续 3 次失败触发熔断 OPEN，15 秒恢复期内试探探测，避免每请求死等超时
        self.circuit_breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=15.0)

    def available(self) -> bool:
        try:
            with urllib.request.urlopen(self.base_url + "/health", timeout=2) as r:
                return r.status == 200
        except Exception:
            return False

    def _post(self, messages, temperature, max_tokens, model=None, timeout=None) -> str:
        if not self.circuit_breaker.allow_request():
            raise urllib.error.URLError("llama-server 处于熔断保护状态 (OPEN)，快速失败切流")
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
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                data = json.loads(r.read().decode("utf-8", "replace"))
            self.circuit_breaker.record_success()
        except Exception:
            self.circuit_breaker.record_failure()
            raise
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
            # 上下文超限同样要显式化：`chat_raw` 原样 `raise` 会让它变成 500
            # 「服务内部错误」，调用方看不出是 prompt 太长（详见 ContextOverflowError 注释）。
            if exc.code == 400 and ("exceed_context_size" in body or "context size" in body):
                m = re.search(r"\((\d+) tokens\)", body)
                m2 = re.search(r"context size \((\d+) tokens\)", body)
                raise ContextOverflowError(
                    int(m.group(1)) if m else None,
                    int(m2.group(1)) if m2 else None,
                    model,
                ) from exc
            raise

    def _post_raw(self, messages, temperature, max_tokens, model=None, timeout=None,
                  tools=None, tool_choice=None):
        if not self.circuit_breaker.allow_request():
            raise urllib.error.URLError("llama-server 处于熔断保护状态 (OPEN)，快速失败切流")
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
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                data = json.loads(r.read().decode("utf-8", "replace"))
            self.circuit_breaker.record_success()
        except Exception:
            self.circuit_breaker.record_failure()
            raise
        choices = data.get("choices") or []
        if not choices:
            return {"role": "assistant", "content": ""}, "stop"
        choice = choices[0]
        return (choice.get("message") or {"role": "assistant", "content": ""}), \
            choice.get("finish_reason") or "stop"

    def chat(self, messages, temperature=0.3, max_tokens=512, model=None, timeout=None) -> str:
        return self._post(messages, temperature, max_tokens, model=model, timeout=timeout)

    def _post_stream(self, messages, temperature, max_tokens, model=None, timeout=None,
                     sink: dict | None = None):
        """SSE 增量推理：逐行读 `data: {...}`，产出 `choices[0].delta.content` 文本片段。

        与 `_post()` 的差异只在传输层：payload `"stream": True`，响应是
        `text/event-stream`，llama.cpp 每生成若干 token 就 flush 一行，
        实测首个 delta 在 0.17s 到达（整段 11.4s），因此调用方可以边收边发。

        熔断器语义与 `_post()` 一致：
          - 请求**建立阶段**失败（熔断 OPEN / 连接失败 / 4xx）→ 记录失败并抛出，
            调用方按「后端不可用」处理（main.py 转 503）；
          - 已经拿到 200 之后的中途断流 → 也记录失败并抛出，但**不重试**
            （此时已经向客户端发出过 delta，重试会造成重复内容）。
        `sink` 用于回传 `finish_reason`（流结束时由调用方读取）。
        """
        if not self.circuit_breaker.allow_request():
            raise urllib.error.URLError("llama-server 处于熔断保护状态 (OPEN)，快速失败切流")
        payload = {
            "model": model or self.model,
            "messages": messages,
            "stream": True,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "top_p": self.top_p,
            "repeat_penalty": self.repeat_penalty,
            # 让 llama.cpp 在最后一个 SSE 分片里带 usage（部分版本不支持，解析侧容错）
            "stream_options": {"include_usage": True},
        }
        req = urllib.request.Request(
            self.base_url + "/v1/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
        resp = None
        try:
            resp = urllib.request.urlopen(req, timeout=timeout or self.timeout)
        except urllib.error.HTTPError as exc:
            self.circuit_breaker.record_failure()
            raise
        except Exception:
            self.circuit_breaker.record_failure()
            raise
        # 连接已建立：200 之后即使中途断流也只在生成器里处理，避免"建连成功"被误记失败
        try:
            for raw in resp:
                line = raw.decode("utf-8", "replace").strip()
                if not line or not line.startswith("data:"):
                    continue
                body = line[len("data:"):].strip()
                if body == "[DONE]":
                    break
                try:
                    obj = json.loads(body)
                except json.JSONDecodeError:
                    logger.debug("跳过无法解析的 SSE 行: %s", body[:120])
                    continue
                if sink is not None:
                    if obj.get("usage"):
                        sink["usage"] = obj["usage"]
                    if obj.get("model"):
                        sink["model"] = obj["model"]
                for choice in obj.get("choices") or []:
                    if sink is not None and choice.get("finish_reason"):
                        sink["finish_reason"] = choice["finish_reason"]
                    delta = choice.get("delta") or {}
                    piece = delta.get("content")
                    if piece:
                        yield piece
            self.circuit_breaker.record_success()
        except GeneratorExit:
            # 客户端提前断开：不算后端故障，直接关流
            raise
        except Exception:
            self.circuit_breaker.record_failure()
            raise
        finally:
            try:
                resp.close()
            except Exception:  # pragma: no cover - 防御性
                pass

    def chat_vision(self, prompt, image_b64, mime="image/jpeg", temperature=0.3,
                    max_tokens=512, model=None, timeout=None) -> str:
        messages = [{"role": "user", "content": [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image_b64}"}}]}]
        return self._post(messages, temperature, max_tokens, model=model,
                          timeout=timeout or self.vision_timeout)


class ChatStream:
    """`LLMClient.chat_stream()` 的返回值：可迭代的增量流 + 收尾元数据。

    迭代产出 `(delta_text, backend_label)`。之所以做成对象而不是裸 generator：
      1. `backend` 需要在**开始迭代前**就能读到（main.py 要在响应头里写
         `X-Lanlink-Fallback`，不能等到第一个 delta）；
      2. `close()` 负责释放 `ModelCallGate` 全局锁——客户端中途断开时
         StreamingResponse 会关闭 generator，锁必须跟着放，否则后续请求全部排队超时。
    """

    def __init__(self, backend: str, iterator, on_close=None, meta: dict | None = None,
                 mode: str = "incremental"):
        self.backend = backend
        self._it = iter(iterator)
        self._on_close = on_close
        self._closed = False
        self.meta = meta if meta is not None else {}
        # incremental = 后端逐 token 吐（真流式）；buffered = 后端只有整段接口，
        # 网关拿到全文后切句（首片延迟≈总时长，仅用于 mock/Ollama 降级路径）。
        self.mode = mode

    def __iter__(self):
        return self

    def __next__(self):
        try:
            return next(self._it)
        except StopIteration:
            self.close()
            raise
        except BaseException:
            self.close()
            raise

    @property
    def finish_reason(self) -> str:
        return (self.meta.get("finish_reason") or "stop") if self.meta else "stop"

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            closer = getattr(self._it, "close", None)
            if closer is not None:
                closer()
        except Exception:  # pragma: no cover - 防御性
            pass
        finally:
            cb, self._on_close = self._on_close, None
            if cb is not None:
                try:
                    cb()
                except Exception:  # pragma: no cover - 防御性
                    logger.warning("chat_stream 收尾回调失败", exc_info=True)


class LLMClient:
    """统一入口：llama.cpp router（双模型按需加载）→ Ollama → mock。"""

    def __init__(self):
        self.backend_pref = (os.environ.get("LLM_BACKEND") or "auto").strip().lower()
        # GPU 强约束：AI 信息分析等主推理任务必须跑在 4070（llama.cpp router）。
        # 开启后若 GPU 链路不可用，直接返回明确错误，绝不静默降级到 CPU(Ollama)/mock。
        self.require_gpu = (os.environ.get("LLM_REQUIRE_GPU") or "0").strip().lower() in (
            "1", "true", "yes", "on")
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

    def chat(self, messages, temperature=0.3, max_tokens=512, model=None, role: str = "buyer"):
        """返回 (text, backend_label, latency_sec)；按请求 model 路由到 text/vl。

        单 GPU 约束：llama.cpp 的 ensure + 推理在全局锁内串行，避免并发请求让
        router 反复卸载/加载模型；锁等待超时则降级 Ollama/mock（route.lock_timeout=True），
        保证不挂死、不 500。
        """
        start = time.time()
        kind = model_kind(model) if model else KIND_TEXT
        if self.backend_pref == "mock":
            user_q = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
            res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)
            self._record_route(kind, model, None, {"reason": "mock_backend"}, {
                "fallback": "heuristic",
                "fallback_reason": "mock_preference",
                "fallback_intent": res["intent"],
                "fallback_trace": res["tool_trace"],
            })
            self._last_backend = "mock:heuristic"
            return res["text"], "mock:heuristic", time.time() - start

        acquired = False
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
                                # 上下文超限：llama.cpp 返回 400 + `exceed_context_size_error`。
                                #
                                # 这是**客户端问题**（prompt 太长），不是 GPU 故障。
                                # 历史缺陷：这个 400 被吞掉后落到下方 `require_gpu` 分支，
                                # 抛出「GPU 推理不可用」，再被兜底 handler 变成 500
                                # 「服务内部错误」。调用方（如 SQLBot 的 Text-to-SQL，
                                # 带 155 张表 schema 约 73k tokens）只能看到 500，
                                # 完全无从判断是 prompt 超长——排查成本极高。
                                # 现在显式抛专用异常，由 main.py 转成 413。
                                if "exceed_context_size" in body or "context size" in body:
                                    import re as _re
                                    m = _re.search(r"\((\d+) tokens\)", body)
                                    actual = int(m.group(1)) if m else None
                                    m2 = _re.search(r"context size \((\d+) tokens\)", body)
                                    limit = int(m2.group(1)) if m2 else None
                                    raise ContextOverflowError(actual, limit, target)
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

        # GPU 强约束：主推理任务（如 AI 信息分析）不允许落到 CPU(Ollama) 或启发式 mock。
        if self.require_gpu:
            reason = ("llm_lock_timeout" if (not acquired and models)
                      else "gpu_unavailable")
            self._record_route(kind, model, None, {"reason": reason}, {
                "fallback": "none",
                "fallback_reason": reason,
                "require_gpu": True,
            })
            logger.error("[llm] LLM_REQUIRE_GPU=1 但 GPU 链路不可用（%s），拒绝降级到 CPU", reason)
            raise RuntimeError("GPU 推理不可用：AI 分析要求运行在 4070，已拒绝降级到 CPU")

        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            try:
                text = self.ollama.chat(messages, temperature, max_tokens)
                self._last_backend = "ollama"
                return text, "ollama:" + self.ollama.model, time.time() - start
            except Exception as exc:
                logger.warning("ollama 推理失败: %s", exc)

        user_q = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
        res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)

        fb_reason = "llm_unavailable"
        fallback_extra = {
            "fallback": "heuristic",
            "fallback_intent": res["intent"],
            "fallback_trace": res["tool_trace"],
        }
        if not acquired and models:
            fb_reason = "llm_lock_timeout"
            fallback_extra["lock_timeout"] = True
            fallback_extra["queued"] = True
            fallback_extra["queue_wait_ms"] = int(waited * 1000)
        elif not self.llama.circuit_breaker.allow_request():
            fb_reason = "llm_circuit_breaker"
        fallback_extra["fallback_reason"] = fb_reason

        self._record_route(kind, model, None, {"reason": fb_reason}, fallback_extra)
        self._last_backend = "mock:heuristic"
        return res["text"], "mock:heuristic", time.time() - start

    def _http_error_body(self, exc: urllib.error.HTTPError) -> str:
        try:
            return exc.read().decode("utf-8", "replace")[:300]
        except Exception:  # pragma: no cover - 防御性
            return ""

    def _raise_if_context_overflow(self, exc: urllib.error.HTTPError, target: str) -> None:
        """400 + exceed_context_size → ContextOverflowError（由 main.py 转 413）。"""
        if exc.code != 400:
            return
        body = self._http_error_body(exc)
        if "exceed_context_size" not in body and "context size" not in body:
            return
        m = re.search(r"\((\d+) tokens\)", body)
        m2 = re.search(r"context size \((\d+) tokens\)", body)
        raise ContextOverflowError(
            int(m.group(1)) if m else None,
            int(m2.group(1)) if m2 else None,
            target,
        )

    def chat_stream(self, messages, temperature=0.3, max_tokens=512, model=None,
                    role: str = "buyer") -> "ChatStream":
        """真流式推理：返回 `ChatStream`，迭代产出 `(delta_text, backend_label)`。

        与 `chat()` 的关系：路由（text/vl 模型解析、硬件调度结果）、熔断器、
        `ModelCallGate` 全局串行锁、`_record_route` 语义完全一致，只是把
        「等全文再返回」换成「逐 delta 返回」。

        为什么先 `next()` 一次（预热首片）：
          main.py 必须**在返回 StreamingResponse 之前**知道这次请求是成功还是失败
          （否则 HTTP 200 已经发出，后端 400/503 只能表现为空流）。预热首片同时
          让 400（上下文超限）能在响应头发出前变成 413。

        降级路径（mock / Ollama）只有整段接口，因此是 `mode="buffered"`：
        网关拿到全文后按句切片，首片延迟≈总时长，这是后端能力限制而非网关攒包。
        """
        start = time.time()
        kind = model_kind(model) if model else KIND_TEXT

        def _buffered(text: str, backend: str, meta: dict | None = None) -> ChatStream:
            def _gen():
                for piece in _split_pieces(text):
                    yield piece, backend
            self._last_backend = backend
            return ChatStream(backend, _gen(), meta=meta or {}, mode="buffered")

        if self.backend_pref == "mock":
            user_q = next((m.get("content") for m in reversed(messages)
                           if m.get("role") == "user"), "")
            res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)
            self._record_route(kind, model, None, {"reason": "mock_backend"}, {
                "fallback": "heuristic",
                "fallback_reason": "mock_preference",
                "fallback_intent": res["intent"],
                "fallback_trace": res["tool_trace"],
            })
            return _buffered(res["text"], "mock:heuristic")

        acquired = False
        waited = 0.0
        models = self._llama_models()
        # 锁是否已经交给 ChatStream（成功建流时由 on_close 释放）；其余情况在此函数内释放
        handed_off = False
        if self.backend_pref in ("llamacpp", "auto") and models:
            acquired, waited = self.gate.acquire(kind, self.lock_timeout)
            if not acquired:
                self._record_route(
                    kind, model, None, {"ok": False, "reason": "lock_timeout"},
                    {"lock_timeout": True, "queued": True,
                     "queue_wait_ms": int(waited * 1000)})
                logger.warning("llama.cpp 模型锁等待超时（%.1fs），流式降级 Ollama/mock", waited)
            else:
                try:
                    ensure = self.manager.ensure(kind)
                    live_models = self._llama_models()
                    target = self._resolve_model(kind, model)
                    route_extra = {"queued": waited > 0.05,
                                   "queue_wait_ms": int(waited * 1000)}
                    self._record_route(kind, model, target, ensure, route_extra)
                    if live_models:
                        sink: dict = {}
                        # 流正常结束但一个字都没产出：这是「空答案」，不是「后端不可用」，
                        # 不能转 503（旧的非流式路径同样返回 200 + 空 content）。
                        empty_ok = False
                        gen = None
                        try:
                            gen = self.llama._post_stream(
                                messages, temperature, max_tokens, model=target, sink=sink)
                            first = next(gen)
                        except StopIteration:
                            first = None
                            empty_ok = True
                        except urllib.error.HTTPError as exc:
                            body = self._http_error_body(exc)
                            logger.warning("llamacpp 流式推理失败 HTTP %s model=%s: %s",
                                           exc.code, target, body)
                            self._raise_if_context_overflow(exc, target)
                            alt = (self.manager.current_loaded(force=True)
                                   if exc.code == 400 else None)
                            first = None
                            if alt and alt != target:
                                try:
                                    sink = {}
                                    gen = self.llama._post_stream(
                                        messages, temperature, max_tokens,
                                        model=alt, sink=sink)
                                    first = next(gen)
                                    target = alt
                                    self._record_route(
                                        kind, model, alt, ensure,
                                        dict(route_extra, model_fallback=True))
                                except Exception as retry_exc:
                                    logger.warning("llamacpp 流式重试失败: %s", retry_exc)
                                    first = None
                        except Exception as exc:
                            logger.warning("llamacpp 流式推理失败: %s", exc)
                            first = None

                        if first is not None:
                            backend = "llamacpp:" + target
                            self._last_backend = "llamacpp"
                            first_ms = int((time.time() - start) * 1000)
                            meta = {"mode": "incremental",
                                    "first_delta_ms": first_ms,
                                    "ttft_ms": first_ms,
                                    "sink": sink}

                            def _gen(first_piece=first, it=gen):
                                try:
                                    yield first_piece, backend
                                    for piece in it:
                                        yield piece, backend
                                finally:
                                    try:
                                        it.close()
                                    except Exception:  # pragma: no cover - 防御性
                                        pass

                            handed_off = True
                            return ChatStream(backend, _gen(), on_close=self.gate.release,
                                              meta=meta, mode="incremental")
                        if empty_ok:
                            return _buffered("", "llamacpp:" + target)
                finally:
                    if not handed_off:
                        # llama.cpp 路径没产出首片：先放锁再降级，避免把锁带进 Ollama/mock
                        self.gate.release()

        if self.require_gpu:
            reason = ("llm_lock_timeout" if (not acquired and models) else "gpu_unavailable")
            self._record_route(kind, model, None, {"reason": reason}, {
                "fallback": "none",
                "fallback_reason": reason,
                "require_gpu": True,
            })
            logger.error("[llm] LLM_REQUIRE_GPU=1 但 GPU 链路不可用（%s），拒绝降级到 CPU", reason)
            raise RuntimeError("GPU 推理不可用：AI 分析要求运行在 4070，已拒绝降级到 CPU")

        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            try:
                text = self.ollama.chat(messages, temperature, max_tokens)
                return _buffered(text, "ollama:" + self.ollama.model)
            except Exception as exc:
                logger.warning("ollama 流式降级失败: %s", exc)

        user_q = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
        res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)
        fb_reason = "llm_unavailable"
        fallback_extra = {
            "fallback": "heuristic",
            "fallback_intent": res["intent"],
            "fallback_trace": res["tool_trace"],
        }
        if not acquired and models:
            fb_reason = "llm_lock_timeout"
            fallback_extra["lock_timeout"] = True
            fallback_extra["queued"] = True
            fallback_extra["queue_wait_ms"] = int(waited * 1000)
        elif not self.llama.circuit_breaker.allow_request():
            fb_reason = "llm_circuit_breaker"
        fallback_extra["fallback_reason"] = fb_reason
        self._record_route(kind, model, None, {"reason": fb_reason}, fallback_extra)
        return _buffered(res["text"], "mock:heuristic")

    def chat_raw(self, messages, temperature=0.3, max_tokens=512, model=None,
                 tools=None, tool_choice=None, role: str = "buyer"):
        """工具调用版推理：返回 (message dict, finish_reason, backend_label, latency)。

        与 chat() 相同的降级链（llamacpp → ollama[auto/ollama 时] → mock:heuristic），但保留完整
        message（含 tool_calls）与 finish_reason；agent 循环在主链路之外逐轮调用，
        每轮各自进出 ModelCallGate（工具执行发生在锁外，不嵌套取锁）。
        """
        start = time.time()
        kind = model_kind(model) if model else KIND_TEXT
        if self.backend_pref == "mock":
            user_q = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
            res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)
            self._record_route(kind, model, None, {"reason": "mock_backend"}, {
                "fallback": "heuristic",
                "fallback_reason": "mock_preference",
                "fallback_intent": res["intent"],
                "fallback_trace": res["tool_trace"],
            })
            self._last_backend = "mock:heuristic"
            return ({"role": "assistant", "content": res["text"]},
                    "stop", "mock:heuristic", time.time() - start)

        acquired = False
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
                        except ContextOverflowError:
                            # prompt 超长是**客户端问题**，必须原样冒泡成 413。
                            # 历史缺陷：这里的 `except Exception` 会把它吞掉，落到
                            # `require_gpu` 分支抛「GPU 不可用」→ 503/500，
                            # 调用方会误以为 GPU 挂了而不是 prompt 太长。
                            raise
                        except Exception as exc:
                            logger.warning("llamacpp chat_raw 失败: %s", exc)
            finally:
                self.gate.release()

        if self.require_gpu:
            self._record_route(kind, model, None, {"reason": "gpu_unavailable"}, {
                "fallback": "none",
                "fallback_reason": "gpu_unavailable",
                "require_gpu": True,
            })
            logger.error("[llm] LLM_REQUIRE_GPU=1 但 GPU 链路不可用（chat_raw），拒绝降级到 CPU")
            raise RuntimeError("GPU 推理不可用：AI 分析要求运行在 4070，已拒绝降级到 CPU")

        if self.backend_pref in ("ollama", "auto") and self.ollama.available():
            try:
                msg, finish = self.ollama.chat_raw(messages, temperature, max_tokens,
                                                   tools=tools, tool_choice=tool_choice)
                self._last_backend = "ollama"
                return msg, finish, "ollama:" + self.ollama.model, time.time() - start
            except Exception as exc:
                logger.warning("ollama chat_raw 失败: %s", exc)

        user_q = next((m.get("content") for m in reversed(messages) if m.get("role") == "user"), "")
        res = FALLBACK_ENGINE.handle(str(user_q or ""), messages=messages, role=role)

        # 触发启发式规则降级引擎接管
        fb_reason = "llm_unavailable"
        fallback_extra = {
            "fallback": "heuristic",
            "fallback_intent": res["intent"],
            "fallback_trace": res["tool_trace"],
        }
        if not acquired and models:
            fb_reason = "llm_lock_timeout"
            fallback_extra["lock_timeout"] = True
            fallback_extra["queued"] = True
            fallback_extra["queue_wait_ms"] = int(waited * 1000)
        elif not self.llama.circuit_breaker.allow_request():
            fb_reason = "llm_circuit_breaker"
        fallback_extra["fallback_reason"] = fb_reason

        self._record_route(kind, model, None, {"reason": fb_reason}, fallback_extra)
        self._last_backend = "mock:heuristic"
        return ({"role": "assistant", "content": res["text"]},
                "stop", "mock:heuristic", time.time() - start)

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


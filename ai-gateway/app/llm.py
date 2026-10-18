# -*- coding: utf-8 -*-
"""本地大模型推理客户端。

后端可插拔：
  - ollama：对接沙盒内 Ollama 服务（http://ollama:11434），本地加载模型权重推理；
  - mock：离线兜底（基于知识库摘录作答），保证网关在模型未就绪时链路仍可验证。
"""
import json
import os
import re
import time
import urllib.error
import urllib.request

import jieba


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
            "options": {"temperature": temperature, "num_predict": max_tokens},
        }
        req = urllib.request.Request(
            self.base_url + "/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=120) as r:
            data = json.loads(r.read().decode("utf-8"))
        return data.get("message", {}).get("content", "").strip()


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


class LLMClient:
    """统一推理入口：优先 Ollama 本地模型，未就绪时回退 mock，返回 (text, backend)。"""

    def __init__(self):
        self.base_url = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
        self.model = os.environ.get("LLM_MODEL", "qwen2.5:0.5b")
        self.ollama = OllamaBackend(self.base_url, self.model)
        self.mock = MockBackend()

    def backend_name(self) -> str:
        return "ollama" if self.ollama.available() else "mock"

    def chat(self, messages: list, temperature: float = 0.3, max_tokens: int = 512) -> tuple:
        start = time.time()
        if self.ollama.available():
            try:
                return self.ollama.chat(messages, temperature, max_tokens), "ollama", time.time() - start
            except Exception:
                pass
        return self.mock.chat(messages, temperature, max_tokens), "mock", time.time() - start

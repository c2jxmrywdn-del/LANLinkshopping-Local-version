"""RAG-Anything 服务配置。

设计要点（为什么这么配）：

1. **不装 MinerU / Docling**：这两条 parser 路线都会拉入 torch + CUDA（实测 site-packages
   6.3GB），而本机 RTX 4070 只剩 ~536MiB 显存，无法共存。这里改用
   `insert_content_list()` 接收「已解析好的内容块」——解析交给已有的
   `SupportDocumentTextExtractor`（PDFBox/POI，已在 Java 侧验证）或调用方，
   RAG-Anything 只负责多模态索引与跨模态检索，这才是它不可替代的部分。

2. **Python 3.14**：`mineru[core]` 的依赖声明带 `python_version < "3.14"` 条件，
   用 3.14 会自动跳过整个 CUDA/torch 依赖树，镜像从 6.3GB 降到 ~334MB。

3. **LLM / Embedding 全部复用现有 ai-gateway**：不引入第二套模型推理。
   embedding 必须 1024 维以对齐现有 bge-m3 约定。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str = "") -> str:
    return (os.getenv(name) or default).strip()


def _env_int(name: str, default: int) -> int:
    try:
        return int(_env(name, str(default)))
    except ValueError:
        return default


@dataclass
class Settings:
    """运行期配置，全部来自环境变量（12-Factor）。"""

    # ---- 服务自身 ----
    host: str = field(default_factory=lambda: _env("RAG_HOST", "0.0.0.0"))
    port: int = field(default_factory=lambda: _env_int("RAG_PORT", 8093))
    # 索引落盘目录（容器内挂卷）
    working_dir: str = field(default_factory=lambda: _env("RAG_WORKING_DIR", "/data/rag_storage"))

    # ---- 上游 LLM / Embedding（复用 ai-gateway）----
    # 网关的 OpenAI 兼容前缀。
    # 容器名是 `lanlink-ai-gateway`（compose 里写了 container_name），
    # 不是 `ai-gateway`——写错会在 DNS 解析阶段直接失败。
    llm_base_url: str = field(default_factory=lambda: _env("RAG_LLM_BASE_URL", "http://lanlink-ai-gateway:8090/v1"))
    llm_api_key: str = field(default_factory=lambda: _env("RAG_LLM_API_KEY", "lanlink-local"))
    llm_model: str = field(default_factory=lambda: _env("RAG_LLM_MODEL", "qwen2.5-7b"))
    vision_model: str = field(default_factory=lambda: _env("RAG_VISION_MODEL", "qwen2.5-vl"))
    embed_model: str = field(default_factory=lambda: _env("RAG_EMBED_MODEL", "bge-m3"))

    # ---- 检索参数 ----
    # 与现有 aikb 的 top_k 口径保持一致，便于结果对比
    top_k: int = field(default_factory=lambda: _env_int("RAG_TOP_K", 20))
    # 单次请求的 LLM/embedding 超时（秒）。本地 7B 在 4070 上约 5-15s，留足余量
    llm_timeout: int = field(default_factory=lambda: _env_int("RAG_LLM_TIMEOUT", 180))
    embed_timeout: int = field(default_factory=lambda: _env_int("RAG_EMBED_TIMEOUT", 60))
    embed_dim: int = field(default_factory=lambda: _env_int("RAG_EMBED_DIM", 1024))
    embed_batch: int = field(default_factory=lambda: _env_int("RAG_EMBED_BATCH", 16))

    # ---- 安全 ----
    # 与后端共享的内部密钥；为空时服务只接受来自内网的请求（见 main.py 的守卫）
    internal_secret: str = field(default_factory=lambda: _env("RAG_INTERNAL_SECRET", ""))


settings = Settings()

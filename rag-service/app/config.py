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

    # ---- 上游 LLM（生成/实体抽取）----
    # 默认走本地网关（llama.cpp 7B）；可切外部（如 DeepSeek）。
    # 容器名是 `lanlink-ai-gateway`（compose 里写了 container_name），
    # 不是 `ai-gateway`——写错会在 DNS 解析阶段直接失败。
    llm_base_url: str = field(default_factory=lambda: _env("RAG_LLM_BASE_URL", "http://lanlink-ai-gateway:8090/v1"))
    llm_api_key: str = field(default_factory=lambda: _env("RAG_LLM_API_KEY", "lanlink-local"))
    llm_model: str = field(default_factory=lambda: _env("RAG_LLM_MODEL", "qwen2.5-7b"))
    vision_model: str = field(default_factory=lambda: _env("RAG_VISION_MODEL", "qwen2.5-vl"))

    # ---- 上游 Embedding（**必须与 LLM 分开配**）----
    #
    # 为什么独立：DeepSeek 只提供 chat/completions，**没有 embeddings 端点**
    # （实测 `POST https://api.deepseek.com/v1/embeddings` 返回 404，
    # 无论 model 填 deepseek-embedding 还是 deepseek-chat）。
    # 所以「LLM 切 DeepSeek」时必须让 embedding 继续走本地 bge-m3，
    # 否则索引写入会直接 500。
    #
    # 另外 embedding 维度必须与已建索引一致（bge-m3=1024）。
    # 一旦换 embedding 模型，LightRAG 的向量库需要整库重建，不能只改配置。
    embed_base_url: str = field(
        default_factory=lambda: _env("RAG_EMBED_BASE_URL", "http://lanlink-ai-gateway:8090/v1")
    )
    embed_api_key: str = field(default_factory=lambda: _env("RAG_EMBED_API_KEY", ""))
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

    # ---- 向量存储后端 ----
    # `redis` = RediSearch HNSW（推荐，见 app/redis_vector_store.py）
    # `nano`  = NanoVectorDB 本地 JSON 文件（历史默认，保留以便回滚）
    #
    # 为什么默认切 redis：LightRAG 的 Redis 实现只覆盖 KV 与 DocStatus，
    # 没有向量层，所以此前索引只能落盘在 rag-storage 卷里，与 Java 侧
    # 两套 Redis 索引（aikb-idx / prod-idx）彼此不可见、无法统一运维。
    vector_backend: str = field(
        default_factory=lambda: _env("RAG_VECTOR_BACKEND", "redis").strip().lower()
    )
    # Redis 连接串。容器内是 redis://redis:6379/0；密码走 URL 的 password 段。
    redis_uri: str = field(default_factory=lambda: _env("REDIS_URI", ""))
    redis_host: str = field(default_factory=lambda: _env("REDIS_HOST", "redis"))
    redis_port: int = field(default_factory=lambda: _env_int("REDIS_PORT", 6379))
    redis_password: str = field(default_factory=lambda: _env("REDIS_PASSWORD", ""))
    redis_db: int = field(default_factory=lambda: _env_int("RAG_REDIS_DB", 0))

    def resolved_redis_uri(self) -> str:
        """拼出 redis:// 连接串：优先显式 REDIS_URI，否则由 host/port/password 组装。

        密码必须 URL 编码——本项目生成的密码是 base64 风格，可能含 `+/=`，
        直接拼进 URL 会让 redis-py 解析失败（实测 `Invalid URL`）。
        """
        if self.redis_uri:
            return self.redis_uri
        auth = ""
        if self.redis_password:
            from urllib.parse import quote
            auth = f":{quote(self.redis_password, safe='')}@"
        return f"redis://{auth}{self.redis_host}:{self.redis_port}/{self.redis_db}"


settings = Settings()

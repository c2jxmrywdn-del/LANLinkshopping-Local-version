"""RAG-Anything 引擎封装。

职责边界：
  - 本模块只管「索引与检索」，不负责文档解析（解析在 Java 侧或调用方完成）。
  - 单例持有 RAGAnything 实例：LightRAG 的存储层带进程内状态，每次请求重建会
    丢失图结构并重复初始化，代价很高。
  - 所有写操作串行化：LightRAG 的 pipeline 不保证并发安全，且并发插入会互相
    等待同一把锁（README 有说明），显式加锁能让排队行为可预期。
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from lightrag.utils import EmbeddingFunc
from raganything import RAGAnything, RAGAnythingConfig

from . import models
from .config import settings
from .prompts import install_hardened_prompts

log = logging.getLogger("rag.engine")


class RagEngine:
    """进程内单例。"""

    def __init__(self) -> None:
        self._rag: RAGAnything | None = None
        self._init_lock = asyncio.Lock()
        self._write_lock = asyncio.Lock()

    async def get(self) -> RAGAnything:
        if self._rag is not None:
            return self._rag
        async with self._init_lock:
            if self._rag is not None:
                return self._rag
            # 必须在构造 RAGAnything 之前安装：prompt 在实体抽取时读取
            install_hardened_prompts()
            config = RAGAnythingConfig(
                working_dir=settings.working_dir,
                # 不使用任何重型 parser：内容由调用方以 content_list 形式传入。
                # 这里保留 mineru 作为默认值不影响，因为我们从不调用 process_document_complete。
                parser="mineru",
                parse_method="auto",
                enable_image_processing=True,
                enable_table_processing=True,
                enable_equation_processing=True,
            )
            embedding_func = EmbeddingFunc(
                embedding_dim=settings.embed_dim,
                max_token_size=8192,
                func=models.embed_texts,
            )
            rag = RAGAnything(
                config=config,
                llm_model_func=models.llm_complete,
                vision_model_func=models.vision_complete,
                embedding_func=embedding_func,
                # 与现有 aikb 的检索粒度对齐（1200 字块长是既有约定）
                lightrag_kwargs={
                    "top_k": settings.top_k,
                    "chunk_token_size": 1200,
                    "chunk_overlap_token_size": 100,
                    "default_llm_timeout": settings.llm_timeout,
                    "default_embedding_timeout": settings.embed_timeout,
                    # NanoVectorDB 的默认余弦阈值 0.2 对中文短查询偏严：
                    # 「单笔充值上限是多少？」与文档块的实际相似度约 0.70，
                    # 但其他改写问法可能落到 0.15-0.3 区间被直接过滤掉，
                    # 表现为「检索静默返回空、模型编造答案」。这里下调到 0.1。
                    "cosine_better_than_threshold": 0.1,
                    # 显式声明向量维度，避免从空存储初始化时丢失维度信息
                    "vector_db_storage_cls_kwargs": {"cosine_better_than_threshold": 0.1},
                },
            )
            """
            跳过 parser 安装校验。

            RAG-Anything 在 `_initialize_lightrag()` 里会无条件校验 parser 是否安装，
            不通过就直接返回失败——哪怕我们根本不会调用 `process_document_complete()`
            （本服务只走 `insert_content_list()`，内容由调用方解析好传进来）。

            不装 MinerU/Docling 是刻意选择：它们会拉入 torch+CUDA（6.3GB），
            而本机 GPU 仅剩 ~536MiB 显存。所以这里把「已校验」标记置位，
            让初始化跳过该检查。这只影响 parser 相关路径，不影响索引与检索。
            """
            rag._parser_installation_checked = True
            await rag._ensure_lightrag_initialized()
            self._rag = rag
            log.info("RAG-Anything 初始化完成 working_dir=%s", settings.working_dir)
            return rag

    async def ingest(self, doc_id: str, content_list: list[dict[str, Any]],
                     file_path: str | None = None) -> dict[str, Any]:
        """写入一篇文档的内容块。

        `content_list` 是 RAG-Anything 的原生输入格式（README `insert_content_list`），
        每个元素形如 `{"type": "text", "text": "...", "page_idx": 0}` 或
        `{"type": "image", "img_path": "...", "image_caption": [...]}`。
        """
        if not content_list:
            raise ValueError("content_list 不能为空")
        rag = await self.get()
        # 串行化：LightRAG pipeline 并发插入会互相等待，显式锁让排队可观测
        async with self._write_lock:
            await rag.insert_content_list(
                content_list,
                file_path=file_path or f"{doc_id}.parsed",
                doc_id=doc_id,
                display_stats=False,
            )
        return {"docId": doc_id, "chunks": len(content_list)}

    async def query(self, question: str, mode: str = "hybrid",
                    top_k: int | None = None) -> str:
        rag = await self.get()
        """
        显式构造 QueryParam，而不是把参数散着传给 aquery。

        压测发现的三个问题都出在这里：
          1. `enable_rerank` 默认 True，但本项目没配 rerank 模型 →
             每个请求刷一条 WARNING，且多走一次无用判断；
          2. 传 `top_k` 时 LightRAG 仍按 `chunk_top_k` 取块，
             不显式设置就永远用全局默认（实测 topK=5 仍返回 20 块）；
          3. `user_prompt` 为空时 LightRAG 会拼一个很长的默认模板，
             7B 模型在长模板下更容易忽略上下文。
        """
        """
        注意 `RAGAnything.aquery` 的签名是
        `(query, mode='mix', system_prompt=None, **kwargs)`，
        它**不接收** `param=QueryParam(...)`——传了会报
        `QueryParam.__init__() got an unexpected keyword argument 'param'`。
        查询参数必须作为 kwargs 平铺传入，由它内部组装 QueryParam。
        """
        k = top_k or settings.top_k
        return await rag.aquery(
            question,
            mode=mode,
            top_k=k,
            chunk_top_k=k,
            # 没有 rerank 模型就明确关掉，避免每请求一条 WARNING
            enable_rerank=False,
        )

    async def health(self) -> dict[str, Any]:
        """健康检查：不触发 LLM 调用，只确认存储与配置就绪。"""
        try:
            rag = await self.get()
            return {
                "ok": True,
                "workingDir": settings.working_dir,
                "llmBaseUrl": settings.llm_base_url,
                "llmModel": settings.llm_model,
                "visionModel": settings.vision_model,
                "embedModel": settings.embed_model,
                "embedDim": settings.embed_dim,
                "initialized": rag is not None,
            }
        except Exception as e:  # noqa: BLE001 - 健康检查要吞掉异常并如实报告
            log.exception("健康检查失败")
            return {"ok": False, "error": str(e)}


engine = RagEngine()

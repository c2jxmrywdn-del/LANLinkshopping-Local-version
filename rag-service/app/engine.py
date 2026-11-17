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
import json
import logging
import os
import time
from pathlib import Path
from typing import Any

from lightrag.utils import EmbeddingFunc
from raganything import RAGAnything, RAGAnythingConfig

from . import models
from .config import settings
from .lightrag_registry import register_redis_vector_storage, storage_available
from .prompts import install_hardened_prompts

log = logging.getLogger("rag.engine")

# LightRAG 用 `<SEP>` 把同一实体/关系的多个来源拼成一个字符串字段：
#   source_id   = "chunk-a<SEP>chunk-b"
#   description = "描述A<SEP>描述B"（与 source_id 不是严格一一对应）
#   file_path   = "a.pdf<SEP>b.pdf"
# 删除文档时必须按这个分隔符切片，否则会把整条记录当成「全部属于该文档」。
GRAPH_FIELD_SEP = "<SEP>"


class RagEngine:
    """进程内单例。"""

    def __init__(self) -> None:
        self._rag: RAGAnything | None = None
        self._init_lock = asyncio.Lock()
        self._write_lock = asyncio.Lock()
        self._vector_backend = "unknown"

    def _resolve_vector_backend(self) -> str:
        """决定向量存储后端，并做前置校验。

        `redis` 分支要满足两个条件，任一不满足就**显式报错**而不是静默回退：
          1. 注册表注入成功（`STORAGES` + `STORAGE_IMPLEMENTATIONS` 都改了）；
          2. `REDIS_URI`（或 host/port/password）可解析。

        为什么不静默回退到 nano：回退会让「配置了 Redis 却仍在写本地文件」
        变成一个没人发现的静默故障，索引数据会分裂到两个地方。
        """
        backend = (settings.vector_backend or "redis").strip().lower()
        if backend in ("nano", "nanovectordb", "nano_vector_db"):
            return "NanoVectorDBStorage"
        if backend not in ("redis", "redisvector", "redis_vector"):
            raise ValueError(
                f"未知的 RAG_VECTOR_BACKEND={backend!r}；可选 redis / nano"
            )
        if not register_redis_vector_storage():
            raise RuntimeError(
                "RedisVectorStorage 注册失败：无法注入 lightrag 的 STORAGES / "
                "STORAGE_IMPLEMENTATIONS，Redis 向量后端不可用"
            )
        if not storage_available():
            raise RuntimeError("RedisVectorStorage 注册后校验失败")
        # 适配器读的是 os.environ['REDIS_URI']，这里把组装好的连接串写回去，
        # 避免密码散落在两处解析逻辑里。
        os.environ.setdefault("REDIS_URI", settings.resolved_redis_uri())
        return "RedisVectorStorage"

    async def get(self) -> RAGAnything:
        if self._rag is not None:
            return self._rag
        async with self._init_lock:
            if self._rag is not None:
                return self._rag
            # 必须在构造 RAGAnything 之前安装：prompt 在实体抽取时读取
            install_hardened_prompts()
            vector_storage_cls = self._resolve_vector_backend()
            self._vector_backend = vector_storage_cls
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
                    # 向量存储后端：redis（RediSearch HNSW）或 NanoVectorDBStorage
                    "vector_storage": vector_storage_cls,
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
                    # 关闭 LLM 响应缓存。
                    #
                    # LightRAG 的缓存 key 只由 query 文本算 hash，**不含 prompt**。
                    # 一旦某次用错误 prompt（例如网关 use_kb 覆盖了上下文）产出错答案，
                    # 之后即使修好 prompt，同一问题仍会命中旧缓存、反复返回同一个错答案，
                    # 排查时极具误导性（实测踩过：清空缓存文件才看到真实行为）。
                    "enable_llm_cache": False,
                    "enable_llm_cache_for_entity_extract": False,
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
            """
            必须检查 `_ensure_lightrag_initialized()` 的返回值。

            它内部把所有 storage 的 `initialize()` 包在 try/except 里，
            **失败只打日志、返回 False**，不会抛异常。历史实现直接忽略返回值，
            于是 `FT.CREATE` 语法错误（例如 VECTOR 属性 COUNT 写错）时：
              - 容器仍然报 healthy
              - `/health` 仍然返回 `{"ok": true}`
              - 但 `FT._LIST` 里一个索引都没有，ingest 会写进没有索引的 key，
                query 永远返回空 —— 属于最难排查的一类静默失败。
            这里改成显式抛错，让容器进入 unhealthy 而不是假装正常。
            """
            initialized = await rag._ensure_lightrag_initialized()
            if initialized is False:
                raise RuntimeError(
                    "LightRAG 存储初始化失败（_ensure_lightrag_initialized 返回 False）："
                    f"向量后端={self._vector_backend}。请检查上方日志里的 storage 初始化异常，"
                    "常见原因是 FT.CREATE 语法错误、Redis 不可达或索引维度冲突。"
                )
            self._rag = rag
            self._purge_stale_query_cache()
            log.info("RAG-Anything 初始化完成 working_dir=%s", settings.working_dir)
            return rag

    def _purge_stale_query_cache(self) -> None:
        """删除历史查询缓存文件。

        背景：`enable_llm_cache=false` 只是**不再写入**，磁盘上已有的旧记录仍在。
        那些记录里有早期用错误 prompt（网关 use_kb 覆盖上下文）产生的错答案，
        而且缓存 key 不含 prompt——一旦有人把开关改回 True，会立刻回放这些错答案。

        删除是安全的：查询缓存是纯性能优化，删掉只会让下一次查询慢 1-2 秒。
        实体抽取缓存（`cache_type=extract`）保留，它按 chunk 内容做 key，不涉及 prompt 漂移。
        """
        self.purge_query_cache()

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
        """
        串行化写入，并在写后回读真实状态。

        为什么写后要回读 `doc_status`：
        LightRAG 对**重复正文**会判成 `dup-*` 或 `failed` 而不报错，
        `insert_content_list` 照样正常返回。如果这里直接返回成功，
        调用方会以为文档已入库，实际检索不到——属于静默失败。

        为什么返回的 chunks 要回读而不是用 len(content_list)：
        一个 content_list 元素经 `split_by_character` 后可能产出多个 chunk
        （实测 1 个元素 → 2 个 chunk），用输入元素数会低报。
        """
        file_key = file_path or f"{doc_id}.parsed"
        async with self._write_lock:
            await rag.insert_content_list(
                content_list,
                file_path=file_key,
                doc_id=doc_id,
                display_stats=False,
                # 按空行切分再建 chunk。
                #
                # 默认行为是把整篇 text 当作**一个** chunk（实测 1 个 content_list 元素
                # 就产出 1 个 chunk），几十页的文档会被塞成单个巨型块：
                #   - 检索粒度太粗，命中的"块"里大部分内容与问题无关；
                #   - 送进 LLM 的上下文变长，7B 更容易忽略关键信息。
                # 按空行切分是最贴近文档原有段落结构的做法，不需要额外分词器。
                split_by_character="\n\n",
            )
            status = await self._read_doc_status(rag, doc_id, file_key)

        if status.get("status") == "failed":
            raise RuntimeError(
                f"索引失败（LightRAG 报告 failed）：{status.get('error') or '未知原因'}；"
                "常见原因是 LLM 不可用或正文重复"
            )
        return {
            "docId": doc_id,
            "chunks": status.get("chunks", len(content_list)),
            "status": status.get("status"),
        }

    async def _read_doc_status(self, rag: RAGAnything, doc_id: str, file_key: str) -> dict[str, Any]:
        """回读 doc_status，拿到真实 chunk 数与终态。"""
        try:
            lr = rag.lightrag
            kv = getattr(lr, "doc_status", None)
            if kv is None:
                return {}
            for key in (doc_id, file_key):
                rec = await kv.get_by_id(key)
                if not rec:
                    continue
                if hasattr(rec, "model_dump"):
                    rec = rec.model_dump()
                return {
                    "status": rec.get("status"),
                    "chunks": rec.get("chunks_count") or rec.get("chunks") or 0,
                    "error": rec.get("error_msg") or rec.get("error"),
                }
        except Exception as e:  # noqa: BLE001 - 回读失败不应掩盖写入本身的结果
            log.warning("回读 doc_status 失败 docId=%s: %s", doc_id, e)
        return {}

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
        """
        查询也要和写入互斥。

        `_write_lock` 原来只包 ingest，query 全程无锁——检索可能读到
        「已写入 chunk、但图/向量还没提交完」的中间态，表现为静默返回空
        或答非所问，且难以复现。

        用同一把锁意味着读写串行：写入期间查询会等待。这是刻意的取舍——
        本服务是内部管理端使用，写入是低频批量操作，宁可查询稍等几秒，
        也不要返回不一致的结果。

        注意：**不要给本服务加 `--workers N`**。LightRAG 的存储是进程内
        全量覆盖写，多 worker 会互相覆盖丢数据。要扩容得先换外部存储。
        """
        async with self._write_lock:
            return await rag.aquery(
                question,
                mode=mode,
                top_k=k,
                chunk_top_k=k,
                # 没有 rerank 模型就明确关掉，避免每请求一条 WARNING
                enable_rerank=False,
            )

    # ------------------------------------------------------------------ 删除

    async def adelete(self, doc_id: str, purge_query_cache: bool = True) -> dict[str, Any]:
        """删除一篇文档，并清理 LightRAG 自身删除逻辑覆盖不到的残留。

        为什么不能只调 `lightrag.adelete_by_doc_id()`（1.4.16 实测）
        ----------------------------------------------------------
        它只在 `doc_status` 里还能找到该 doc 时才会真正动手；一旦上一次删除
        已经把 `doc_status` 行删掉、但后续步骤失败（例如向量/图持久化失败、
        进程在写盘前被 kill），重试会直接返回 `not_found` 并**什么都不做**。
        此时 `text_chunks` / `full_entities` / `full_relations` / 三套向量索引
        与 graphml 里的节点边全都还在，`hybrid` / `global` 模式仍能从 graph
        取到这些实体和关系，于是继续返回已删除文档的内容。

        它还有一个更隐蔽的缺口：`enable_llm_cache_for_entity_extract=false`
        （本服务的刻意配置）会让 `rebuild_knowledge_from_chunks()` 拿不到
        抽取缓存，只能打印 "No cached extraction results found, cannot rebuild"
        然后跳过——共享实体/关系的 `description` 里因此长期保留已删除文档的
        事实描述，而这些描述会进入 hybrid/global 的 LLM 上下文。

        本方法因此做三件事：
          1. 先按「doc_status 已丢失」也能工作的方式自己收集 chunk id
             （doc_status → text_chunks.full_doc_id → 兜底全表扫描）；
          2. 调上游删除（拿它做好图/向量的依赖分析），**无论返回什么状态**
             都继续执行第 3 步；
          3. 逐存储核对并补删：KV 五件套、三套 Redis 向量索引、graphml 里的
             节点/边，以及只由该文档贡献的 `entity_chunks`/`relation_chunks` 行。

        返回结构里的 `deleted` 是「本次实际删掉的条数」，`warnings` 是
        「删不掉或需要人工确认」的条目——调用方据此判断是否真的干净。
        """
        if not doc_id or not doc_id.strip():
            raise ValueError("docId 不能为空")
        doc_id = doc_id.strip()
        rag = await self.get()
        lr = rag.lightrag

        deleted: dict[str, int] = {}
        warnings: list[str] = []

        async with self._write_lock:
            # ---- 1. 收集该文档的 chunk id（不依赖 doc_status 是否还在）----
            chunk_ids = await self._collect_doc_chunk_ids(lr, doc_id)
            doc_status_before = await self._safe_get(lr.doc_status, doc_id)
            full_docs_before = await self._safe_get(lr.full_docs, doc_id)

            # ---- 2. 先记录该文档涉及的实体/关系名，供图与向量清理使用 ----
            entity_names, relation_pairs = await self._doc_graph_members(
                lr, doc_id, chunk_ids
            )

            # ---- 3. 调上游删除：它负责依赖分析与「无剩余来源才删」的判断 ----
            upstream_status, upstream_message = await self._call_upstream_delete(lr, doc_id)
            if upstream_status not in ("success", "not_found"):
                warnings.append(
                    f"LightRAG adelete_by_doc_id 返回 {upstream_status}: {upstream_message}；"
                    "已继续执行显式清理"
                )
            if upstream_status == "not_found" and chunk_ids:
                warnings.append(
                    "LightRAG 未找到 doc_status 记录（可能是上次删除中途失败），"
                    "已按 text_chunks/full_* 里的残留继续清理"
                )

            # ---- 4. 逐存储补删并统计 ----
            self._merge_counts(deleted, await self._purge_doc_kv(lr, doc_id, chunk_ids))
            # 顺序很重要：**先清图、再清向量**。
            # `_purge_vectors` 用「图里还有没有这个实体」判断是否该删向量；
            # 反过来做的话，节点此刻还在图里 → 判定为「仍有其它来源」→ 跳过，
            # 紧接着图被删掉，就留下永远查得到的孤儿向量（实测踩过）。
            graph_counts, graph_warnings = await self._purge_graph(
                lr, chunk_ids, entity_names, relation_pairs
            )
            self._merge_counts(deleted, graph_counts)
            warnings.extend(graph_warnings)
            self._merge_counts(
                deleted,
                await self._purge_vectors(lr, doc_id, chunk_ids, entity_names, relation_pairs),
            )
            self._merge_counts(
                deleted,
                await self._purge_chunk_tracking(lr, chunk_ids, entity_names, relation_pairs),
            )
            # ---- 4.5 兜底：清掉「来源 chunk 已全部不存在」的孤儿节点/边 ----
            # 上一轮删除若在「删了 chunk、没删图」时中断，图里会留下 source_id
            # 全部指向已删 chunk 的节点。这类节点仍会被 hybrid/global 检索到，
            # 而它已不属于任何文档——只能靠「来源是否还存在」判定。
            orphan_counts, orphan_warnings = await self._purge_orphan_graph(lr)
            self._merge_counts(deleted, orphan_counts)
            warnings.extend(orphan_warnings)
            # ---- 4.6 兜底：清掉「来源 chunk 已全部不存在」的孤儿向量 ----
            # 图删了、向量没删（或实体名被改写导致 hash 对不上）时，entities/
            # relationships 索引里仍留有可被检索到的旧描述。这里按向量自身的
            # `source_id` 反查存活 chunk，彻底清掉。
            vec_counts, vec_warnings = await self._purge_orphan_vectors(lr)
            self._merge_counts(deleted, vec_counts)
            warnings.extend(vec_warnings)

            # ---- 5. 落盘：KV/graph 都是「内存改完等 index_done_callback」----
            try:
                await lr._insert_done()
            except Exception as e:  # noqa: BLE001 - 落盘失败要显式报告
                warnings.append(f"删除后落盘失败（内存已清，磁盘可能仍有残留）: {e}")

            # ---- 6. 复核：把「查得到」的残留升级成 warning ----
            residual_warnings = await self._verify_clean(
                lr, doc_id, chunk_ids, entity_names, relation_pairs
            )
            warnings.extend(residual_warnings)

            if purge_query_cache:
                removed = self.purge_query_cache()
                if removed:
                    deleted["llm_query_cache"] = removed

        existed = bool(doc_status_before or full_docs_before or chunk_ids)
        total = sum(deleted.values())
        log.info(
            "删除完成 docId=%s existed=%s total=%d warnings=%d",
            doc_id, existed, total, len(warnings),
        )
        return {
            "docId": doc_id,
            "existed": existed,
            "deleted": deleted,
            # residual=True 表示复核后仍能查到残留，调用方必须当失败处理
            "residual": bool(residual_warnings),
            "warnings": warnings,
        }

    async def _collect_doc_chunk_ids(self, lr: Any, doc_id: str) -> list[str]:
        """收集属于该文档的 chunk id，尽量不依赖 doc_status 是否还在。

        三条来源按可靠性排序：
          1. `doc_status.chunks_list`（正常路径，最准）；
          2. `text_chunks` 里 `full_doc_id == doc_id` 的行（doc_status 丢了也有效）；
          3. `doc_status` 里 `file_path` 相同、且状态为 failed 的 `dup-*` 记录
             指向的 chunk（正文重复时 LightRAG 会另建一条 dup 记录）。
        """
        ids: list[str] = []
        seen: set[str] = set()

        def _add(values: Any) -> None:
            if not isinstance(values, list):
                return
            for v in values:
                if isinstance(v, str) and v and v not in seen:
                    seen.add(v)
                    ids.append(v)

        status = await self._safe_get(lr.doc_status, doc_id)
        if status:
            _add(status.get("chunks_list"))

        # text_chunks 全表扫描：JsonKVStorage 的 `_data` 是进程内共享 dict，
        # 这里只读遍历，不改动；文档数在管理端量级（百级），成本可接受。
        # 换成外部 KV 后端时拿不到全表（没有 all_keys API），此时跳过——
        # 只靠 doc_status.chunks_list，属于降级但不报错。
        for cid, rec in self._iter_kv_items(getattr(lr, "text_chunks", None)):
            if isinstance(rec, dict) and rec.get("full_doc_id") == doc_id:
                _add([cid])

        return ids

    @staticmethod
    def _iter_kv_items(storage: Any):
        """遍历 KV 存储的 (key, record)，拿不到全表时返回空。

        LightRAG 的 `JsonKVStorage` 把数据放在 `_data`（进程内共享 dict），
        没有公开的 all_keys 接口。这里兼容 `_data` / `data` 两种命名，
        两者都没有时静默返回空——调用方必须能接受「只有 doc_status 一条线索」。
        """
        if storage is None:
            return []
        for attr in ("_data", "data"):
            data = getattr(storage, attr, None)
            if isinstance(data, dict):
                return list(data.items())
        return []

    async def _doc_graph_members(
        self, lr: Any, doc_id: str, chunk_ids: list[str]
    ) -> tuple[list[str], list[tuple[str, str]]]:
        """找出该文档涉及的实体名与关系对。

        优先用 `full_entities` / `full_relations`（LightRAG 自己维护的归属表）；
        它们缺失时退回「扫 graph，看 source_id 是否引用本文件的 chunk」。
        """
        entity_names: list[str] = []
        relation_pairs: list[tuple[str, str]] = []
        chunk_set = set(chunk_ids)

        full_entities = await self._safe_get(lr.full_entities, doc_id)
        if full_entities:
            for name in full_entities.get("entity_names") or []:
                if isinstance(name, str) and name:
                    entity_names.append(name)

        full_relations = await self._safe_get(lr.full_relations, doc_id)
        if full_relations:
            for pair in full_relations.get("relation_pairs") or []:
                if isinstance(pair, (list, tuple)) and len(pair) == 2:
                    relation_pairs.append((str(pair[0]), str(pair[1])))

        graph = getattr(lr, "chunk_entity_relation_graph", None)
        if graph is not None and (chunk_set or doc_id):
            try:
                for node in await graph.get_all_nodes():
                    name = node.get("id")
                    if not isinstance(name, str) or not name:
                        continue
                    if self._record_belongs_to_doc(node, doc_id, chunk_set):
                        if name not in entity_names:
                            entity_names.append(name)
                for edge in await graph.get_all_edges():
                    if not self._record_belongs_to_doc(edge, doc_id, chunk_set):
                        continue
                    pair = (str(edge.get("source")), str(edge.get("target")))
                    if pair not in relation_pairs:
                        relation_pairs.append(pair)
            except Exception as e:  # noqa: BLE001 - 图读取失败不阻断 KV 清理
                log.warning("扫描图归属失败 docId=%s: %s", doc_id, e)

        return entity_names, relation_pairs

    @staticmethod
    def _record_belongs_to_doc(
        record: dict[str, Any], doc_id: str, chunk_set: set[str]
    ) -> bool:
        """判断一条图记录是否只由该文档贡献。

        判定依据是 `source_id` 里的 chunk 引用（这是 LightRAG 的权威归属信息）；
        同时兜底看 `file_path`，因为旧数据里 source_id 可能被截断过。
        """
        source_id = record.get("source_id") or ""
        refs = {
            part for part in str(source_id).split(GRAPH_FIELD_SEP) if part
        }
        if refs & chunk_set:
            return True
        file_path = str(record.get("file_path") or "")
        if doc_id and doc_id in file_path:
            return True
        return False

    async def _call_upstream_delete(self, lr: Any, doc_id: str) -> tuple[str, str]:
        """调 LightRAG 的删除 API，把异常收敛成 (status, message)。"""
        try:
            result = await lr.adelete_by_doc_id(doc_id)
        except Exception as e:  # noqa: BLE001 - 上游失败不能阻断显式清理
            log.warning("LightRAG adelete_by_doc_id 抛异常 docId=%s: %s", doc_id, e)
            return "exception", str(e)
        status = getattr(result, "status", None) or "unknown"
        message = getattr(result, "message", "") or ""
        return str(status), str(message)

    async def _purge_doc_kv(
        self, lr: Any, doc_id: str, chunk_ids: list[str]
    ) -> dict[str, int]:
        """补删 5 个 KV 存储里属于该文档的行。"""
        counts: dict[str, int] = {}

        async def _delete_rows(attr: str, ids: list[str]) -> None:
            if not ids:
                counts[attr] = 0
                return
            storage = getattr(lr, attr, None)
            if storage is None:
                counts[attr] = 0
                return
            existing = await self._existing_ids(storage, ids)
            if existing:
                await storage.delete(existing)
            counts[attr] = len(existing)

        await _delete_rows("doc_status", [doc_id])
        await _delete_rows("full_docs", [doc_id])
        await _delete_rows("full_entities", [doc_id])
        await _delete_rows("full_relations", [doc_id])
        await _delete_rows("text_chunks", chunk_ids)
        return counts

    async def _existing_ids(self, storage: Any, ids: list[str]) -> list[str]:
        """返回 ids 中真实存在的那部分（删除计数要反映实际删掉多少）。"""
        try:
            rows = await storage.get_by_ids(ids)
        except Exception:  # noqa: BLE001 - 取不到就按「全在」处理，删除本身幂等
            return list(ids)
        if not isinstance(rows, list):
            return list(ids)
        found = []
        for i, row in enumerate(rows):
            if row and i < len(ids):
                found.append(ids[i])
        return found

    async def _purge_vectors(
        self,
        lr: Any,
        doc_id: str,
        chunk_ids: list[str],
        entity_names: list[str],
        relation_pairs: list[tuple[str, str]],
    ) -> dict[str, int]:
        """补删三套向量索引（chunks / entities / relationships）。

        上游删除只删「已经没有其它来源」的实体与关系，所以这里**不能**无条件
        按 entity_name 删——那会把仍被其它文档引用的实体一起删掉。判定标准是：
        该实体/关系在图里已经不存在（说明上游已判定它无剩余来源）。

        调用前必须先跑 `_purge_graph`：本方法依赖「图里已无该节点」这一事实。

        另外按 `file_path` 兜底：旧数据里实体名可能被改名/截断，导致
        `compute_mdhash_id(entity_name)` 算不出正确的向量 id；此时用
        「向量的 file_path 指向该文档」来定位，避免留下查得到的孤儿向量。
        """
        counts = {"chunks_vdb": 0, "entities_vdb": 0, "relationships_vdb": 0}
        graph = getattr(lr, "chunk_entity_relation_graph", None)

        chunks_vdb = getattr(lr, "chunks_vdb", None)
        if chunks_vdb is not None and chunk_ids:
            existing = await self._existing_vdb_ids(chunks_vdb, chunk_ids)
            if existing:
                await chunks_vdb.delete(existing)
            counts["chunks_vdb"] = len(existing)

        entities_vdb = getattr(lr, "entities_vdb", None)
        if entities_vdb is not None and (entity_names or doc_id):
            orphan_names = []
            for name in entity_names:
                if graph is not None:
                    try:
                        if await graph.has_node(name):
                            continue
                    except Exception:  # noqa: BLE001
                        pass
                orphan_names.append(name)
            existing = await self._existing_entity_vdb_ids(entities_vdb, orphan_names)
            existing.extend(
                await self._vdb_ids_by_file_path(entities_vdb, doc_id, existing)
            )
            if existing:
                await entities_vdb.delete(existing)
            counts["entities_vdb"] = len(existing)

        relationships_vdb = getattr(lr, "relationships_vdb", None)
        if relationships_vdb is not None and (relation_pairs or doc_id):
            rel_ids = []
            for src, tgt in relation_pairs:
                if graph is not None:
                    try:
                        if await graph.has_edge(src, tgt):
                            continue
                    except Exception:  # noqa: BLE001
                        pass
                rel_ids.extend(self._relation_vdb_ids(src, tgt))
            rel_ids.extend(
                await self._vdb_ids_by_file_path(relationships_vdb, doc_id, rel_ids)
            )
            if rel_ids:
                existing = await self._existing_vdb_ids(relationships_vdb, rel_ids)
                if existing:
                    await relationships_vdb.delete(existing)
                counts["relationships_vdb"] = len(existing)

        return counts

    async def _vdb_ids_by_file_path(
        self, vdb: Any, doc_id: str, already: list[str]
    ) -> list[str]:
        """按 `file_path` 找出指向该文档、但不在 `already` 里的向量 id。

        为什么要这一层：实体名被 LLM 改写（例如 `Acme Depot` → `ACMEDEPOT`）时，
        `compute_mdhash_id(旧名字)` 找不到向量，只能靠 file_path 反查。
        Redis 后端走 FT.SEARCH 的 TAG 查询；其它后端没有全表接口时返回空。
        """
        if not doc_id:
            return []
        index_name = getattr(vdb, "index_name", None)
        client = getattr(vdb, "_client", None)
        if not index_name or client is None:
            return []
        try:
            raw = await client.execute_command(
                "FT.SEARCH", index_name, f"@file_path:{{{self._escape_tag_value(doc_id)}}}",
                "NOCONTENT", "LIMIT", "0", "10000", "DIALECT", "2",
            )
        except Exception:  # noqa: BLE001 - 反查失败不阻断主流程
            return []
        keys = self._search_keys(raw)
        prefix = getattr(vdb, "key_prefix", "") or ""
        seen = set(already)
        out = []
        for key in keys:
            vid = key[len(prefix):] if prefix and key.startswith(prefix) else key
            if vid and vid not in seen:
                seen.add(vid)
                out.append(vid)
        return out

    @staticmethod
    def _escape_tag_value(value: str) -> str:
        """RediSearch TAG 查询转义（与 RedisVectorStorage._escape_tag 同规则）。"""
        specials = frozenset(',.<>{}[]"\':;!@#$%^&*()-+=~/\\| ')
        return "".join(("\\" + ch) if ch in specials else ch for ch in value)

    @staticmethod
    def _search_keys(raw: Any) -> list[str]:
        """从 FT.SEARCH 响应取完整 key，兼容 RESP3(dict) 与 RESP2(list)。"""
        keys: list[str] = []

        def _dec(v):
            return v.decode("utf-8", "replace") if isinstance(v, bytes) else v

        if isinstance(raw, dict):
            for entry in raw.get(b"results") or raw.get("results") or []:
                if isinstance(entry, dict):
                    k = _dec(entry.get(b"id") or entry.get("id"))
                    if k:
                        keys.append(k)
            return keys
        if isinstance(raw, list) and raw:
            for item in raw[1:]:
                if isinstance(item, (bytes, str)):
                    keys.append(_dec(item))
        return keys

    async def _existing_vdb_ids(self, vdb: Any, ids: list[str]) -> list[str]:
        unique = list(dict.fromkeys(i for i in ids if i))
        if not unique:
            return []
        try:
            rows = await vdb.get_by_ids(unique)
        except Exception:  # noqa: BLE001
            return unique
        if not isinstance(rows, list):
            return unique
        return [unique[i] for i, row in enumerate(rows) if row and i < len(unique)]

    async def _existing_entity_vdb_ids(self, vdb: Any, names: list[str]) -> list[str]:
        """实体名 → 向量 id（`ent-<md5(name)>`），只保留真实存在的。"""
        from lightrag.utils import compute_mdhash_id  # noqa: PLC0415 - 与上游算法保持一致

        ids = [compute_mdhash_id(name, prefix="ent-") for name in names if name]
        return await self._existing_vdb_ids(vdb, ids)

    @staticmethod
    def _relation_vdb_ids(src: str, tgt: str) -> list[str]:
        """关系向量 id：LightRAG 同时写正向与反向两个 hash，两个都要删。"""
        from lightrag.utils import compute_mdhash_id  # noqa: PLC0415

        return [
            compute_mdhash_id(src + tgt, prefix="rel-"),
            compute_mdhash_id(tgt + src, prefix="rel-"),
        ]

    async def _purge_graph(
        self,
        lr: Any,
        chunk_ids: list[str],
        entity_names: list[str],
        relation_pairs: list[tuple[str, str]],
    ) -> tuple[dict[str, int], list[str]]:
        """清理 graphml 里由该文档贡献、且已无其它来源的节点与边。

        这一步是「删除后仍能查到」的直接修复点：hybrid 从 entities/relationships
        向量取上下文，global 从 relationships 取；只要图里还留着节点/边，
        对应向量就会被重建出来继续参与检索。

        只删满足以下条件的记录（避免误删仍被其它文档引用的实体）：
          - `source_id` 里的 chunk 全部落在本次删除的 chunk 集合内，或
          - `source_id` 为空且 `file_path` 指向该文档。
        """
        counts = {"graph_nodes": 0, "graph_edges": 0}
        warnings: list[str] = []
        graph = getattr(lr, "chunk_entity_relation_graph", None)
        if graph is None:
            return counts, warnings

        chunk_set = set(chunk_ids)
        try:
            nodes = await graph.get_all_nodes()
            edges = await graph.get_all_edges()
        except Exception as e:  # noqa: BLE001
            return counts, [f"读取图失败，未能清理 graphml: {e}"]

        doomed_nodes: list[str] = []
        for node in nodes:
            name = node.get("id")
            if not isinstance(name, str) or not name:
                continue
            if self._solely_from_doc(node, chunk_set):
                doomed_nodes.append(name)

        doomed_edges: list[tuple[str, str]] = []
        for edge in edges:
            if not self._solely_from_doc(edge, chunk_set):
                continue
            src, tgt = edge.get("source"), edge.get("target")
            if isinstance(src, str) and isinstance(tgt, str):
                doomed_edges.append((src, tgt))

        if doomed_edges:
            try:
                await graph.remove_edges(doomed_edges)
                counts["graph_edges"] = len(doomed_edges)
            except Exception as e:  # noqa: BLE001
                warnings.append(f"删除图边失败（{len(doomed_edges)} 条）: {e}")

        # 上游可能已经删过一部分节点；这里用 has_node 复核，避免把已删的再算一次。
        remaining_nodes = []
        for name in doomed_nodes:
            try:
                if await graph.has_node(name):
                    remaining_nodes.append(name)
            except Exception:  # noqa: BLE001
                remaining_nodes.append(name)
        if remaining_nodes:
            try:
                await graph.remove_nodes(remaining_nodes)
                counts["graph_nodes"] = len(remaining_nodes)
            except Exception as e:  # noqa: BLE001
                warnings.append(f"删除图节点失败（{len(remaining_nodes)} 个）: {e}")

        return counts, warnings

    @staticmethod
    def _solely_from_doc(record: dict[str, Any], chunk_set: set[str]) -> bool:
        """记录是否「只由这些 chunk 贡献」——决定能否整条删除。"""
        refs = {
            part for part in str(record.get("source_id") or "").split(GRAPH_FIELD_SEP)
            if part
        }
        if not refs:
            return False
        return refs.issubset(chunk_set)

    @staticmethod
    def _merge_counts(target: dict[str, int], extra: dict[str, int]) -> None:
        """把 extra 累加进 target（同 key 相加，不是覆盖）。

        为什么不能直接 `dict.update`：图清理与向量清理都会产出
        `orphan_entities_vdb` 这类同名 key，覆盖会让统计少报，运维无法
        从返回体判断到底删了多少。

        保留 0 值：`graph_nodes: 0` 本身就是有效信息（说明图里确实没有该文档
        的节点），删掉这个 key 会让调用方无法区分「删了 0 条」与「没检查」。
        """
        for key, value in (extra or {}).items():
            target[key] = target.get(key, 0) + int(value or 0)

    async def _purge_orphan_graph(self, lr: Any) -> tuple[dict[str, int], list[str]]:
        """清理「所有来源 chunk 都已不存在」的图节点与边。

        这是删除失败后的兜底，不依赖任何 docId：

        场景：上一次删除已经把 `text_chunks` 删了，但进程在 `index_done_callback`
        写盘前退出（或写盘失败），graphml 里的节点仍带着指向已删 chunk 的
        `source_id`。这类节点不属于任何现存文档，却仍会被 hybrid/global 的
        实体/关系检索命中，把已删除文档的描述重新带进 LLM 上下文。

        安全性：只有「有 source_id 且全部引用都已失效」才删。source_id 为空
        的节点无法判定归属，一律保留（宁可留脏数据，也不误删有效实体）。

        向量侧的孤儿由 `_purge_orphan_vectors` 独立处理（它按向量自身的
        `source_id` 判定，实体被改名时也能清到），这里不重复删。
        """
        counts = {"orphan_graph_nodes": 0, "orphan_graph_edges": 0}
        warnings: list[str] = []
        graph = getattr(lr, "chunk_entity_relation_graph", None)
        if graph is None:
            return counts, warnings

        alive = {
            key for key, _ in self._iter_kv_items(getattr(lr, "text_chunks", None))
        }
        if not alive:
            # 拿不到 text_chunks 全表时无法判断「来源是否还存在」，不能盲删
            return counts, warnings

        def _orphan(record: dict[str, Any]) -> bool:
            refs = {
                part
                for part in str(record.get("source_id") or "").split(GRAPH_FIELD_SEP)
                if part
            }
            return bool(refs) and not (refs & alive)

        try:
            nodes = await graph.get_all_nodes()
            edges = await graph.get_all_edges()
        except Exception as e:  # noqa: BLE001
            return counts, [f"读取图失败，未能清理孤儿节点: {e}"]

        doomed_edges = [
            (e["source"], e["target"])
            for e in edges
            if _orphan(e) and isinstance(e.get("source"), str) and isinstance(e.get("target"), str)
        ]
        if doomed_edges:
            try:
                await graph.remove_edges(doomed_edges)
                counts["orphan_graph_edges"] = len(doomed_edges)
            except Exception as e:  # noqa: BLE001
                warnings.append(f"删除孤儿图边失败（{len(doomed_edges)} 条）: {e}")

        doomed_nodes = [n["id"] for n in nodes if isinstance(n.get("id"), str) and _orphan(n)]
        if doomed_nodes:
            try:
                await graph.remove_nodes(doomed_nodes)
                counts["orphan_graph_nodes"] = len(doomed_nodes)
            except Exception as e:  # noqa: BLE001
                warnings.append(f"删除孤儿图节点失败（{len(doomed_nodes)} 个）: {e}")

        if doomed_nodes or doomed_edges:
            log.info(
                "孤儿图清理：节点 %d、边 %d", len(doomed_nodes), len(doomed_edges)
            )
        return counts, warnings

    async def _purge_orphan_vectors(self, lr: Any) -> tuple[dict[str, int], list[str]]:
        """清理 entities/relationships 向量索引里「来源 chunk 全都不存在」的行。

        为什么需要独立一层：图与向量是两套存储，删除/重建任何一步中断都会让
        它们不一致。图里已经没有的实体，只要向量还在，`hybrid` 的实体检索
        （entities_vdb）与 `global` 的关系检索（relationships_vdb）仍会把
        已删文档的描述送进 LLM 上下文——这正是「删了还能查到」的最后一环。

        判定只看向量自身的 `source_id` 字段（LightRAG 写入时会把来源 chunk
        拼进去），与实体名/关系名无关，因此实体被 LLM 改名也能清到。

        仅对能全表扫描的向量后端生效（当前 Redis 适配器支持 SCAN + HGETALL）。
        拿不到全表时跳过并记录 warning，不静默假装干净。
        """
        counts = {"orphan_entities_vdb": 0, "orphan_relationships_vdb": 0}
        warnings: list[str] = []
        alive = {
            key for key, _ in self._iter_kv_items(getattr(lr, "text_chunks", None))
        }
        if not alive:
            return counts, warnings

        for attr, count_key in (
            ("entities_vdb", "orphan_entities_vdb"),
            ("relationships_vdb", "orphan_relationships_vdb"),
        ):
            vdb = getattr(lr, attr, None)
            rows = await self._scan_vdb_rows(vdb)
            if rows is None:
                warnings.append(f"{attr} 不支持全表扫描，未能清理孤儿向量")
                continue
            doomed = []
            for vid, fields in rows:
                refs = {
                    part
                    for part in str(fields.get("source_id") or "").split(GRAPH_FIELD_SEP)
                    if part
                }
                # 无 source_id 的行无法判定归属，保留（宁可留脏也不误删）
                if refs and not (refs & alive):
                    doomed.append(vid)
            if not doomed:
                continue
            try:
                await vdb.delete(doomed)
                counts[count_key] = len(doomed)
            except Exception as e:  # noqa: BLE001
                warnings.append(f"删除孤儿向量失败（{attr}, {len(doomed)} 条）: {e}")

        if any(counts.values()):
            log.info("孤儿向量清理：%s", {k: v for k, v in counts.items() if v})
        return counts, warnings

    async def _scan_vdb_rows(self, vdb: Any) -> list[tuple[str, dict[str, str]]] | None:
        """全表扫描向量库，返回 [(id, fields)]；后端不支持时返回 None。

        当前只对 Redis 适配器实现（`_client` + `key_prefix`）。其它后端返回
        None，由调用方转成 warning——不能因为拿不到全表就报告「删干净了」。
        """
        client = getattr(vdb, "_client", None)
        prefix = getattr(vdb, "key_prefix", None)
        if client is None or not prefix:
            return None

        def _dec(v):
            return v.decode("utf-8", "replace") if isinstance(v, bytes) else v

        rows: list[tuple[str, dict[str, str]]] = []
        try:
            keys = [k async for k in client.scan_iter(f"{prefix}*", count=1000)]
            for key in keys:
                raw = await client.hgetall(key)
                key_str = _dec(key)
                vid = key_str[len(prefix):] if key_str.startswith(prefix) else key_str
                fields = {_dec(k): _dec(v) for k, v in raw.items() if _dec(k) != "vec"}
                rows.append((vid, fields))
        except Exception as e:  # noqa: BLE001 - 扫描失败按「不支持」处理并告警
            log.warning("扫描向量库失败: %s", e)
            return None
        return rows

    async def _purge_chunk_tracking(
        self,
        lr: Any,
        chunk_ids: list[str],
        entity_names: list[str],
        relation_pairs: list[tuple[str, str]],
    ) -> dict[str, int]:
        """清理 entity_chunks / relation_chunks 里指向已删 chunk 的追踪行。

        这两张表是 LightRAG 判断「某实体还有哪些来源 chunk」的依据。留着已删
        chunk 的引用，会让下一次删除把已不存在的 chunk 当成「仍有其它来源」，
        于是共享实体永远删不掉。
        """
        counts = {"entity_chunks": 0, "relation_chunks": 0}
        chunk_set = set(chunk_ids)
        if not chunk_set:
            return counts

        entity_chunks = getattr(lr, "entity_chunks", None)
        if entity_chunks is not None and entity_names:
            drop: list[str] = []
            update: dict[str, dict[str, Any]] = {}
            now = int(time.time())
            for name in entity_names:
                rec = await self._safe_get(entity_chunks, name)
                if not rec:
                    continue
                kept = [c for c in rec.get("chunk_ids") or [] if c not in chunk_set]
                if kept:
                    update[name] = {
                        "chunk_ids": kept, "count": len(kept), "updated_at": now,
                    }
                else:
                    drop.append(name)
            if update:
                await entity_chunks.upsert(update)
            if drop:
                await entity_chunks.delete(drop)
            counts["entity_chunks"] = len(drop) + len(update)

        relation_chunks = getattr(lr, "relation_chunks", None)
        if relation_chunks is not None and relation_pairs:
            from lightrag.utils import make_relation_chunk_key  # noqa: PLC0415

            drop_keys: list[str] = []
            update_keys: dict[str, dict[str, Any]] = {}
            now = int(time.time())
            for src, tgt in relation_pairs:
                key = make_relation_chunk_key(src, tgt)
                rec = await self._safe_get(relation_chunks, key)
                if not rec:
                    continue
                kept = [c for c in rec.get("chunk_ids") or [] if c not in chunk_set]
                if kept:
                    update_keys[key] = {
                        "chunk_ids": kept, "count": len(kept), "updated_at": now,
                    }
                else:
                    drop_keys.append(key)
            if update_keys:
                await relation_chunks.upsert(update_keys)
            if drop_keys:
                await relation_chunks.delete(drop_keys)
            counts["relation_chunks"] = len(drop_keys) + len(update_keys)

        return counts

    async def _verify_clean(
        self,
        lr: Any,
        doc_id: str,
        chunk_ids: list[str],
        entity_names: list[str],
        relation_pairs: list[tuple[str, str]],
    ) -> list[str]:
        """复核：任何「还能查到」的残留都转成 warning，不静默成功。"""
        warnings: list[str] = []
        if await self._safe_get(lr.doc_status, doc_id):
            warnings.append("doc_status 仍存在该文档记录")
        if await self._safe_get(lr.full_docs, doc_id):
            warnings.append("full_docs 仍存在该文档记录")
        if await self._safe_get(lr.full_entities, doc_id):
            warnings.append("full_entities 仍存在该文档记录")
        if await self._safe_get(lr.full_relations, doc_id):
            warnings.append("full_relations 仍存在该文档记录")
        if chunk_ids:
            left = await self._existing_ids(lr.text_chunks, chunk_ids)
            if left:
                warnings.append(f"text_chunks 仍有 {len(left)} 个 chunk 未删除")
            chunks_vdb = getattr(lr, "chunks_vdb", None)
            if chunks_vdb is not None:
                left_v = await self._existing_vdb_ids(chunks_vdb, chunk_ids)
                if left_v:
                    warnings.append(f"chunks_vdb 仍有 {len(left_v)} 条向量未删除")

        graph = getattr(lr, "chunk_entity_relation_graph", None)
        if graph is not None:
            for name in entity_names:
                try:
                    if await graph.has_node(name):
                        node = await graph.get_node(name) or {}
                        if self._solely_from_doc(node, set(chunk_ids)):
                            warnings.append(f"图节点仍残留: {name}")
                except Exception:  # noqa: BLE001
                    pass
            for src, tgt in relation_pairs:
                try:
                    if await graph.has_edge(src, tgt):
                        edge = await graph.get_edge(src, tgt) or {}
                        if self._solely_from_doc(edge, set(chunk_ids)):
                            warnings.append(f"图边仍残留: {src} -> {tgt}")
                except Exception:  # noqa: BLE001
                    pass

        return warnings

    @staticmethod
    async def _safe_get(storage: Any, key: str) -> dict[str, Any] | None:
        if storage is None or not key:
            return None
        try:
            return await storage.get_by_id(key)
        except Exception:  # noqa: BLE001 - 复核阶段不应因单点异常中断
            return None

    def purge_query_cache(self) -> int:
        """删除 LLM 响应缓存里 `cache_type=query` 的条目，返回删除条数。

        为什么需要：LightRAG 的查询缓存 key 只由 query 文本（加 system prompt）
        算 hash，**不含检索到的上下文**。同一问题在文档删除前后命中同一条缓存，
        就会继续返回旧答案——这与向量/图是否删干净无关，属于独立的缓存层。

        本服务已把 `enable_llm_cache=false`（不再写新查询缓存），但磁盘上的
        历史条目仍在；`/purge-cache` 与删除流程都复用它。
        实体抽取缓存（`cache_type=extract`）保留：它按 chunk 内容做 key，
        且删除流程需要它来重建共享实体的描述。
        """
        path = Path(settings.working_dir) / "kv_store_llm_response_cache.json"
        try:
            if not path.exists():
                return 0
            raw = json.loads(path.read_text(encoding="utf-8") or "{}")
            if not isinstance(raw, dict):
                return 0
            kept = {
                k: v for k, v in raw.items()
                if not (isinstance(v, dict) and v.get("cache_type") == "query")
            }
            removed = len(raw) - len(kept)
            if removed:
                path.write_text(json.dumps(kept, ensure_ascii=False), encoding="utf-8")
                log.info("已清理 %d 条查询缓存（保留 %d 条抽取缓存）", removed, len(kept))
            return removed
        except Exception as e:  # noqa: BLE001 - 缓存清理失败不应让删除失败
            log.warning("清理查询缓存失败: %s", e)
            return 0

    async def health(self) -> dict[str, Any]:
        """健康检查：不触发 LLM 调用，只确认存储与配置就绪。"""
        try:
            rag = await self.get()
            vector_detail = self._vector_health()
            return {
                "ok": True,
                "workingDir": settings.working_dir,
                "llmBaseUrl": settings.llm_base_url,
                "llmModel": settings.llm_model,
                "visionModel": settings.vision_model,
                "embedModel": settings.embed_model,
                "embedDim": settings.embed_dim,
                "vectorBackend": self._vector_backend,
                "vector": vector_detail,
                "initialized": rag is not None,
            }
        except Exception as e:  # noqa: BLE001 - 健康检查要吞掉异常并如实报告
            log.exception("健康检查失败")
            return {"ok": False, "error": str(e)}

    def _vector_health(self) -> dict[str, Any]:
        """向量存储的可观测信息：后端、索引名、文档数、Redis 是否可达。

        只读、不建索引——健康检查不应有副作用。
        """
        if self._vector_backend != "RedisVectorStorage":
            return {"backend": self._vector_backend or "uninitialized"}
        info: dict[str, Any] = {"backend": "redis"}
        try:
            from .redis_vector_store import _index_name  # noqa: PLC0415

            rag = self._rag
            lightrag = getattr(rag, "lightrag", None) if rag else None
            workspace = getattr(lightrag, "workspace", "") or ""
            info["indexes"] = {
                ns: _index_name(workspace, ns)
                for ns in ("entities", "relationships", "chunks")
            }
        except Exception as exc:  # noqa: BLE001
            info["indexError"] = str(exc)
        return info


engine = RagEngine()

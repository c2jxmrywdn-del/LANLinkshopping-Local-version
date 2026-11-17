"""LightRAG 的 Redis Stack（RediSearch HNSW）向量存储适配器。

为什么需要这个文件
------------------
LightRAG 1.4.x 的 `lightrag/kg/redis_impl.py` 只提供 **KV** 与 **DocStatus** 两种
Redis 存储，**没有 Vector 实现**（实测 `grep "^class " redis_impl.py` 只有
`RedisConnectionManager` / `RedisKVStorage` / `RedisDocStatusStorage`）。

这就是 `docker-compose.yml` 里「存储独立」注释的由来：无法直接复用现有
`aikb-idx`，索引只能落在独立卷 `rag-storage`（NanoVectorDB 的 JSON 文件）。

本适配器补上缺失的一环：把 LightRAG 的向量层接到 Redis Stack 的 RediSearch
HNSW 上，并**使用完全独立的索引名与 key 前缀**，与既有两套索引互不连坐：

| 索引 | key 前缀 | 归属 |
|:---|:---|:---|
| `aikb-idx` | `aikb:vblob:` | Java 知识库（KnowledgeRagService） |
| `prod-idx` | `prod:vblob:` | Java 商品向量（ProductVectorStore） |
| `rag-idx:<workspace>:<namespace>` | `rag:vblob:<workspace>:<namespace>:` | 本适配器 |

为什么按 namespace 分索引而不是单索引加 TAG 过滤
------------------------------------------------
LightRAG 有 3 个向量命名空间（entities / relationships / chunks），三者
`meta_fields` 不同。分索引后每个索引的 schema 可以精确匹配自己的字段，
不需要为全部命名空间声明并集字段；`DROPINDEX` 也天然只影响自己。

为什么不复用 `aikb-idx`
----------------------
`aikb-idx` 的 schema 是 `{source TAG, vec VECTOR, title TEXT, text TEXT}`，
而 LightRAG 需要存 `entity_name` / `src_id` / `tgt_id` / `full_doc_id` /
`file_path` 等字段，且同一个 `id` 的语义完全不同（aikb 用内容哈希，
LightRAG 用 `ent-<md5>` / `rel-<md5>` / `chunk-<md5>`）。
混用会导致两套检索互相污染，且 `DROPINDEX aikb-idx` 会连带删掉 RAG 数据。
"""
from __future__ import annotations

import asyncio
import json
import os
import struct
from dataclasses import dataclass
from typing import Any, final

import redis.asyncio as aioredis

from lightrag.base import BaseVectorStorage
from lightrag.utils import logger


def _env(name: str, default: str = "") -> str:
    return (os.getenv(name) or default).strip()


def _index_name(workspace: str, namespace: str) -> str:
    """索引名：rag-idx:<workspace>:<namespace>，workspace 为空时用 `_` 占位。

    只允许字母数字与下划线——RediSearch 的索引名会出现在 `FT.CREATE` 命令行里，
    带空格或冒号的奇怪值容易在后续运维脚本里出问题。
    """
    ws = "".join(c if c.isalnum() or c == "_" else "_" for c in (workspace or "_"))
    ns = "".join(c if c.isalnum() or c == "_" else "_" for c in (namespace or "default"))
    return f"rag-idx:{ws}:{ns}"


def _key_prefix(workspace: str, namespace: str) -> str:
    """HASH key 前缀：rag:vblob:<workspace>:<namespace>:"""
    ws = "".join(c if c.isalnum() or c == "_" else "_" for c in (workspace or "_"))
    ns = "".join(c if c.isalnum() or c == "_" else "_" for c in (namespace or "default"))
    return f"rag:vblob:{ws}:{ns}:"


def _to_f32(vector: list[float]) -> bytes:
    """float 列表 → 小端 float32 字节串（RediSearch VECTOR FLOAT32 的 wire 格式）。"""
    return struct.pack(f"<{len(vector)}f", *vector)


@final
@dataclass
class RedisVectorStorage(BaseVectorStorage):
    """RediSearch HNSW 向量存储。

    设计取舍：
      - **向量以 HASH 字段 `vec` 存 float32 原始字节**，不用 base64/zlib：
        RediSearch 要求向量字段是二进制，压缩后无法建索引；
      - **元数据全部以字符串存 HASH 字段**，`meta_fields` 声明的字段额外建
        TAG 索引（用于 `delete_entity` 按实体名反查）；
      - **不缓存**：Redis 本身就是外部存储，进程内再缓存会引入一致性问题。
    """

    def __post_init__(self):
        self._validate_embedding_func()

        self.redis_url = _env("REDIS_URI", "redis://redis:6379/0")
        self.index_name = _index_name(self.workspace, self.namespace)
        self.key_prefix = _key_prefix(self.workspace, self.namespace)
        self.embedding_dim = int(self.embedding_func.embedding_dim)
        self._max_batch_size = int(
            (self.global_config or {}).get("embedding_batch_num") or 16
        )

        kwargs = (self.global_config or {}).get("vector_db_storage_cls_kwargs", {}) or {}
        threshold = kwargs.get("cosine_better_than_threshold")
        if threshold is None:
            raise ValueError(
                "cosine_better_than_threshold must be specified in vector_db_storage_cls_kwargs"
            )
        self.cosine_better_than_threshold = float(threshold)

        self._client: aioredis.Redis | None = None
        self._index_ready = False
        self._init_lock: asyncio.Lock | None = None

    # ------------------------------------------------------------------ 连接

    async def initialize(self):
        """建连接 + 建索引（幂等）。"""
        if self._client is None:
            self._client = aioredis.from_url(
                self.redis_url, encoding="utf-8", decode_responses=False
            )
        if self._init_lock is None:
            self._init_lock = asyncio.Lock()
        async with self._init_lock:
            if not self._index_ready:
                await self._ensure_index()
                self._index_ready = True
        logger.info(
            "[rag-redis] initialized index=%s dim=%d threshold=%.2f",
            self.index_name, self.embedding_dim, self.cosine_better_than_threshold,
        )

    async def _ensure_index(self) -> None:
        """创建索引；已存在且维度一致则直接复用，维度不一致则报错而非静默重建。"""
        client = self._require_client()
        try:
            info = await client.execute_command("FT.INFO", self.index_name)
            existing_dim = self._extract_dim(info)
            if existing_dim is not None and existing_dim != self.embedding_dim:
                raise RuntimeError(
                    f"索引 {self.index_name} 已存在但维度为 {existing_dim}，"
                    f"当前 embedding 维度为 {self.embedding_dim}。"
                    f"换 embedding 模型必须整库重建：先 FT.DROPINDEX {self.index_name} DD，再重新 ingest。"
                )
            return
        except aioredis.ResponseError as exc:
            if "Unknown index name" not in str(exc) and "no such index" not in str(exc).lower():
                raise

        # 元数据字段：meta_fields 里除 content 外的都建 TAG（便于按实体名反查），
        # content 本身不建索引——它只用于回填结果，建 TEXT 索引会白占内存。
        tag_fields = [f for f in sorted(self.meta_fields) if f != "content"]
        args: list[Any] = [
            "FT.CREATE", self.index_name,
            "ON", "HASH",
            "PREFIX", "1", self.key_prefix,
            "SCHEMA",
            # VECTOR 属性语法：
            #   <field> VECTOR <ALGORITHM> <COUNT> <param>...
            # COUNT 必须精确等于其后 token 个数，多一个少一个都会被当成
            # 「下一个 schema 字段名」→ `Invalid field type for field EF_CONSTRUCTION`。
            # 下面 6 组键值对共 10 个 token，所以 COUNT=10。
            # M=16 / EF_CONSTRUCTION=200 与 aikb-idx 保持一致，便于横向对比召回率。
            "vec", "VECTOR", "HNSW", "10",
            "TYPE", "FLOAT32",
            "DIM", str(self.embedding_dim),
            "DISTANCE_METRIC", "COSINE",
            "M", "16",
            "EF_CONSTRUCTION", "200",
            "workspace_id", "TAG",
        ]
        for f in tag_fields:
            args.extend([f, "TAG", "SEPARATOR", "|"])
        await client.execute_command(*args)
        logger.info("[rag-redis] created index=%s dim=%d", self.index_name, self.embedding_dim)

    @staticmethod
    def _extract_dim(info: Any) -> int | None:
        """从 FT.INFO 的扁平响应里取 `dim`。

        实测 redis-py 8.x 对 FT.INFO 返回 **dict，且 key/value 都是 bytes**
        （`{b'attributes': [{b'identifier': b'vec', ..., b'dim': 1024}]}`）。
        老版本/其它 RESP 模式下可能退化为扁平 list，两种形态都要兼容。
        """
        try:
            if isinstance(info, dict):
                attrs = RedisVectorStorage._dict_get(info, "attributes") or []
                for a in attrs:
                    if not isinstance(a, dict):
                        continue
                    # `_dict_get` 取到的是 bytes（b"vec"），必须解码后再比字符串，
                    # 否则 `b"vec" == "vec"` 恒 False，维度冲突检查形同虚设。
                    ident = RedisVectorStorage._decode(
                        RedisVectorStorage._dict_get(a, "identifier")
                    )
                    if ident == "vec":
                        dim = RedisVectorStorage._dict_get(a, "dim")
                        return int(dim) if dim is not None else None
                return None
            # list 形态：... attributes [ identifier vec ... dim 1024 ... ]
            # 注意：本方法是 @staticmethod，**不能**用 self（历史缺陷：写成 self._decode
            # 会抛 NameError，被外层 except 吞掉 → list 分支恒返回 None，
            # RESP2 降级时维度冲突检查静默失效）。
            items = [RedisVectorStorage._decode(x) for x in info]
            for i, key in enumerate(items):
                if key == "dim" and i + 1 < len(items):
                    try:
                        return int(items[i + 1])
                    except (TypeError, ValueError):
                        continue
        except Exception:  # noqa: BLE001 - 解析失败不阻断，按「未知」处理
            return None
        return None

    @staticmethod
    def _dict_get(d: dict, key: str) -> Any:
        """按 str 或 bytes key 取值（redis-py 返回的 dict key 是 bytes）。"""
        if key in d:
            return d[key]
        bkey = key.encode("utf-8")
        if bkey in d:
            return d[bkey]
        return None

    @staticmethod
    def _decode(value: Any) -> Any:
        if isinstance(value, bytes):
            return value.decode("utf-8", "replace")
        return value

    def _require_client(self) -> aioredis.Redis:
        if self._client is None:
            raise RuntimeError("RedisVectorStorage 尚未 initialize()")
        return self._client

    def _key(self, doc_id: str) -> str:
        return f"{self.key_prefix}{doc_id}"

    # ------------------------------------------------------------------ 写入

    async def upsert(self, data: dict[str, dict[str, Any]]) -> None:
        """批量写入。向量由 embedding_func 统一计算，分批避免上游超时。"""
        if not data:
            return
        client = self._require_client()

        ids = list(data.keys())
        contents = [data[i].get("content") or "" for i in ids]

        batches = [
            (ids[i:i + self._max_batch_size], contents[i:i + self._max_batch_size])
            for i in range(0, len(ids), self._max_batch_size)
        ]
        embedding_tasks = [
            self.embedding_func(batch_contents, context="document")
            for _, batch_contents in batches
        ]
        embeddings_list = await asyncio.gather(*embedding_tasks)

        flat: list[list[float]] = []
        for emb in embeddings_list:
            for row in emb:
                flat.append(list(row))

        if len(flat) != len(ids):
            logger.error(
                "[rag-redis] embedding 数量与数据不一致 %d != %d（index=%s）",
                len(flat), len(ids), self.index_name,
            )
            return

        pipe = client.pipeline(transaction=False)
        for doc_id, vector, payload in zip(ids, flat, (data[i] for i in ids)):
            if len(vector) != self.embedding_dim:
                logger.error(
                    "[rag-redis] 维度不符 id=%s got=%d want=%d，跳过",
                    doc_id, len(vector), self.embedding_dim,
                )
                continue
            mapping: dict[bytes | str, bytes | str] = {
                "vec": _to_f32(vector),
                "workspace_id": self.workspace or "_",
            }
            for field in self.meta_fields:
                value = payload.get(field)
                if value is None:
                    continue
                mapping[field] = value if isinstance(value, str) else json.dumps(
                    value, ensure_ascii=False
                )
            # HSET 覆盖写：LightRAG 的 upsert 语义就是「同 id 覆盖」
            pipe.hset(self._key(doc_id), mapping=mapping)
        await pipe.execute()

    # ------------------------------------------------------------------ 查询

    async def query(
        self, query: str, top_k: int, query_embedding: list[float] = None
    ) -> list[dict[str, Any]]:
        """KNN 检索。

        RediSearch 的 COSINE 返回的是**距离**（0=完全相同，2=完全相反），
        而 LightRAG 下游按「相似度越大越好」使用，所以这里换算成
        `similarity = 1 - distance`，与 `aikb-idx` 的口径保持一致。
        """
        client = self._require_client()

        if query_embedding is not None:
            embedding = list(query_embedding)
        else:
            emb = await self.embedding_func([query], context="query", _priority=5)
            embedding = list(emb[0])

        if len(embedding) != self.embedding_dim:
            logger.error(
                "[rag-redis] query 向量维度不符 got=%d want=%d",
                len(embedding), self.embedding_dim,
            )
            return []

        blob = _to_f32(embedding)
        # KNN 的 k 与 LIMIT 的 k 不能同值。
        #
        # 历史缺陷：两者都取 top_k，于是「先按距离取 k 条、再按阈值过滤」时，
        # 阈值砍掉 m 条后只剩 k-m 条，上层拿到的永远少于请求的 top_k
        # （实测 top_k=5 时若前 5 条里有 2 条低于 0.1 阈值，只返回 3 条）。
        # 现在 KNN 多取候选（top_k 的 3 倍，上限 100），过滤后再截断到 top_k。
        k = max(1, int(top_k))
        candidates = min(max(k * 3, k), 100)

        # RETURN 的第一个参数是**字段个数**，必须与实际字段名数量精确相等。
        # 历史缺陷：这里写死 `RETURN 4`，但 meta_fields 因命名空间而异
        # （entities 有 entity_name/source_id/file_path，relationships 还有 src_id/tgt_id），
        # 多出来的字段名会被 RediSearch 当成命令参数解析，报
        # `Unknown argument 'source_id' at position 13`，整次查询失败。
        # 注意 distance 也算一个字段，所以 count = 1 + 1 + len(其它 meta)。
        extra_fields = sorted(self.meta_fields - {"content"})
        return_fields = ["distance", "content", *extra_fields]
        args = [
            "FT.SEARCH", self.index_name,
            f"*=>[KNN {candidates} @vec $BLOB AS distance]",
            "PARAMS", "2", "BLOB", blob,
            "SORTBY", "distance",
            "RETURN", str(len(return_fields)), *return_fields,
            # LIMIT 必须显式给：RediSearch 默认只返回前 10 条，
            # 不写 LIMIT 时 top_k=20 会被静默截断成 10（实测 15 条文档只回 10 条）。
            "LIMIT", "0", str(candidates),
            "DIALECT", "2",
        ]
        raw = await client.execute_command(*args)
        # 阈值过滤在 _parse_search 里做，过滤后再截断到调用方要求的条数
        return self._parse_search(raw)[:k]

    def _parse_search(self, raw: Any) -> list[dict[str, Any]]:
        """把 FT.SEARCH 响应解成 LightRAG 期望的 dict 列表。

        实测 redis-py 8.x 默认 RESP3，FT.SEARCH 返回 **dict**：
            {
              b"total_results": 1,
              b"results": [
                {b"id": b"<key>",
                 b"extra_attributes": {b"distance": b"0", b"content": b"..."}}
              ],
            }
        RESP2 下则退化为扁平 list：`[total, key1, [f, v, ...], ...]`。
        两种形态都兼容，避免 redis-py 升级/降级时静默返回空结果。
        """
        out: list[dict[str, Any]] = []
        if isinstance(raw, dict):
            entries = self._dict_get(raw, "results") or []
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                key = self._dict_get(entry, "id")
                attrs = self._dict_get(entry, "extra_attributes") or {}
                record: dict[str, Any] = {}
                for k, v in attrs.items():
                    record[self._decode(k)] = self._decode(v)
                self._append_record(out, key, record)
            return out

        if not isinstance(raw, list) or not raw:
            return out
        total = raw[0]
        if not isinstance(total, int) or total == 0:
            return out
        i = 1
        while i + 1 < len(raw):
            key = raw[i]
            fields = raw[i + 1]
            i += 2
            if not isinstance(fields, list):
                continue
            record = {}
            for j in range(0, len(fields) - 1, 2):
                record[self._decode(fields[j])] = self._decode(fields[j + 1])
            self._append_record(out, key, record)
        return out

    def _append_record(self, out: list[dict[str, Any]], key: Any, record: dict[str, Any]) -> None:
        """统一做「key → id」与「distance → similarity」换算后追加。"""
        key_str = self._decode(key) or ""
        doc_id = key_str[len(self.key_prefix):] if key_str.startswith(self.key_prefix) else key_str

        distance = record.pop("distance", None)
        try:
            distance_f = float(distance) if distance is not None else 2.0
        except (TypeError, ValueError):
            distance_f = 2.0
        similarity = 1.0 - distance_f
        if similarity < self.cosine_better_than_threshold:
            return

        record["id"] = doc_id
        record["distance"] = similarity
        out.append(record)

    # ------------------------------------------------------------------ 读取

    async def get_by_id(self, id: str) -> dict[str, Any] | None:
        client = self._require_client()
        raw = await client.hgetall(self._key(id))
        if not raw:
            return None
        return self._to_record(id, raw)

    async def get_by_ids(self, ids: list[str]) -> list[dict[str, Any]]:
        if not ids:
            return []
        client = self._require_client()
        pipe = client.pipeline(transaction=False)
        for doc_id in ids:
            pipe.hgetall(self._key(doc_id))
        results = await pipe.execute()
        out: list[dict[str, Any]] = []
        for doc_id, raw in zip(ids, results):
            if raw:
                out.append(self._to_record(doc_id, raw))
        return out

    def _to_record(self, doc_id: str, raw: dict[Any, Any]) -> dict[str, Any]:
        record: dict[str, Any] = {"id": doc_id}
        for k, v in raw.items():
            name = self._decode(k)
            if name == "vec":
                continue  # 原始向量不向上层暴露（体积大且无消费方）
            value = self._decode(v)
            if name in ("workspace_id",):
                continue
            record[name] = value
        return record

    async def get_vectors_by_ids(self, ids: list[str]) -> dict[str, list[float]]:
        if not ids:
            return {}
        client = self._require_client()
        pipe = client.pipeline(transaction=False)
        for doc_id in ids:
            pipe.hget(self._key(doc_id), "vec")
        results = await pipe.execute()

        out: dict[str, list[float]] = {}
        for doc_id, blob in zip(ids, results):
            if not blob:
                continue
            try:
                count = len(blob) // 4
                out[doc_id] = list(struct.unpack(f"<{count}f", blob))
            except Exception:  # noqa: BLE001
                continue
        return out

    # ------------------------------------------------------------------ 删除

    async def delete(self, ids: list[str]) -> None:
        if not ids:
            return
        client = self._require_client()
        await client.delete(*[self._key(i) for i in ids])

    async def delete_entity(self, entity_name: str) -> None:
        """按实体名删除（entities 命名空间的 `entity_name` TAG 索引）。"""
        if not entity_name:
            return
        await self._delete_by_tag("entity_name", entity_name)

    async def delete_entity_relation(self, entity_name: str) -> None:
        """按实体名删除关系（relationships 的 `src_id` / `tgt_id` 都可能命中）。"""
        if not entity_name:
            return
        await self._delete_by_tag("src_id", entity_name)
        await self._delete_by_tag("tgt_id", entity_name)

    async def _delete_by_tag(self, field: str, value: str) -> None:
        if field not in self.meta_fields:
            return
        client = self._require_client()
        # RediSearch 的 TAG 查询里，`{...}` 内部是**查询语法上下文**，
        # 下列字符都必须转义，否则直接 Syntax error：
        #   , . < > { } [ ] " ' : ; ! @ # $ % ^ & * ( ) - + = ~ / \ | 空格
        # 历史缺陷：只转了 `-` `|` 空格三个，于是
        #   `ACME (中国) 有限公司` → Syntax error near 有限公司
        #   `100%纯棉`             → Syntax error near 纯棉
        #   `x{y}`                 → Syntax error near x
        # 异常被 except 吞掉后 delete_entity **静默不删**，留下孤儿向量
        # （索引里查得到、图里已不存在），且没有任何日志提示。
        safe = self._escape_tag(value)
        try:
            raw = await client.execute_command(
                "FT.SEARCH", self.index_name, f"@{field}:{{{safe}}}",
                "NOCONTENT", "LIMIT", "0", "10000", "DIALECT", "2",
            )
        except aioredis.ResponseError as exc:
            logger.warning("[rag-redis] FT.SEARCH 按 %s 反查失败: %s", field, exc)
            return
        keys = self._search_keys(raw)
        if keys:
            await client.delete(*keys)

    #: 需要反斜杠转义的 TAG 特殊字符集合（按 RediSearch 官方文档）。
    _TAG_SPECIALS = frozenset(',.<>{}[]"\':;!@#$%^&*()-+=~/\\| ')

    @staticmethod
    def _escape_tag(value: str) -> str:
        """按 RediSearch TAG 规范转义全部特殊字符。

        用逐字符拼接而不是正则替换：正则里要把这堆符号塞进字符类需要大量转义，
        容易漏字符，而漏一个就又是 Syntax error（正是历史缺陷的成因）。
        """
        if not value:
            return ""
        out = []
        for ch in value:
            if ch in RedisVectorStorage._TAG_SPECIALS:
                out.append("\\")
            out.append(ch)
        return "".join(out)

    def _search_keys(self, raw: Any) -> list[str]:
        """从 FT.SEARCH 响应里取出命中的完整 key（含前缀）。

        RESP3（dict + results[*].id）与 RESP2（[total, key, ...]）两种形态都处理。
        注意这里返回的是**带前缀的完整 key**，供 DEL 直接使用；
        与 `_parse_search` 返回的裸 id 不同。
        """
        keys: list[str] = []
        if isinstance(raw, dict):
            for entry in (self._dict_get(raw, "results") or []):
                if isinstance(entry, dict):
                    k = self._decode(self._dict_get(entry, "id"))
                    if k:
                        keys.append(k)
            return keys
        if isinstance(raw, list) and raw:
            # [total, key1, key2, ...]（NOCONTENT 时没有字段数组）
            for item in raw[1:]:
                if isinstance(item, (bytes, str)):
                    keys.append(self._decode(item))
        return keys

    # ------------------------------------------------------------------ 生命周期

    async def index_done_callback(self) -> None:
        """RediSearch 写入即生效，无需额外提交。"""
        return None

    async def drop(self) -> dict[str, str]:
        """删除索引与全部数据（DD = 连文档一起删）。"""
        client = self._require_client()
        try:
            await client.execute_command("FT.DROPINDEX", self.index_name, "DD")
        except aioredis.ResponseError as exc:
            if "Unknown index name" not in str(exc):
                return {"status": "error", "message": str(exc)}
        self._index_ready = False
        await self._ensure_index()
        self._index_ready = True
        return {"status": "success", "message": "data dropped"}

    async def finalize(self):
        if self._client is not None:
            try:
                await self._client.aclose()
            except Exception:  # noqa: BLE001
                pass
            self._client = None

# -*- coding: utf-8 -*-
"""网关前置两级语义缓存引擎（Two-Tier Semantic Cache）。

核心机制：
1. 两级匹配架构 (Two-Tier Hierarchy)：
   - Tier 1 精确哈希匹配 (Exact Hash Match)：
     基于 SHA-256(role + ":" + normalized_query) 进行 O(1) 毫秒级直接查找，时延 < 0.5ms，零向量计算开销；
   - Tier 2 向量相似度近邻检索 (Vector Similarity Search - VSS)：
     利用 Ollama nomic-embed-text 生成 768 维 Query Embedding 并经 L2 归一化，
     在 RediSearch 索引 `aigateway:scache:idx` 执行余弦 KNN 检索。
     安全阈值：Cosine Distance <= 0.04 (等价余弦相似度 >= 0.96) 判定为语义等价命中。
2. 角色与多租户隔离：
   - 严格在 TAG 字段过滤 `@role:{buyer|merchant|ops}`，杜绝跨角色权限与回答内容交叉污染。
3. 交易安全与隐私围栏 (Financial & Privacy Fence)：
   - 包含动态订单号（ORD-*）、支付草稿、手机号、密码验证码等隐私/动态写操作请求一律禁止入缓存。
4. 高性能响应与流式重放 (SSE Streaming Replay)：
   - 命中时直接构造 OpenAI Completions 报文，流式模式下以微延迟平滑重放 SSE chunk；
   - 注入 X-Lanlink-Cache: HIT-EXACT / HIT-SEMANTIC / MISS 与 X-Lanlink-Cache-Sim 响应头。
5. 异步写回与主动失效：
   - 异步后台写回，不占用用户首包延迟 (TTFT)；
   - 提供 /cache/invalidate 端点与支持 TTL（默认 7200 秒）。
6. 双轨容灾 (In-Memory Fallback)：
   - Redis 断连或无 RediSearch 模块时，自动降级为线程安全内存 LRU 与内存向量余弦匹配，保障服务零报错。
"""
import base64
import hashlib
import json
import logging
import math
import os
import re
import struct
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, Generator, List, Optional, Tuple

logger = logging.getLogger("lanlink.semantic_cache")

EXACT_KEY_PREFIX = "aigateway:scache:exact:"
DOC_KEY_PREFIX = "aigateway:scache:doc:"
CACHE_INDEX_NAME = "aigateway:scache:idx"
DEFAULT_SIMILARITY_THRESHOLD = 0.96  # 余弦相似度 >= 0.96 (距离 <= 0.04)
DEFAULT_CACHE_TTL = int(os.environ.get("GATEWAY_CACHE_TTL", "7200"))  # 默认 2 小时
DEFAULT_CACHE_INVALIDATE_CHANNEL = os.environ.get(
    "GATEWAY_CACHE_INVALIDATE_CHANNEL", "aigateway:cache:invalidate"
)

# 动态敏感正则：命中则绝对不予缓存（防隐私泄露与越权）
SENSITIVE_QUERY_PATTERNS = [
    re.compile(r"\bORD[-_A-Za-z0-9]+\b", re.IGNORECASE),  # 订单号
    re.compile(r"\bTX[-_A-Za-z0-9]+\b", re.IGNORECASE),   # 交易流水号
    re.compile(r"\b1[3-9]\d{9}\b"),                       # 手机号（独立11位）
    re.compile(r"(密码|验证码|支付码|token|secret|apiKey)", re.IGNORECASE),
]

SENSITIVE_CONTENT_PATTERNS = [
    re.compile(r"\bORD[-_A-Za-z0-9]+\b", re.IGNORECASE),  # 订单号
    re.compile(r"\bTX[-_A-Za-z0-9]+\b", re.IGNORECASE),   # 交易流水号
    re.compile(r"(支付密码|验证码为|临时密码|私钥|秘钥)", re.IGNORECASE),
]


def _decode(val: Any) -> str:
    if isinstance(val, bytes):
        return val.decode("utf-8", "replace")
    return "" if val is None else str(val)


def _normalize_query(query: str) -> str:
    """标准化查询文本：去除多余空白、标点与大小写统一"""
    if not query:
        return ""
    q = query.strip().lower()
    q = re.sub(r"[\s\t\r\n]+", " ", q)
    q = re.sub(r"[，。！？；：、“”‘’\?\,\!\.\;\:]+$", "", q)
    return q.strip()


@dataclass
class CacheHit:
    hit_type: str                   # "exact" | "semantic"
    similarity: float               # 1.0 或 0.96~1.0
    content: str                    # 回答文本
    reasoning_content: str = ""     # 思考链文本
    scene: str = ""                 # 业务场景
    docs: List[Dict[str, Any]] = field(default_factory=list)
    cache_id: str = ""
    latency_ms: float = 0.0


class CachePubSubListener:
    """监听 Redis Pub/Sub 广播事件，自动触发本地及 Redis 语义缓存的主动失效闭环。"""

    def __init__(self, cache_instance: "SemanticCache", channel: str = DEFAULT_CACHE_INVALIDATE_CHANNEL):
        self.cache = cache_instance
        self.channel = channel
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._running = False

    def start(self):
        if self._running:
            return
        self._running = True
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="cache-pubsub-listener", daemon=True)
        self._thread.start()
        logger.info("已启动 Redis Pub/Sub 缓存失效监听线程，监听通道: %s", self.channel)

    def stop(self):
        self._running = False
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        logger.info("已停止 Redis Pub/Sub 缓存失效监听线程")

    def _run_loop(self):
        backoff = 1.0
        while not self._stop_event.is_set():
            client = None
            pubsub = None
            try:
                client = self.cache._get_redis()
                if client is None:
                    time.sleep(1.0)
                    continue
                pubsub = client.pubsub()
                pubsub.subscribe(self.channel)
                logger.debug("Redis Pub/Sub 成功订阅通道: %s", self.channel)
                backoff = 1.0

                while not self._stop_event.is_set():
                    msg = pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
                    if not msg:
                        continue
                    if msg.get("type") == "message":
                        data_raw = msg.get("data")
                        data_str = _decode(data_raw)
                        logger.info("收到 Redis 缓存失效广播: %s", data_str)
                        try:
                            payload = json.loads(data_str) if data_str else {}
                        except Exception:
                            payload = {"role": data_str} if data_str else {}
                        role = payload.get("role")
                        if role in ("all", "*", None, ""):
                            self.cache.invalidate()
                        else:
                            self.cache.invalidate(role=role)
            except Exception as exc:
                if not self._stop_event.is_set():
                    logger.debug("Redis Pub/Sub 监听异常，%s 秒后重连: %s", backoff, exc)
                    time.sleep(backoff)
                    backoff = min(backoff * 2, 15.0)
            finally:
                if pubsub:
                    try:
                        pubsub.close()
                    except Exception:
                        pass


class SemanticCache:
    """企业级两级语义缓存管理器"""

    def __init__(
        self,
        redis_client: Optional[Any] = None,
        embedder: Optional[Any] = None,
        dim: int = 768,
        similarity_threshold: float = DEFAULT_SIMILARITY_THRESHOLD,
        ttl_seconds: int = DEFAULT_CACHE_TTL,
    ):
        self._client = redis_client
        self._embedder = embedder
        self.dim = dim
        self.similarity_threshold = similarity_threshold
        self.distance_threshold = round(1.0 - similarity_threshold, 4)
        self.ttl = ttl_seconds
        self._lock = threading.Lock()

        # 内存双轨降级容器
        self._mem_exact: Dict[str, Dict[str, Any]] = {}
        self._mem_vectors: List[Dict[str, Any]] = []
        self._max_mem_entries = 1000
        # 异步失效 MVCC 时间戳，防止后台异步写回覆盖失效指令
        self._last_invalidate_time: Dict[str, float] = {}

    def _is_invalidated_since(self, role: str, t: float) -> bool:
        """检查自 t 刻起是否发生过主动失效指令。"""
        with self._lock:
            role_key = (role or "buyer").strip().lower()
            return (self._last_invalidate_time.get(role_key, 0.0) >= t or
                    self._last_invalidate_time.get("", 0.0) >= t)

        # RediSearch 可用性探针状态
        self._redis_available = True
        self._redisearch_ready = False
        self._last_index_check = 0.0

        # Redis Pub/Sub 广播自动失效监听器
        self._pubsub_listener: Optional[CachePubSubListener] = None
        if os.environ.get("GATEWAY_ENABLE_PUBSUB_INVALIDATION", "true").lower() in ("1", "true", "yes"):
            self.start_pubsub_listener()

    def start_pubsub_listener(self, channel: Optional[str] = None):
        """启动后台 Redis Pub/Sub 监听线程，感知后端主动失效广播。"""
        with self._lock:
            if self._pubsub_listener is None:
                ch = channel or DEFAULT_CACHE_INVALIDATE_CHANNEL
                self._pubsub_listener = CachePubSubListener(self, channel=ch)
                self._pubsub_listener.start()

    def stop_pubsub_listener(self):
        """停止后台 Redis Pub/Sub 监听线程。"""
        with self._lock:
            if self._pubsub_listener is not None:
                self._pubsub_listener.stop()
                self._pubsub_listener = None

    def is_pubsub_listener_running(self) -> bool:
        """检查 Pub/Sub 监听线程运行状态。"""
        return self._pubsub_listener is not None and self._pubsub_listener._running

    def _get_redis(self):
        """延迟解析/复用 Redis 客户端（优先 127.0.0.1 / REDIS_HOST 毫秒级探测）"""
        if self._client is not None:
            return self._client
        try:
            import redis
        except ImportError:
            self._redis_available = False
            return None

        urls_to_try = []
        if os.environ.get("REDIS_URL"):
            urls_to_try.append(os.environ.get("REDIS_URL"))
        host = os.environ.get("REDIS_HOST", "127.0.0.1")
        port = int(os.environ.get("REDIS_PORT", "6379"))
        db = int(os.environ.get("REDIS_DB", "0"))
        urls_to_try.append(f"redis://{host}:{port}/{db}")
        if host not in ("127.0.0.1", "localhost"):
            urls_to_try.append(f"redis://127.0.0.1:{port}/{db}")
        urls_to_try.append(f"redis://shopping-redis-1:{port}/{db}")

        for url in urls_to_try:
            try:
                candidate = redis.Redis.from_url(
                    url,
                    decode_responses=False,
                    socket_connect_timeout=0.5,
                    socket_timeout=1.5,
                )
                candidate.ping()
                self._client = candidate
                self._redis_available = True
                return candidate
            except Exception:
                continue

        self._redis_available = False
        return None

    def _ensure_index(self, client) -> bool:
        """确保 RediSearch 索引就绪"""
        if self._redisearch_ready:
            return True
        now = time.time()
        if now - self._last_index_check < 30.0:
            return self._redisearch_ready
        self._last_index_check = now

        try:
            names = client.execute_command("FT._LIST") or []
            names = [_decode(n) for n in names]
            if CACHE_INDEX_NAME in names:
                self._redisearch_ready = True
                return True

            # 创建 HNSW 向量索引
            client.execute_command(
                "FT.CREATE", CACHE_INDEX_NAME,
                "ON", "HASH",
                "PREFIX", "1", DOC_KEY_PREFIX,
                "SCHEMA",
                "role", "TAG",
                "model", "TAG",
                "query", "TEXT",
                "qvec", "VECTOR", "HNSW", "6",
                "TYPE", "FLOAT32",
                "DIM", str(self.dim),
                "DISTANCE_METRIC", "COSINE",
                "created_at", "NUMERIC",
            )
            self._redisearch_ready = True
            logger.info("RediSearch 语义缓存索引创建成功: %s", CACHE_INDEX_NAME)
            return True
        except Exception as exc:
            err = str(exc)
            if "Index already exists" in err:
                self._redisearch_ready = True
                return True
            logger.warning("RediSearch 索引探针/创建失败，回退内存 VSS: %s", exc)
            self._redisearch_ready = False
            return False

    def _get_embedder(self):
        if self._embedder is not None:
            return self._embedder
        try:
            from .redis_kb import OllamaEmbedder
            self._embedder = OllamaEmbedder()
            return self._embedder
        except Exception:
            return None

    @staticmethod
    def _exact_hash(role: str, query: str) -> str:
        norm = _normalize_query(query)
        r = (role or "buyer").strip().lower()
        raw = f"{r}:{norm}".encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    @staticmethod
    def is_cacheable(query: str, content: str = "", tools_mode: bool = False, tool_trace: list = None) -> bool:
        """安全围栏判定：仅允许缓存确定性无副作用的常见问答与咨询"""
        if not query or len(query.strip()) < 2:
            return False
        # 工具执行包含写入/创建草稿者绝对不可缓存
        if tools_mode:
            if tool_trace:
                for trace in tool_trace:
                    t_name = trace.get("tool") if isinstance(trace, dict) else ""
                    if t_name in ("create_order_draft", "pay_order", "cancel_order"):
                        return False
            # 若使用了工具且包含动态用户数据，谨慎起见不予长效缓存
            return False

        # 正则扫描敏感动态实体
        for pat in SENSITIVE_QUERY_PATTERNS:
            if pat.search(query):
                return False
        if content:
            for pat in SENSITIVE_CONTENT_PATTERNS:
                if pat.search(content):
                    return False

        return True

    # ----------------- 缓存读取主流程 -----------------

    def get(self, query: str, role: str = "buyer", model: str = "") -> Optional[CacheHit]:
        """两级语义缓存检索 (Tier 1 Exact Hash -> Tier 2 VSS Cosine)"""
        if not query or not query.strip():
            return None

        t0 = time.time()
        role_clean = (role or "buyer").strip().lower()
        exact_h = self._exact_hash(role_clean, query)
        exact_key = f"{EXACT_KEY_PREFIX}{exact_h}"

        # ----------------- Tier 1: Exact Match -----------------
        client = self._get_redis()
        if client is not None:
            try:
                raw_exact = client.get(exact_key)
                if raw_exact:
                    data = json.loads(_decode(raw_exact))
                    lat = round((time.time() - t0) * 1000, 2)
                    return CacheHit(
                        hit_type="exact",
                        similarity=1.0,
                        content=data.get("content", ""),
                        reasoning_content=data.get("reasoning_content", ""),
                        scene=data.get("scene", ""),
                        docs=data.get("docs", []),
                        cache_id=exact_h[:16],
                        latency_ms=lat,
                    )
            except Exception as exc:
                logger.debug("Redis Tier 1 Exact 检查失败: %s", exc)

        # 内存 Tier 1 兜底
        with self._lock:
            if exact_h in self._mem_exact:
                entry = self._mem_exact[exact_h]
                lat = round((time.time() - t0) * 1000, 2)
                return CacheHit(
                    hit_type="exact",
                    similarity=1.0,
                    content=entry.get("content", ""),
                    reasoning_content=entry.get("reasoning_content", ""),
                    scene=entry.get("scene", ""),
                    docs=entry.get("docs", []),
                    cache_id=exact_h[:16],
                    latency_ms=lat,
                )

        # ----------------- Tier 2: Vector Similarity Search (VSS) -----------------
        if len(query.strip()) < 3:
            return None

        embedder = self._get_embedder()
        if embedder is None:
            return None

        vector = None
        try:
            vector = embedder.embed_one(query)
        except Exception as exc:
            logger.debug("Query Embedding 计算失败: %s", exc)

        if not vector or len(vector) != self.dim:
            return None

        # L2 归一化
        norm = math.sqrt(sum(v * v for v in vector))
        if not norm:
            return None
        norm_vec = [v / norm for v in vector]
        vec_bytes = struct.pack(f"<{len(norm_vec)}f", *norm_vec)

        # 尝试 RediSearch 检索
        if client is not None and self._ensure_index(client):
            try:
                safe_role = re.sub(r"[^A-Za-z0-9_\-]", "", role_clean)
                search_q = f"(@role:{{{safe_role}}})=>[KNN 1 @qvec $vec AS distance]"
                raw_resp = client.execute_command(
                    "FT.SEARCH", CACHE_INDEX_NAME,
                    search_q,
                    "PARAMS", "2", "vec", vec_bytes,
                    "SORTBY", "distance", "ASC",
                    "LIMIT", "0", "1",
                    "DIALECT", "2"
                )
                hit = self._parse_redisearch_hit(raw_resp)
                if hit:
                    distance = hit.get("distance", 1.0)
                    if distance <= self.distance_threshold:
                        similarity = round(1.0 - distance, 4)
                        lat = round((time.time() - t0) * 1000, 2)
                        return CacheHit(
                            hit_type="semantic",
                            similarity=similarity,
                            content=hit.get("content", ""),
                            reasoning_content=hit.get("reasoning_content", ""),
                            scene=hit.get("scene", ""),
                            docs=hit.get("docs", []),
                            cache_id=hit.get("key", "").split(":")[-1],
                            latency_ms=lat,
                        )
            except Exception as exc:
                logger.debug("RediSearch VSS 执行异常: %s", exc)

        # 内存向量余弦匹配兜底
        with self._lock:
            best_sim = 0.0
            best_entry = None
            for item in self._mem_vectors:
                if item.get("role") != role_clean:
                    continue
                v_stored = item.get("vec")
                if not v_stored or len(v_stored) != len(norm_vec):
                    continue
                # 计算余弦相似度（已知两者均已 L2 归一化，即点积）
                sim = sum(a * b for a, b in zip(norm_vec, v_stored))
                if sim > best_sim:
                    best_sim = sim
                    best_entry = item

            if best_entry and best_sim >= self.similarity_threshold:
                lat = round((time.time() - t0) * 1000, 2)
                return CacheHit(
                    hit_type="semantic",
                    similarity=round(best_sim, 4),
                    content=best_entry.get("content", ""),
                    reasoning_content=best_entry.get("reasoning_content", ""),
                    scene=best_entry.get("scene", ""),
                    docs=best_entry.get("docs", []),
                    cache_id=best_entry.get("id", "mem")[:16],
                    latency_ms=lat,
                )

        return None

    # ----------------- 缓存写入主流程 -----------------

    def set(
        self,
        query: str,
        content: str,
        reasoning_content: str = "",
        role: str = "buyer",
        model: str = "",
        scene: str = "",
        docs: Optional[List[Dict[str, Any]]] = None,
        ttl: Optional[int] = None,
    ) -> bool:
        """同步写回缓存（Tier 1 Exact + Tier 2 VSS）"""
        if not self.is_cacheable(query, content):
            return False

        set_start = time.time()
        effective_ttl = ttl or self.ttl
        role_clean = (role or "buyer").strip().lower()
        if self._is_invalidated_since(role_clean, set_start):
            return False
        exact_h = self._exact_hash(role_clean, query)
        exact_key = f"{EXACT_KEY_PREFIX}{exact_h}"
        doc_id = uuid.uuid4().hex[:16]
        doc_key = f"{DOC_KEY_PREFIX}{doc_id}"

        payload = {
            "query": query,
            "content": content,
            "reasoning_content": reasoning_content,
            "role": role_clean,
            "model": model,
            "scene": scene,
            "docs": docs or [],
            "created_at": int(time.time()),
        }

        # 1. 写入 Exact Match
        client = self._get_redis()
        if client is not None:
            try:
                client.set(exact_key, json.dumps(payload, ensure_ascii=False), ex=effective_ttl)
            except Exception as exc:
                logger.debug("写入 Redis Tier 1 失败: %s", exc)

        with self._lock:
            self._mem_exact[exact_h] = payload
            if len(self._mem_exact) > self._max_mem_entries:
                # 简单驱逐最早一项
                oldest = next(iter(self._mem_exact))
                del self._mem_exact[oldest]

        # 2. 写入 Tier 2 VSS
        embedder = self._get_embedder()
        vector = None
        if embedder:
            try:
                vector = embedder.embed_one(query)
            except Exception:
                pass

        if self._is_invalidated_since(role_clean, set_start):
            return False

        if vector and len(vector) == self.dim:
            norm = math.sqrt(sum(v * v for v in vector))
            if norm:
                norm_vec = [v / norm for v in vector]
                vec_bytes = struct.pack(f"<{len(norm_vec)}f", *norm_vec)

                if client is not None:
                    try:
                        self._ensure_index(client)
                        mapping = {
                            "role": role_clean,
                            "model": model or "qwen2.5-7b",
                            "query": query,
                            "content": content,
                            "reasoning_content": reasoning_content,
                            "scene": scene,
                            "docs": json.dumps(docs or [], ensure_ascii=False),
                            "qvec": vec_bytes,
                            "created_at": payload["created_at"],
                        }
                        client.hset(doc_key, mapping=mapping)
                        client.expire(doc_key, effective_ttl)
                    except Exception as exc:
                        logger.debug("写入 Redis Tier 2 VSS 失败: %s", exc)

                with self._lock:
                    mem_v = dict(payload)
                    mem_v["id"] = doc_id
                    mem_v["vec"] = norm_vec
                    self._mem_vectors.append(mem_v)
                    if len(self._mem_vectors) > self._max_mem_entries:
                        self._mem_vectors.pop(0)

        return True

    def set_async(self, query: str, content: str, reasoning_content: str = "",
                  role: str = "buyer", model: str = "", scene: str = "",
                  docs: Optional[List[Dict[str, Any]]] = None, ttl: Optional[int] = None) -> None:
        """异步非阻塞写回缓存，绝不阻塞用户端首包交互"""
        t = threading.Thread(
            target=self.set,
            args=(query, content, reasoning_content, role, model, scene, docs, ttl),
            daemon=True,
            name="semantic-cache-writer",
        )
        t.start()

    # ----------------- 缓存主动失效 -----------------

    def invalidate(self, role: Optional[str] = None, pattern: Optional[str] = None) -> int:
        """主动失效指定的语义缓存项"""
        deleted = 0
        client = self._get_redis()
        if client is not None:
            try:
                exact_pat = f"{EXACT_KEY_PREFIX}*"
                doc_pat = f"{DOC_KEY_PREFIX}*"
                for pat in (exact_pat, doc_pat):
                    for k in client.scan_iter(match=pat, count=200):
                        client.delete(k)
                        deleted += 1
            except Exception as exc:
                logger.warning("Redis 清理缓存键异常: %s", exc)

        now = time.time()
        with self._lock:
            if role:
                r = role.strip().lower()
                self._last_invalidate_time[r] = now
                deleted += len(self._mem_exact)
                self._mem_exact = {k: v for k, v in self._mem_exact.items() if v.get("role") != r}
                self._mem_vectors = [v for v in self._mem_vectors if v.get("role") != r]
            else:
                self._last_invalidate_time[""] = now
                deleted += len(self._mem_exact) + len(self._mem_vectors)
                self._mem_exact.clear()
                self._mem_vectors.clear()

        return deleted

    # ----------------- 辅助解析 -----------------

    @staticmethod
    def _parse_redisearch_hit(raw) -> Optional[Dict[str, Any]]:
        """统一解析 RediSearch RESP2 / RESP3 返回结构"""
        if not raw:
            return None
        try:
            # RESP2: [total_count, key, [field, val, ...]]
            if isinstance(raw, list) and len(raw) >= 3:
                key = _decode(raw[1])
                fields_raw = raw[2]
                fields = {}
                if isinstance(fields_raw, list):
                    for i in range(0, len(fields_raw) - 1, 2):
                        fields[_decode(fields_raw[i])] = fields_raw[i + 1]
                distance = float(_decode(fields.get("distance", "1.0")))
                docs_val = fields.get("docs")
                docs_list = []
                if docs_val:
                    try:
                        docs_list = json.loads(_decode(docs_val))
                    except Exception:
                        pass
                return {
                    "key": key,
                    "distance": distance,
                    "content": _decode(fields.get("content")),
                    "reasoning_content": _decode(fields.get("reasoning_content")),
                    "scene": _decode(fields.get("scene")),
                    "docs": docs_list,
                }
            # RESP3 or dict
            if isinstance(raw, dict):
                results = raw.get(b"results") or raw.get("results") or []
                if results and isinstance(results[0], dict):
                    first = results[0]
                    key = _decode(first.get(b"id") or first.get("id"))
                    attrs = first.get(b"extra_attributes") or first.get("extra_attributes") or {}
                    dist = float(_decode(attrs.get(b"distance") or attrs.get("distance") or 1.0))
                    return {"key": key, "distance": dist}
        except Exception as exc:
            logger.debug("解析 RediSearch hit 异常: %s", exc)
        return None

    # ----------------- SSE 流式模拟重放 -----------------

    @staticmethod
    def stream_cached_response(
        hit: CacheHit,
        model: str = "qwen2.5-7b",
        chunk_interval: float = 0.012,
    ) -> Generator[str, None, None]:
        """构造平滑流式 SSE 报文（打字机效果模拟），完全契合 OpenAI 流式契约"""
        chunk_id = f"chatcmpl-{uuid.uuid4().hex[:24]}"
        created_ts = int(time.time())

        # 1. Head Chunk
        head = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "created": created_ts,
            "model": model,
            "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}],
        }
        yield f"data: {json.dumps(head, ensure_ascii=False)}\n\n"

        # 2. Reasoning Chunk (如有)
        if hit.reasoning_content:
            reason_chunk = {
                "id": chunk_id,
                "object": "chat.completion.chunk",
                "created": created_ts,
                "model": model,
                "choices": [{"index": 0, "delta": {"reasoning_content": hit.reasoning_content}, "finish_reason": None}],
            }
            yield f"data: {json.dumps(reason_chunk, ensure_ascii=False)}\n\n"

        # 3. Content Chunks (按标点或词片切分平滑打字)
        parts = [p for p in re.split(r"(?<=[。！？\n,，])", hit.content) if p]
        if not parts and hit.content:
            parts = [hit.content]

        for part in parts:
            delta = {
                "id": chunk_id,
                "object": "chat.completion.chunk",
                "created": created_ts,
                "model": model,
                "choices": [{"index": 0, "delta": {"content": part}, "finish_reason": None}],
            }
            yield f"data: {json.dumps(delta, ensure_ascii=False)}\n\n"
            if chunk_interval > 0:
                time.sleep(chunk_interval)

        # 4. Tail Chunk (标记 cached 溯源与时延)
        tail = {
            "id": chunk_id,
            "object": "chat.completion.chunk",
            "created": created_ts,
            "model": model,
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            "lanlink": {
                "cached": True,
                "cache_type": hit.hit_type,
                "cache_similarity": hit.similarity,
                "cache_id": hit.cache_id,
                "cache_latency_ms": hit.latency_ms,
                "scene": hit.scene,
            },
        }
        yield f"data: {json.dumps(tail, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

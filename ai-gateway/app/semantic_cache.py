# -*- coding: utf-8 -*-
"""网关前置两级语义缓存引擎（Two-Tier Semantic Cache）。

核心机制：
1. 两级匹配架构 (Two-Tier Hierarchy)：
   - Tier 1 精确哈希匹配 (Exact Hash Match)：
     基于 SHA-256(role + ":" + model + ":" + use_kb + ":" + normalized_query) 进行 O(1) 毫秒级直接查找，
     时延 < 0.5ms，零向量计算开销；
   - Tier 2 向量相似度近邻检索 (Vector Similarity Search - VSS)：
     利用 Ollama nomic-embed-text 生成 768 维 Query Embedding 并经 L2 归一化，
     在 RediSearch 索引 `aigateway:scache:idx` 执行余弦 KNN 检索。
     安全阈值：Cosine Distance <= 0.04 (等价余弦相似度 >= 0.96) 判定为语义等价命中。
2. 角色、模型与提示词形态隔离：
   - 严格在 TAG 字段过滤 `@role:{buyer|merchant|ops}`，杜绝跨角色权限与回答内容交叉污染；
   - 缓存键同时携带 `model` 与 `use_kb` 维度：`use_kb=true` 会用 RAG 模板重建 messages，
     与 `use_kb=false`（尊重调用方自带 messages）是完全不同的 prompt，两者绝不能互相命中。
3. 答案来源溯源 (Provenance)：
   - 缓存条目记录写入时的原始生成后端（`llamacpp:qwen2.5-7b` / `external:mimo` / …）；
   - 命中时按原始后端还原 `answer_generator`：本地来源回 `local:semantic_cache`，
     外部来源回 `external:semantic_cache`，绝不把外部生成内容冒充成本地结果。
4. 交易安全与隐私围栏 (Financial & Privacy Fence)：
   - 包含动态订单号（ORD-*）、支付草稿、手机号、密码验证码等隐私/动态写操作请求一律禁止入缓存。
5. 高性能响应与流式重放 (SSE Streaming Replay)：
   - 命中时直接构造 OpenAI Completions 报文，流式模式下以微延迟平滑重放 SSE chunk；
   - 注入 X-Lanlink-Cache: HIT-EXACT / HIT-SEMANTIC / MISS 与 X-Lanlink-Cache-Sim 响应头。
6. 异步写回与主动失效：
   - 异步后台写回，不占用用户首包延迟 (TTFT)；
   - 提供 /cache/invalidate 端点与支持 TTL（默认 7200 秒）。
7. 双轨容灾 (In-Memory Fallback)：
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
from urllib.parse import quote

logger = logging.getLogger("lanlink.semantic_cache")

EXACT_KEY_PREFIX = "aigateway:scache:exact:"
DOC_KEY_PREFIX = "aigateway:scache:doc:"
CACHE_INDEX_NAME = "aigateway:scache:idx"
DEFAULT_SIMILARITY_THRESHOLD = 0.96  # 余弦相似度 >= 0.96 (距离 <= 0.04)
DEFAULT_CACHE_TTL = int(os.environ.get("GATEWAY_CACHE_TTL", "7200"))  # 默认 2 小时
DEFAULT_CACHE_INVALIDATE_CHANNEL = os.environ.get(
    "GATEWAY_CACHE_INVALIDATE_CHANNEL", "aigateway:cache:invalidate"
)


def _env_embed_dim() -> Optional[int]:
    """读取 AI_EMBED_DIM；未配置/非法时返回 None（交由嵌入模型实际输出决定维度）。

    历史缺陷：`dim` 硬编码 768，而网关部署注入的 `AI_EMBED_MODEL=bge-m3` 输出 1024 维，
    `len(vector) != self.dim` 会静默丢弃全部 Tier-2 写入与命中，语义缓存退化成仅精确匹配。
    """
    raw = (os.environ.get("AI_EMBED_DIM") or "").strip()
    if not raw:
        return None
    try:
        value = int(raw)
    except (TypeError, ValueError):
        logger.warning("AI_EMBED_DIM 非法（%r），改由嵌入模型实际输出决定维度", raw)
        return None
    return value if value > 0 else None


# 维度探测用的探针文本：短、无业务语义，避免被敏感规则或业务逻辑干扰。
_DIM_PROBE_TEXT = "维度探针"

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


def _normalize_model(model: str) -> str:
    """模型名归一化：仅用于缓存键/TAG，调用方大小写或首尾空白不同不应造成重复条目。"""
    return (model or "").strip().lower()


def _tag_token(value: str) -> str:
    """把任意字符串压成 RediSearch TAG 安全 token（字母/数字/下划线）。

    `mimo-v2.6-flash` 这类带点号与连字符的模型名直接塞进 `@model:{...}` 过滤会因
    RediSearch 的转义/分词规则产生歧义，写入与查询两侧统一走同一函数即可稳定匹配。
    精确隔离仍由 Tier-1 的完整 SHA-256 键保证，此处只服务 Tier-2 的近似检索。
    """
    token = re.sub(r"[^A-Za-z0-9_]", "_", (value or "").strip().lower())
    return token or "none"


# 允许被认定为「本地生成」的后端标签前缀。其余（含空值）一律按外部来源处理，
# 宁可让 Java 侧 SupportAnswerPolicy 拒答，也不能把外部答案冒充成本地结果。
LOCAL_BACKEND_PREFIXES = ("local:", "llamacpp:", "ollama:", "mock")


def cache_answer_generator(backend: str) -> str:
    """按缓存条目的原始生成后端还原 `lanlink.answer_generator`。

    历史缺陷：命中时无条件写 `local:semantic_cache`，而缓存内容可能来自
    MiMo / DeepSeek 等外部模型，会骗过 Java 侧 `SupportAnswerPolicy.assertLocal`。
    现在：本地来源 → `local:semantic_cache`；外部/未知来源 → `external:semantic_cache`。
    """
    raw = (backend or "").strip().lower()
    if raw.startswith(LOCAL_BACKEND_PREFIXES):
        return "local:semantic_cache"
    return "external:semantic_cache"


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
    generator: str = ""             # 写入时的原始生成后端（llamacpp:qwen2.5-7b / external:mimo）

    @property
    def answer_generator(self) -> str:
        """命中响应应回填的 `lanlink.answer_generator`（按原始后端如实标注）。"""
        return cache_answer_generator(self.generator)


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
        dim: Optional[int] = None,
        similarity_threshold: float = DEFAULT_SIMILARITY_THRESHOLD,
        ttl_seconds: int = DEFAULT_CACHE_TTL,
        auto_start_pubsub: bool = False,
    ):
        self._client = redis_client
        self._embedder = embedder
        # 维度解析优先级：显式参数 > AI_EMBED_DIM > 嵌入模型首次实际输出。
        # 后两者都可能为 None：此时不预先定死维度，等首次 embed 时按真实输出校准，
        # 避免"配置写 768、模型吐 1024"这类静默失效。
        self.dim: Optional[int] = dim if dim else _env_embed_dim()
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

        # RediSearch 可用性探针状态
        self._redis_available = True
        self._redisearch_ready = False
        self._last_index_check = 0.0

        # Redis Pub/Sub 广播自动失效监听器
        # 只有进程级单例才订阅：每个 SemanticCache 实例各起一条重连线程会让线程数随实例
        # 线性增长（Redis 不可用时每秒重试一次）。测试/临时实例默认不订阅，需要时显式调用
        # start_pubsub_listener()。GATEWAY_ENABLE_PUBSUB_INVALIDATION=false 可整体关闭。
        self._pubsub_listener: Optional[CachePubSubListener] = None
        if (auto_start_pubsub
                and os.environ.get("GATEWAY_ENABLE_PUBSUB_INVALIDATION", "true").lower()
                in ("1", "true", "yes")):
            self.start_pubsub_listener()

    def _is_invalidated_since(self, role: str, t: float) -> bool:
        """检查自 t 刻起是否发生过主动失效指令。"""
        with self._lock:
            role_key = (role or "buyer").strip().lower()
            return (self._last_invalidate_time.get(role_key, 0.0) >= t or
                    self._last_invalidate_time.get("", 0.0) >= t)

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
        """延迟解析/复用 Redis 客户端（优先 REDIS_URL / 显式密码组串，再退化无密码探测）"""
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
        # 带密码组串优先：redis 服务以 requirepass 启动（compose REDIS_PASSWORD 同源），
        # 纯 noauth URL 对其 PING 必抛 AuthenticationError——历史缺陷是无密码候选在前、
        # 有密码链路缺失，导致凡是未走 startup 绑定（KB.redis_kb._connect 注入）的
        # _get_redis 调用方（如资讯管线判重）在带密码部署下永远拿不到客户端。
        password = (os.environ.get("REDIS_PASSWORD") or "").strip()
        if password:
            urls_to_try.append(
                f"redis://:{quote(password, safe='')}@{host}:{port}/{db}")
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
        """确保 RediSearch 索引就绪（维度与当前嵌入模型一致）。"""
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
                existing = self._index_dim(client)
                # 老索引没有 use_kb / generator 字段：`@use_kb:{...}` 过滤会直接报
                # "Unknown field"，Tier-2 会永久退化成 0 命中。必须按 schema 版本重建。
                schema_ok = self._index_has_fields(client, ("role", "model", "use_kb", "qvec"))
                if (existing is None or self.dim is None or existing == self.dim) and schema_ok:
                    self._redisearch_ready = True
                    return True
                if not schema_ok:
                    logger.warning(
                        "语义缓存索引缺少隔离字段（role/model/use_kb/qvec 不全），删除后重建 %s",
                        CACHE_INDEX_NAME)
                else:
                    # 已有索引维度与当前嵌入模型不一致：旧向量无法参与 KNN，
                    # 直接删索引重建，否则每次检索都报 "Invalid vector blob size"。
                    logger.warning(
                        "语义缓存索引维度不一致（索引 %s / 当前 %s），删除后重建 %s",
                        existing, self.dim, CACHE_INDEX_NAME)
                client.execute_command("FT.DROPINDEX", CACHE_INDEX_NAME, "DD")

            if self.dim is None:
                # 维度还没由嵌入模型校准：此时建索引只能靠猜，一旦猜错就要整库重建。
                # 先退回内存 VSS，等首次 embed 锁定真实维度后再建。
                logger.info("语义缓存维度尚未确定，暂不创建 RediSearch 索引（先用内存 VSS）")
                self._redisearch_ready = False
                return False

            # 创建 HNSW 向量索引
            client.execute_command(
                "FT.CREATE", CACHE_INDEX_NAME,
                "ON", "HASH",
                "PREFIX", "1", DOC_KEY_PREFIX,
                "SCHEMA",
                "role", "TAG",
                "model", "TAG",
                "use_kb", "TAG",
                "query", "TEXT",
                "generator", "TEXT",
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

    @staticmethod
    def _index_dim(client) -> Optional[int]:
        """从 FT.INFO 读出索引已声明的向量维度；读不到返回 None（视为未知，不重建）。"""
        try:
            info = client.execute_command("FT.INFO", CACHE_INDEX_NAME)
        except Exception:
            return None
        try:
            attributes = SemanticCache._index_attributes(info)
            return SemanticCache._dim_from_attributes(attributes)
        except Exception:
            return None

    @staticmethod
    def _index_attributes(info):
        """从 FT.INFO 返回体里取出 attributes 节点（兼容 RESP2 扁平列表与 RESP3 dict）。"""
        if isinstance(info, dict):
            return info.get(b"attributes") or info.get("attributes")
        flat = list(info or [])
        for i, item in enumerate(flat[:-1]):
            if _decode(item).lower() == "attributes":
                return flat[i + 1]
        return None

    @staticmethod
    def _index_has_fields(client, required) -> bool:
        """索引 schema 是否已包含全部必需字段；读不到 FT.INFO 时保守返回 True（不误删索引）。"""
        try:
            info = client.execute_command("FT.INFO", CACHE_INDEX_NAME)
        except Exception:
            return True
        attributes = SemanticCache._index_attributes(info)
        if not attributes:
            return True

        # RESP2 里每个字段是扁平键值对 `[identifier, role, type, TAG, ...]`，
        # RESP3 里是 dict `{identifier: role, type: TAG, ...}`；统一摊平成标量序列后
        # 取每个 "identifier" 的后继即可，避免两套解析逻辑分叉。
        flat: list = []

        def flatten(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    flat.append(key)
                    flatten(value)
            elif isinstance(node, (list, tuple)):
                for item in node:
                    if isinstance(item, (dict, list, tuple)):
                        flatten(item)
                    else:
                        flat.append(item)
            else:
                flat.append(node)

        flatten(attributes)
        names = set()
        for i, token in enumerate(flat[:-1]):
            if _decode(token).strip().lower() == "identifier":
                names.add(_decode(flat[i + 1]).strip().lower())
        return all(name.lower() in names for name in required)

    @staticmethod
    def _dim_from_attributes(attributes) -> Optional[int]:
        """在 attributes 结构里找 DIM：兼容 dict / RESP2 扁平列表 / 元素为 dict 的列表。"""
        if not attributes:
            return None

        def flatten(node, out):
            if isinstance(node, dict):
                for key, value in node.items():
                    out.extend([key, value])
                    flatten(value, out)
            elif isinstance(node, (list, tuple)):
                for item in node:
                    if isinstance(item, (dict, list, tuple)):
                        flatten(item, out)
                    else:
                        out.append(item)
            else:
                out.append(node)

        flat: list = []
        flatten(attributes, flat)
        for i, item in enumerate(flat[:-1]):
            if _decode(item).upper() == "DIM":
                try:
                    return int(float(_decode(flat[i + 1])))
                except (TypeError, ValueError):
                    return None
        return None

    def _get_embedder(self):
        if self._embedder is not None:
            return self._embedder
        try:
            from .redis_kb import OllamaEmbedder
            self._embedder = OllamaEmbedder()
            return self._embedder
        except Exception:
            return None

    def _resolve_dim(self, vector) -> Optional[int]:
        """用嵌入向量的真实长度校准维度，返回可用的维度（不可用时 None）。

        首次拿到向量时若维度未定，就地锁定；若已定但与实际不符，说明配置与模型不一致——
        以实际输出为准并告警，同时让 RediSearch 索引按新维度重建，避免 Tier-2 永久失效。
        """
        if not vector:
            return None
        actual = len(vector)
        if actual <= 0:
            return None
        with self._lock:
            if self.dim == actual:
                return actual
            previous, self.dim = self.dim, actual
            # 维度变了，旧索引的向量字段宽度不匹配，必须重建
            self._redisearch_ready = False
            self._last_index_check = 0.0
        if previous is None:
            logger.info("语义缓存维度已按嵌入模型实际输出锁定为 %d", actual)
        else:
            logger.warning(
                "语义缓存维度与嵌入模型不一致（配置 %s / 实际 %d），已切换为实际维度并重建索引",
                previous, actual)
        return actual

    @staticmethod
    def _exact_hash(role: str, query: str, model: str = "", use_kb: bool = True) -> str:
        """Tier-1 精确键：role + model + use_kb + 归一化问题。

        历史缺陷：键里只有 `role:query`，`model` 与 `use_kb` 都没有参与哈希，
        导致「qwen2.5-7b 的答案被 mimo 请求命中」「use_kb=true 的 RAG 答案被
        use_kb=false 命中」两类串味，且 Tier-2 的 `@role` 过滤同样漏掉了这两个维度。
        """
        norm = _normalize_query(query)
        r = (role or "buyer").strip().lower()
        m = _normalize_model(model)
        kb = "1" if use_kb else "0"
        raw = f"{r}:{m}:{kb}:{norm}".encode("utf-8")
        return hashlib.sha256(raw).hexdigest()

    @staticmethod
    def is_cacheable(query: str, content: str = "", tools_mode: bool = False,
                     tool_trace: list = None, use_kb: bool = True) -> bool:
        """安全围栏判定：仅允许缓存确定性无副作用的常见问答与咨询。

        `use_kb` 参与判定与键维度：RAG 模式会用知识库检索结果重建 messages，
        与非 RAG 模式是完全不同的 prompt，缓存不可互通。
        """
        if not query or len(query.strip()) < 2:
            return False
        # 提示词形态必须显式给出，避免调用方漏传后静默落回某个默认分桶造成串味。
        if not isinstance(use_kb, bool):
            raise TypeError("use_kb 必须是 bool")
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

    def get(self, query: str, role: str = "buyer", model: str = "",
            use_kb: bool = True) -> Optional[CacheHit]:
        """两级语义缓存检索 (Tier 1 Exact Hash -> Tier 2 VSS Cosine)。

        `model` 与 `use_kb` 都是隔离维度：不同模型、不同 prompt 形态互不命中。
        """
        if not query or not query.strip():
            return None

        t0 = time.time()
        role_clean = (role or "buyer").strip().lower()
        model_clean = _normalize_model(model)
        use_kb_eff = bool(use_kb)
        exact_h = self._exact_hash(role_clean, query, model_clean, use_kb_eff)
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
                        generator=data.get("generator", ""),
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
                    generator=entry.get("generator", ""),
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

        if not vector or self._resolve_dim(vector) is None:
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
                safe_model = _tag_token(model_clean)
                safe_kb = "1" if use_kb_eff else "0"
                search_q = (
                    f"(@role:{{{safe_role}}} @model:{{{safe_model}}} @use_kb:{{{safe_kb}}})"
                    "=>[KNN 1 @qvec $vec AS distance]"
                )
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
                            generator=hit.get("generator", ""),
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
                # Tier-2 内存兜底同样按 model + use_kb 隔离，否则近似命中会绕过 Tier-1 的隔离维度。
                if _normalize_model(item.get("model", "")) != model_clean:
                    continue
                if bool(item.get("use_kb", True)) is not use_kb_eff:
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
                    generator=best_entry.get("generator", ""),
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
        use_kb: bool = True,
        generator: str = "",
    ) -> bool:
        """同步写回缓存（Tier 1 Exact + Tier 2 VSS）。

        `generator` 记录本次回答的原始生成后端（如 `llamacpp:qwen2.5-7b`、
        `external:mimo`），命中时据此还原 `answer_generator`，不再无条件冒充 local。
        """
        if not self.is_cacheable(query, content, use_kb=use_kb):
            return False

        set_start = time.time()
        effective_ttl = ttl or self.ttl
        role_clean = (role or "buyer").strip().lower()
        model_clean = _normalize_model(model)
        use_kb_eff = bool(use_kb)
        if self._is_invalidated_since(role_clean, set_start):
            return False
        exact_h = self._exact_hash(role_clean, query, model_clean, use_kb_eff)
        exact_key = f"{EXACT_KEY_PREFIX}{exact_h}"
        doc_id = uuid.uuid4().hex[:16]
        doc_key = f"{DOC_KEY_PREFIX}{doc_id}"

        payload = {
            "query": query,
            "content": content,
            "reasoning_content": reasoning_content,
            "role": role_clean,
            "model": model_clean,
            "use_kb": use_kb_eff,
            "generator": generator,
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

        if vector and self._resolve_dim(vector) is not None:
            norm = math.sqrt(sum(v * v for v in vector))
            if norm:
                norm_vec = [v / norm for v in vector]
                vec_bytes = struct.pack(f"<{len(norm_vec)}f", *norm_vec)

                if client is not None:
                    try:
                        self._ensure_index(client)
                        mapping = {
                            "role": role_clean,
                            "model": _tag_token(model_clean),
                            "use_kb": "1" if use_kb_eff else "0",
                            "query": query,
                            "content": content,
                            "reasoning_content": reasoning_content,
                            "generator": generator,
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
                  docs: Optional[List[Dict[str, Any]]] = None, ttl: Optional[int] = None,
                  use_kb: bool = True, generator: str = "") -> None:
        """异步非阻塞写回缓存，绝不阻塞用户端首包交互"""
        t = threading.Thread(
            target=self.set,
            kwargs={
                "query": query,
                "content": content,
                "reasoning_content": reasoning_content,
                "role": role,
                "model": model,
                "scene": scene,
                "docs": docs,
                "ttl": ttl,
                "use_kb": use_kb,
                "generator": generator,
            },
            daemon=True,
            name="semantic-cache-writer",
        )
        t.start()

    # ----------------- 缓存主动失效 -----------------

    def invalidate(self, role: Optional[str] = None, pattern: Optional[str] = None) -> int:
        """主动失效指定的语义缓存项。

        role 非空时必须只删该角色的键：exact 键名是 `前缀 + sha256(role:query)`，
        角色不在键名里；doc 哈希把角色存成字段。因此不能按前缀整批删，必须逐键回读判定。
        否则角色级失效（含 Pub/Sub 广播）会连其它角色的缓存一起清空，破坏角色隔离。
        """
        deleted = 0
        client = self._get_redis()
        role_clean = (role or "").strip().lower()
        # MVCC 时间戳必须在**删除之前**落位。
        #
        # 历史缺陷：时间戳在 Redis 扫描删除之后才写入，而 `set()` 是「写 exact → 算
        # embedding（网络往返，数百毫秒）→ 再写 doc」。并发写回线程在时间戳落位前通过
        # `_is_invalidated_since` 检查，随后 hset 的 doc 键落在删除之后 → 失效后仍能
        # 语义命中旧答案（实测约 2/3 概率复现）。先置时间戳再删，过期写回即被拦下。
        now = time.time()
        with self._lock:
            self._last_invalidate_time[role_clean] = now

        if client is not None:
            try:
                exact_pat = f"{EXACT_KEY_PREFIX}*"
                doc_pat = f"{DOC_KEY_PREFIX}*"
                for pat in (exact_pat, doc_pat):
                    for k in client.scan_iter(match=pat, count=200):
                        if role_clean and not self._redis_key_belongs_to_role(client, k, role_clean):
                            continue
                        client.delete(k)
                        deleted += 1
            except Exception as exc:
                logger.warning("Redis 清理缓存键异常: %s", exc)

        with self._lock:
            if role_clean:
                r = role_clean
                deleted += len(self._mem_exact)
                self._mem_exact = {k: v for k, v in self._mem_exact.items() if v.get("role") != r}
                self._mem_vectors = [v for v in self._mem_vectors if v.get("role") != r]
            else:
                deleted += len(self._mem_exact) + len(self._mem_vectors)
                self._mem_exact.clear()
                self._mem_vectors.clear()

        return deleted

    @staticmethod
    def _redis_key_belongs_to_role(client, key, role: str) -> bool:
        """回读 Redis 键判断归属角色；读不到/解析失败时返回 False，宁可不删也不误删他人缓存。"""
        try:
            name = _decode(key)
            if name.startswith(EXACT_KEY_PREFIX):
                raw = client.get(key)
                if not raw:
                    return False
                data = json.loads(_decode(raw))
                return str(data.get("role") or "").strip().lower() == role
            if name.startswith(DOC_KEY_PREFIX):
                return _decode(client.hget(key, "role")).strip().lower() == role
        except Exception as exc:
            logger.debug("Redis 键角色判定失败，跳过 key=%s: %s", key, exc)
        return False

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
                    "generator": _decode(fields.get("generator")),
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
                    fields = {}
                    for fkey, fval in (attrs or {}).items():
                        fields[_decode(fkey)] = fval
                    dist = float(_decode(fields.get("distance", "1.0")))
                    docs_val = fields.get("docs")
                    docs_list = []
                    if docs_val:
                        try:
                            docs_list = json.loads(_decode(docs_val))
                        except Exception:
                            pass
                    # 历史缺陷：RESP3 分支只取 key/distance，content/reasoning_content 全部丢弃，
                    # 导致 Tier-2 命中回放空答案（Redis Stack 默认走 RESP3，线上必现）。
                    return {
                        "key": key,
                        "distance": dist,
                        "content": _decode(fields.get("content")),
                        "reasoning_content": _decode(fields.get("reasoning_content")),
                        "generator": _decode(fields.get("generator")),
                        "scene": _decode(fields.get("scene")),
                        "docs": docs_list,
                    }
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
                # 流式重放同样要如实标注来源：外部模型生成的内容不得带 local: 前缀。
                "answer_generator": hit.answer_generator,
            },
        }
        yield f"data: {json.dumps(tail, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

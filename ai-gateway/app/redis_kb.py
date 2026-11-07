# -*- coding: utf-8 -*-
"""Redis Stack 共享向量知识库 + 混合检索（Redis 向量优先，TF-IDF 兜底）。

数据契约（后端 Java 已写入同一 Redis）：
  - RediSearch 索引 KB_INDEX（默认 aikb-idx）：
      HASH 前缀 aikb:vblob:  —— 字段 vec = float32 LE 原始字节、source = TAG
  - 文档元数据 HASH aikb:doc:<id>：source / refId / title / text / vec(base64) / dim
  - 文档 id 集合 aikb:all（SCARD = 文档数），aikb:idx:dim = 向量维度

检索链路：
  Ollama /api/embed(AI_EMBED_MODEL=nomic-embed-text) 生成查询向量
  → FT.SEARCH "(@source:{x})=>[KNN k @vec $vec AS score]" PARAMS 4 vec <f32 LE> k <k> DIALECT 2
  → 解析 RESP2 / RESP3 → key 去掉 aikb:vblob: 前缀 → HGETALL aikb:doc:<id> 取标题/正文
  → 词面 hybrid 重排：score = 0.6*向量余弦 + 0.4*中文 char-bigram 覆盖率（Top-K）
  → 返回统一的 {id,title,category,content,source,score,refId} 结构

可用性策略：REDIS_URL（或 REDIS_URLS，逗号分隔）优先；未显式配置时用
REDIS_PASSWORD + REDIS_HOST/REDIS_PORT/REDIS_DB 组装（密码从不落仓库）。
Redis / Ollama / redis 包任一不可用都会快速降级（失败后冷却 60s 内不再重试），
由 HybridKB 回退本地 TF-IDF，绝不让 /kb/search 或 chat RAG 返回 500。
"""
import base64
import json
import logging
import math
import os
import re
import struct
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import quote, urlsplit, urlunsplit

from .retriever import KnowledgeBase, default_kb_path

logger = logging.getLogger(__name__)

VECTOR_PREFIX = "aikb:vblob:"
DOC_PREFIX = "aikb:doc:"
ALL_DOCS_KEY = "aikb:all"
DIM_KEY = "aikb:idx:dim"
MAX_EMBED_BATCH = 32

# source TAG -> 中文分类标签（前端/后端展示用）
SOURCE_CATEGORY_LABELS = {
    "kb": "平台帮助",
    "policy": "平台政策",
    "product": "商品",
    "news": "市场资讯",
    "membership": "会员与订阅",
    "subscription": "订阅",
    "category": "行业分类",
    "logistics": "物流",
    "intent": "意图",
    "manual": "人工录入",
    "learned": "学习知识",
    "tech-expert": "技术服务专家",
    "tech-org": "专业服务机构",
    "tech-service": "技术服务产品",
    "tech-demand": "技术需求",
    "tech-achievement": "技术成果",
    "tech-policy": "技术政策",
    "tech-post": "技术社区",
    "tech-manual": "技术服务机制",
}

_EMBED_DIM_FALLBACK = 256  # 本地 TF-IDF 哈希向量维度（仅兜底时使用）


# ------------------------------ 小工具 ------------------------------

def _decode(value) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return "" if value is None else str(value)


def _to_float(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(_decode(value))
    except (TypeError, ValueError):
        return None


def _to_int(value):
    number = _to_float(value)
    return int(number) if number is not None else None


def category_label(source) -> str:
    """source TAG -> 中文分类；未知 source 原样返回。"""
    key = (source or "").strip()
    return SOURCE_CATEGORY_LABELS.get(key.lower(), key)


def source_authority(source) -> float:
    """来源权威度权重：news=0.75、manual=0.95、其余（kb/policy/product/…）=1.0。"""
    return SOURCE_AUTHORITY_WEIGHTS.get(
        (source or "").strip().lower(), DEFAULT_SOURCE_AUTHORITY)


# --------------------- 词面 hybrid 重排（nomic 中文区分度差，需词面兜底） ---------------------
HYBRID_VECTOR_WEIGHT = 0.6        # 向量余弦权重；词面权重 = 1 - 该值
HYBRID_CANDIDATE_MULTIPLIER = 4   # 先取 top_k*4 个向量候选再做 hybrid 重排
HYBRID_MIN_CANDIDATES = 12
# 通用检索（不指定 source）时排除的来源：intent 是意图分类的用户问法示例语料，
# 不是可引用的平台事实；混入 RAG 会挤占上下文名额（意图分类自身按 source 过滤检索，不受影响）。
RAG_EXCLUDED_SOURCES = {"intent"}
# 无 source 过滤时加大候选倍数，抵消排除 intent 后的召回损失。
HYBRID_CANDIDATE_MULTIPLIER_UNFILTERED = 6

# 来源权威度权重（与后端 KnowledgeRagService 对齐）：市场资讯 news 降权，
# 避免无关资讯挤进 RAG 上下文；manual 人工录入略降权；其余 1.0。
SOURCE_AUTHORITY_WEIGHTS = {
    "news": 0.75,
    "manual": 0.95,
    # 技术服务源：库内主体多为演示/未签约数据，必须显式降权，
    # 否则未列出的 source 取 DEFAULT_SOURCE_AUTHORITY，会盖过真实政策文本。
    # 与后端 KnowledgeRagService.SOURCE_WEIGHTS 保持一致。
    "tech-expert": 0.70, "tech-org": 0.70, "tech-service": 0.70,
    "tech-demand": 0.70, "tech-achievement": 0.70,
    "tech-policy": 0.85, "tech-post": 0.60, "tech-manual": 0.90,
}
DEFAULT_SOURCE_AUTHORITY = 1.0

_CJK_RE = re.compile(r"[\u4e00-\u9fff]+")
_ASCII_RE = re.compile(r"[A-Za-z0-9]+(?:\.[0-9]+)?")

# --------------------- 工业品与复合型号模式识别 ---------------------
MODEL_SPEC_PATTERN = re.compile(
    r'(?i)\b(?:DN\d+|PN\d+|SC\d+|HRB\d+[A-Z]?|Q\d+[A-Z]?|PPR|PE\d*|PVC\d*|[A-Z]+\d+[-/]\w+|\d+(?:\.\d+)?(?:mm|cm|m|kg|t|mpa|kpa|kw|v|a|寸|分))\b|(?:\d{3}[A-Za-z]?(?:不锈钢|钢板|圆钢|无缝管))'
)

# 动态自适应 RRF 权重配置
DEFAULT_SPARSE_SPEC_WEIGHT = float(os.environ.get("KB_HYBRID_SPARSE_SPEC_WEIGHT", "0.80"))
DEFAULT_DENSE_SPEC_WEIGHT = float(os.environ.get("KB_HYBRID_DENSE_SPEC_WEIGHT", "0.20"))
DEFAULT_SPARSE_QA_WEIGHT = float(os.environ.get("KB_HYBRID_SPARSE_QA_WEIGHT", "0.25"))
DEFAULT_DENSE_QA_WEIGHT = float(os.environ.get("KB_HYBRID_DENSE_QA_WEIGHT", "0.75"))
RRF_K_CONSTANT = int(os.environ.get("KB_RRF_K", "60"))

# RediSearch 查询保留符号（必须安全清洗以防语法解析错误）
REDISEARCH_SYNTAX_CHARS = re.compile(r'["\'\(\)\[\]\{\}\~\^\!\:\;\@\+\=\\\<\>\?]')


def sanitize_sparse_query(query: str, source: str | None = None) -> str:
    """针对 RediSearch BM25 全文检索构建安全的稀疏查询表达式。

    核心特性：
    1. 连字符与分隔符安全化：RediSearch 将 - 视作逻辑否定运算符，
       将复合型号（如 DN100-PN16、Q611F/16P、304/2.0mm）中的分隔符替换为空格分词；
    2. 语法保留符号过滤：剔除引起 Syntax Error 的括号、引号、冒号等；
    3. 提取英数 Token 与中文片段，构造联合检索子句；
    4. 附加可选的 source TAG 过滤。
    """
    if not query or not query.strip():
        return ""

    cleaned = re.sub(r'[-/*_+]+', ' ', query)
    cleaned = REDISEARCH_SYNTAX_CHARS.sub(' ', cleaned)

    ascii_tokens = _ASCII_RE.findall(cleaned)

    cjk_tokens = []
    for run in _CJK_RE.findall(cleaned):
        run = run.strip()
        if len(run) == 1:
            cjk_tokens.append(run)
        elif len(run) <= 4:
            cjk_tokens.append(run)
        else:
            for i in range(0, len(run), 2):
                piece = run[i:i + 3]
                if piece:
                    cjk_tokens.append(piece)

    all_terms = []
    seen = set()
    for t in ascii_tokens + cjk_tokens:
        t_clean = t.strip()
        if t_clean and t_clean.lower() not in seen:
            seen.add(t_clean.lower())
            all_terms.append(t_clean)

    if not all_terms:
        return ""

    terms_expr = " | ".join(all_terms)
    body_expr = f"({terms_expr})"

    if source:
        safe_src = re.sub(r"[^A-Za-z0-9_\-]", "", source.strip())
        if safe_src:
            return f"(@source:{{{safe_src}}}) {body_expr}"

    return body_expr


def detect_adaptive_weights(query: str) -> tuple[float, float]:
    """根据查询文本类型动态决策 Dense 与 Sparse 的加权比例。

    - 包含工业型号、规格、材质等强特征时：提升 Sparse 权重至 0.80，降低 Dense 至 0.20；
    - 普通政策、业务问答时：保持 Dense 语义泛化主导（0.75），Sparse 辅助（0.25）。
    """
    if not query:
        return DEFAULT_DENSE_QA_WEIGHT, DEFAULT_SPARSE_QA_WEIGHT
    if MODEL_SPEC_PATTERN.search(query):
        return DEFAULT_DENSE_SPEC_WEIGHT, DEFAULT_SPARSE_SPEC_WEIGHT
    return DEFAULT_DENSE_QA_WEIGHT, DEFAULT_SPARSE_QA_WEIGHT


def lexical_terms(query: str) -> set:
    """查询词面特征：中文 char-bigram（单字中文保留单字）+ 英文/数字词。"""
    q = (query or "").lower()
    terms = set()
    for run in _CJK_RE.findall(q):
        if len(run) == 1:
            terms.add(run)
        else:
            terms.update(run[i:i + 2] for i in range(len(run) - 1))
    terms.update(_ASCII_RE.findall(q))
    return terms


def lexical_score(query: str, text: str) -> float:
    """query 词面在 text 中的覆盖率（0~1）：中文 bigram 命中数 / 词面总数。"""
    terms = lexical_terms(query)
    corpus = (text or "").lower()
    if not terms or not corpus:
        return 0.0
    hits = sum(1 for term in terms if term in corpus)
    return round(hits / len(terms), 4)


def hybrid_score(vector_score, lexical, alpha: float = HYBRID_VECTOR_WEIGHT) -> float:
    """hybrid = alpha * 向量余弦相似度 + (1-alpha) * 词面覆盖率。"""
    vector = max(0.0, min(1.0, _to_float(vector_score) or 0.0))
    lex = max(0.0, min(1.0, _to_float(lexical) or 0.0))
    a = max(0.0, min(1.0, _to_float(alpha) if _to_float(alpha) is not None else HYBRID_VECTOR_WEIGHT))
    return round(a * vector + (1.0 - a) * lex, 4)



def _mask_url(url: str) -> str:
    """隐藏连接串中的密码，供 /health 展示（永不返回明文密码）。"""
    try:
        parts = urlsplit(url)
        netloc = parts.netloc
        if "@" in netloc:
            userinfo, host = netloc.rsplit("@", 1)
            user = userinfo.split(":", 1)[0]
            netloc = f"{user}:***@{host}"
        return urlunsplit((parts.scheme, netloc, parts.path, "", ""))
    except Exception:  # pragma: no cover - 防御性
        return url.split("@")[-1] if "@" in url else url


class OllamaEmbedder:
    """Ollama /api/embed 客户端（批量），失败返回 None 由上层兜底。"""

    def __init__(self, base_url: str | None = None, model: str | None = None):
        explicit = (base_url or os.environ.get("OLLAMA_BASE_URL") or "").strip().rstrip("/")
        if explicit:
            self.candidate_urls = [explicit]
        else:
            self.candidate_urls = [
                "http://127.0.0.1:11434",
                "http://ollama:11434",
                "http://localhost:11434",
            ]
        self.base_url = self.candidate_urls[0]
        self.model = (model or os.environ.get("AI_EMBED_MODEL")
                      or "nomic-embed-text").strip()
        self.embed_path = (os.environ.get("AI_EMBED_PATH") or "/api/embed").strip()
        try:
            self.timeout = float(os.environ.get("AI_EMBED_TIMEOUT", "3"))
        except ValueError:
            self.timeout = 3.0

    @property
    def provider(self) -> str:
        return f"ollama:{self.model}"

    def embed(self, texts: list) -> list | None:
        """请求向量；返回 list[list[float]]（与 texts 等长），失败返回 None。"""
        if not texts:
            return []
        safe_inputs = [t if isinstance(t, str) and t.strip() else " " for t in texts]
        payload = {"model": self.model, "input": safe_inputs}
        data = None
        for base in self.candidate_urls:
            req = urllib.request.Request(
                base + self.embed_path,
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    data = json.loads(resp.read().decode("utf-8", "replace"))
                self.base_url = base
                break
            except Exception as exc:
                continue
        if data is None:
            return None
        embeddings = data.get("embeddings")
        if not embeddings:
            # 兼容旧版 /api/embeddings 单条返回 {"embedding": [...]}
            single = data.get("embedding")
            embeddings = [single] if single else None
        if not isinstance(embeddings, list) or len(embeddings) != len(safe_inputs):
            return None
        dims = {len(v) for v in embeddings if isinstance(v, (list, tuple))}
        if len(dims) != 1 or 0 in dims:
            return None
        try:
            return [[float(x) for x in v] for v in embeddings]
        except (TypeError, ValueError):
            return None

    def embed_one(self, text: str):
        result = self.embed([text])
        return result[0] if result else None


class RedisVectorKB:
    """Redis Stack（RediSearch KNN）向量知识库客户端。"""

    def __init__(self, index: str | None = None, embedder: OllamaEmbedder | None = None):
        self.index = (index or os.environ.get("KB_INDEX") or "aikb-idx").strip()
        self.embedder = embedder or OllamaEmbedder()
        self.embed_provider = self.embedder.provider
        try:
            self.cooldown_sec = float(os.environ.get("KB_REDIS_COOLDOWN", "60"))
        except ValueError:
            self.cooldown_sec = 60.0
        try:
            self.connect_timeout = float(os.environ.get("KB_REDIS_TIMEOUT", "2"))
        except ValueError:
            self.connect_timeout = 2.0
        try:
            self.socket_timeout = float(os.environ.get("KB_REDIS_SOCKET_TIMEOUT", "5"))
        except ValueError:
            self.socket_timeout = 5.0
        try:
            self.hybrid_alpha = float(os.environ.get(
                "KB_HYBRID_VECTOR_WEIGHT", str(HYBRID_VECTOR_WEIGHT)))
        except ValueError:
            self.hybrid_alpha = HYBRID_VECTOR_WEIGHT
        self.hybrid_alpha = max(0.0, min(1.0, self.hybrid_alpha))
        self._urls = self._candidate_urls()
        self._client = None
        self._available = False
        self._next_probe = 0.0
        self._error = None
        self._active_url = None
        self._dim = None
        self._sparse_ready = False
        self._lock = threading.Lock()

    # --------------------------- 连接与可用性 ---------------------------

    def _candidate_urls(self) -> list:
        """候选连接串：显式 REDIS_URL(S) 优先，否则用密码 + 主机名组装。"""
        explicit = []
        for var in ("REDIS_URL", "REDIS_URLS"):
            raw = (os.environ.get(var) or "").strip()
            for part in re.split(r"[,\s]+", raw):
                if part:
                    explicit.append(part)
        if explicit:
            return explicit
        password = os.environ.get("REDIS_PASSWORD") or ""
        if not password:
            return []
        host = (os.environ.get("REDIS_HOST") or "redis").strip()
        port = (os.environ.get("REDIS_PORT") or "6379").strip()
        db = (os.environ.get("REDIS_DB") or "0").strip()
        auth = ":" + quote(password, safe="")
        urls = [f"redis://{auth}@{host}:{port}/{db}"]
        # 兼容两种部署：compose 网络内直连 redis 服务 / 宿主机端口映射
        for alt in ("shopping-redis-1", "host.docker.internal"):
            if alt != host:
                urls.append(f"redis://{auth}@{alt}:{port}/{db}")
        return urls

    def _connect(self):
        client = self._client
        if client is not None:
            return client
        if not self._urls:
            raise RuntimeError("REDIS_URL / REDIS_PASSWORD 未配置")
        with self._lock:
            if self._client is not None:
                return self._client
            try:
                import redis  # 延迟导入：缺 redis 包时优雅降级
            except Exception as exc:  # pragma: no cover - 依赖缺失
                raise RuntimeError(f"redis 包不可用: {exc}") from exc
            last_exc = None
            for url in self._urls:
                try:
                    candidate = redis.Redis.from_url(
                        url,
                        decode_responses=False,
                        socket_connect_timeout=self.connect_timeout,
                        socket_timeout=self.socket_timeout,
                        health_check_interval=30,
                    )
                    candidate.ping()
                except Exception as exc:
                    last_exc = exc
                    continue
                self._client = candidate
                self._active_url = _mask_url(url)
                return candidate
            raise RuntimeError(f"Redis 连接失败: {last_exc}")

    def _mark_down(self, exc) -> None:
        self._available = False
        self._error = f"{type(exc).__name__}: {exc}"[:300]
        self._next_probe = time.time() + self.cooldown_sec
        client, self._client = self._client, None
        if client is not None:
            try:
                client.close()
            except Exception:  # pragma: no cover - 防御性
                pass

    def available(self) -> bool:
        """ping + FT._LIST 含索引；失败后冷却 cooldown_sec 内不重试。"""
        if self._available:
            return True
        if not self._urls:
            return False
        now = time.time()
        if now < self._next_probe:
            return False
        try:
            client = self._connect()
            names = client.execute_command("FT._LIST") or []
            names = [_decode(name) for name in names]
            if self.index not in names:
                raise RuntimeError(f"索引 {self.index} 不存在")
            self._ensure_sparse_schema(client)
        except Exception as exc:
            self._mark_down(exc)
            logger.info("Redis 向量知识库不可用，回退 TF-IDF: %s", exc)
            return False
        self._available = True
        self._error = None
        return True

    def _ensure_sparse_schema(self, client) -> None:
        """检查并确保 RediSearch 索引包含 title 和 text 全文倒排字段（自愈补全）。"""
        if self._sparse_ready:
            return
        try:
            try:
                client.execute_command("FT.ALTER", self.index, "SCHEMA", "ADD", "title", "TEXT", "WEIGHT", "2.0")
                logger.info("已通过 FT.ALTER 为索引 %s 补充 title TEXT 倒排字段", self.index)
            except Exception as e:
                logger.debug("FT.ALTER title TEXT 响应: %s", e)
            try:
                client.execute_command("FT.ALTER", self.index, "SCHEMA", "ADD", "text", "TEXT", "WEIGHT", "1.0")
                logger.info("已通过 FT.ALTER 为索引 %s 补充 text TEXT 倒排字段", self.index)
            except Exception as e:
                logger.debug("FT.ALTER text TEXT 响应: %s", e)
            self._sparse_ready = True
        except Exception as exc:
            logger.warning("检查/补全 RediSearch 稀疏倒排索引异常: %s", exc)

    def reset(self) -> None:
        """清空连接与冷却状态（/kb/reload 用）。"""
        client, self._client = self._client, None
        self._available = False
        self._next_probe = 0.0
        self._error = None
        self._dim = None
        self._sparse_ready = False
        if client is not None:
            try:
                client.close()
            except Exception:  # pragma: no cover - 防御性
                pass

    # ------------------------------ 向量化 ------------------------------

    def _fetch_dim(self, client) -> int | None:
        if self._dim:
            return self._dim
        try:
            raw = client.execute_command("GET", DIM_KEY)
            if raw:
                self._dim = _to_int(raw)
                if self._dim:
                    return self._dim
        except Exception:  # pragma: no cover - 防御性
            pass
        try:
            _cursor, keys = client.execute_command(
                "SCAN", 0, "MATCH", DOC_PREFIX + "*", "COUNT", 10)
            for key in keys or []:
                raw = client.execute_command("HGET", _decode(key), "vec")
                if raw:
                    try:
                        self._dim = len(base64.b64decode(raw)) // 4
                    except Exception:
                        self._dim = len(raw) // 4 if isinstance(raw, bytes) else None
                    if self._dim:
                        return self._dim
        except Exception:  # pragma: no cover - 防御性
            pass
        return self._dim

    def embed_query(self, text: str):
        """查询向量：Ollama /api/embed + L2 归一化；失败或无结果返回 None。"""
        if not text or not text.strip():
            return None
        vector = self.embedder.embed_one(text)
        if not vector:
            return None
        norm = math.sqrt(sum(v * v for v in vector))
        if not norm:
            return None
        vector = [v / norm for v in vector]
        try:
            client = self._client or self._connect()
            expected = self._fetch_dim(client)
        except Exception:
            expected = None
        if expected and len(vector) != expected:
            logger.warning("查询向量维度 %s 与索引维度 %s 不一致，跳过 Redis 检索",
                           len(vector), expected)
            return None
        return vector

    @staticmethod
    def _vector_bytes(vector: list) -> bytes:
        return struct.pack(f"<{len(vector)}f", *vector)

    # ------------------------------ 查询 ------------------------------

    @staticmethod
    def build_search_query(k: int, source: str | None = None) -> str:
        """构造 DIALECT 2 的 KNN 查询串（source 可选过滤）。"""
        k = int(k)
        tag = (source or "").strip()
        if not tag:
            return f"*=>[KNN {k} @vec $vec AS score]"
        safe = re.sub(r"[^A-Za-z0-9_\-]", "", tag)
        if not safe or safe != tag:
            raise ValueError(f"非法 source: {source!r}")
        return f"(@source:{{{safe}}})=>[KNN {k} @vec $vec AS score]"

    @staticmethod
    def _normalize_fields(raw_fields) -> dict:
        """字段容器 -> {str: str}：兼容 RESP2 扁平列表 / RESP3 dict（bytes key）。

        跳过向量原始字节字段 `vec`（体积大且检索结果用不到，章节元数据另行 HGETALL）。
        """
        fields = {}
        if isinstance(raw_fields, dict):
            items = list(raw_fields.items())
        elif isinstance(raw_fields, (list, tuple)):
            items = [(raw_fields[i], raw_fields[i + 1])
                     for i in range(0, len(raw_fields) - 1, 2)]
        else:
            return fields
        for name, value in items:
            key = _decode(name)
            if key == "vec":
                continue
            fields[key] = _decode(value)
        return fields

    @staticmethod
    def parse_search_response(raw) -> list:
        """把 FT.SEARCH 响应统一成 [{"key": str, "fields": {name: str|float}}]。

        支持：
          - RESP2 原始列表：[total, key1, [field, value, ...], key2, [...], ...]
          - RESP3 原始字典（redis-py>=8 默认协议）：
              {b"results": [{b"id": ..., b"extra_attributes": {b"score": ...}}]}
          - redis-py 封装对象：Result.docs / Document.id / Document.score
        """
        if raw is None:
            return []
        # redis-py SearchCommands 的 Result/Document 封装
        docs_attr = getattr(raw, "docs", None)
        if docs_attr is not None:
            hits = []
            for doc in docs_attr or []:
                fields = {}
                score = getattr(doc, "score", None)
                if score is not None:
                    fields["score"] = score
                for name, value in vars(doc).items() if hasattr(doc, "__dict__") else []:
                    if name in ("id", "payload", "score") or name.startswith("_"):
                        continue
                    if isinstance(value, (str, bytes, int, float)):
                        fields[name] = _decode(value)
                hits.append({"key": _decode(getattr(doc, "id", "")), "fields": fields})
            return hits
        # RESP3 字典（redis-py 8 默认 RESP3，dict 的 key 可能是 bytes）
        if isinstance(raw, dict):
            normalized = {_decode(k): v for k, v in raw.items()}
            hits = []
            for item in normalized.get("results") or []:
                if not isinstance(item, dict):
                    continue
                item = {_decode(k): v for k, v in item.items()}
                fields = {}
                extra = item.get("extra_attributes") or item.get("attributes")
                fields.update(RedisVectorKB._normalize_fields(extra))
                values = item.get("values")
                if isinstance(values, dict):
                    fields.update(RedisVectorKB._normalize_fields(values))
                elif isinstance(values, (list, tuple)):
                    # 新版 RESP3 的 values 可能是 [{key, value}, ...]
                    for entry in values:
                        if isinstance(entry, dict):
                            fields.update(RedisVectorKB._normalize_fields(entry))
                    if not fields and values:
                        fields.update(RedisVectorKB._normalize_fields(values))
                hits.append({"key": _decode(item.get("id", "")), "fields": fields})
            return hits
        # RESP2 列表
        if isinstance(raw, (list, tuple)):
            hits = []
            index = 1 if raw and _to_int(raw[0]) is not None else 0
            while index + 1 < len(raw):
                key = _decode(raw[index])
                hits.append({
                    "key": key,
                    "fields": RedisVectorKB._normalize_fields(raw[index + 1]),
                })
                index += 2
            return hits
        return []

    def _fetch_docs(self, client, ids: list) -> dict:
        """批量 HGETALL aikb:doc:<id>，返回 {id: {field: value}}。"""
        ids = [doc_id for doc_id in ids if doc_id]
        if not ids:
            return {}
        docs = {}
        try:
            pipe = client.pipeline(transaction=False)
            for doc_id in ids:
                pipe.hgetall(DOC_PREFIX + doc_id)
            rows = pipe.execute()
        except Exception as exc:
            logger.debug("批量读取文档元数据失败（尝试逐条）: %s", exc)
            rows = []
            for doc_id in ids:
                try:
                    rows.append(client.execute_command("HGETALL", DOC_PREFIX + doc_id))
                except Exception:
                    rows.append(None)
        for doc_id, row in zip(ids, rows):
            if isinstance(row, dict):
                docs[doc_id] = {_decode(k): _decode(v) for k, v in row.items()}
            elif isinstance(row, (list, tuple)):
                docs[doc_id] = {_decode(row[i]): _decode(row[i + 1])
                                for i in range(0, len(row) - 1, 2)}
        return docs

    @staticmethod
    def build_results(hits: list, docs_by_id: dict) -> list:
        """KNN 命中 + 文档元数据 -> 统一检索结果结构（score 初始为向量余弦相似度）。"""
        results = []
        for hit in hits or []:
            key = _decode(hit.get("key"))
            doc_id = key[len(VECTOR_PREFIX):] if key.startswith(VECTOR_PREFIX) else key
            meta = docs_by_id.get(doc_id) or {}
            fields = hit.get("fields") or {}
            source = meta.get("source") or _decode(fields.get("source")) or ""
            distance = _to_float(fields.get("score"))
            score = round(max(0.0, 1.0 - distance), 4) if distance is not None else 0.0
            results.append({
                "id": doc_id,
                "title": meta.get("title") or doc_id,
                "category": category_label(source),
                "content": meta.get("text") or "",
                "source": source,
                "score": score,
                "vector_score": score,
                "lexical_score": None,
                "authority": source_authority(source),
                "distance": round(distance, 4) if distance is not None else None,
                "refId": meta.get("refId"),
            })
        results.sort(key=lambda item: item["score"], reverse=True)
        return results

    @staticmethod
    def rerank_results(query: str, results: list, top_k: int = 4,
                       alpha: float = HYBRID_VECTOR_WEIGHT) -> list:
        """词面 hybrid 重排 + 来源权威度加权，返回 top_k。

        - hybrid = alpha*向量余弦 + (1-alpha)*中文词面覆盖率；
        - score = hybrid * 来源权威度（news=0.75、manual=0.95、其余 1.0），
          与后端 KnowledgeRagService 口径一致，避免无关市场资讯进入 RAG 上下文；
        - 返回项保留 vector_score / lexical_score / hybrid_score / authority / distance。
        """
        rescored = []
        for item in results or []:
            vector_score = item.get("vector_score")
            if vector_score is None:
                vector_score = item.get("score") or 0.0
            vector_score = max(0.0, min(1.0, _to_float(vector_score) or 0.0))
            text = f"{item.get('title') or ''} {item.get('content') or ''}"
            lex = lexical_score(query, text)
            hybrid = hybrid_score(vector_score, lex, alpha)
            authority = item.get("authority")
            authority = source_authority(item.get("source")) if authority is None \
                else max(0.0, min(1.0, _to_float(authority) or 0.0))
            rescored.append({**item,
                             "score": round(hybrid * authority, 4),
                             "hybrid_score": hybrid,
                             "vector_score": round(vector_score, 4),
                             "lexical_score": lex,
                             "authority": round(authority, 4)})
        rescored.sort(key=lambda row: (row["score"], row["vector_score"]), reverse=True)
        return rescored[:max(int(top_k), 0)]

    @staticmethod
    def rrf_fuse(
        dense_hits: list,
        sparse_hits: list,
        docs_by_id: dict,
        w_dense: float,
        w_sparse: float,
        k: int = RRF_K_CONSTANT,
        top_k: int = 4,
        source: str | None = None,
    ) -> list:
        """倒数排名融合算法（Reciprocal Rank Fusion, k=60）与自适应加权。

        RRF(d) = w_dense / (k + rank_dense(d)) + w_sparse / (k + rank_sparse(d))
        """
        ranks_dense = {}
        dense_map = {}
        for idx, hit in enumerate(dense_hits or []):
            doc_id = hit["id"]
            ranks_dense[doc_id] = idx + 1
            dense_map[doc_id] = hit

        ranks_sparse = {}
        sparse_map = {}
        for idx, hit in enumerate(sparse_hits or []):
            doc_id = hit["id"]
            ranks_sparse[doc_id] = idx + 1
            sparse_map[doc_id] = hit

        all_doc_ids = list(dict.fromkeys(list(ranks_dense.keys()) + list(ranks_sparse.keys())))
        if not all_doc_ids:
            return []

        fused = []
        for doc_id in all_doc_ids:
            r_d = ranks_dense.get(doc_id)
            r_s = ranks_sparse.get(doc_id)

            score_dense_comp = (w_dense / (k + r_d)) if r_d is not None else 0.0
            score_sparse_comp = (w_sparse / (k + r_s)) if r_s is not None else 0.0
            rrf_score = score_dense_comp + score_sparse_comp

            # 归一化映射到 [0.0, 1.0]：单路第 1 名最大权重分约等于 (k+1)
            norm_score = min(1.0, rrf_score * (k + 1))

            meta = docs_by_id.get(doc_id) or {}
            d_hit = dense_map.get(doc_id) or {}
            s_hit = sparse_map.get(doc_id) or {}
            fields = d_hit.get("fields") or s_hit.get("fields") or {}

            source_tag = meta.get("source") or _decode(fields.get("source")) or ""
            authority = source_authority(source_tag)
            final_score = round(norm_score * authority, 4)

            title = meta.get("title") or _decode(fields.get("title")) or doc_id
            content = meta.get("text") or _decode(fields.get("text")) or ""

            fused.append({
                "id": doc_id,
                "title": title,
                "category": category_label(source_tag),
                "content": content,
                "source": source_tag,
                "score": final_score,
                "rrf_score": round(rrf_score, 6),
                "vector_score": d_hit.get("score"),
                "sparse_score": s_hit.get("score"),
                "dense_rank": r_d,
                "sparse_rank": r_s,
                "authority": round(authority, 4),
                "distance": d_hit.get("distance"),
                "refId": meta.get("refId") or _decode(fields.get("refId")),
            })

        fused.sort(key=lambda item: (item["score"], item["rrf_score"]), reverse=True)
        return fused[:max(int(top_k), 0)]

    def _search_dense(self, client, query: str, candidate_k: int, source: str | None = None) -> list:
        vector = self.embed_query(query)
        if not vector:
            return []
        raw = client.execute_command(
            "FT.SEARCH", self.index, self.build_search_query(candidate_k, source),
            "PARAMS", 4, "vec", self._vector_bytes(vector), "k", candidate_k,
            "LIMIT", 0, candidate_k, "DIALECT", 2)
        hits = self.parse_search_response(raw)
        out = []
        for hit in hits:
            key = _decode(hit.get("key"))
            doc_id = key[len(VECTOR_PREFIX):] if key.startswith(VECTOR_PREFIX) else key
            fields = hit.get("fields") or {}
            distance = _to_float(fields.get("score"))
            score = round(max(0.0, 1.0 - distance), 4) if distance is not None else 0.0
            out.append({
                "id": doc_id,
                "score": score,
                "distance": distance,
                "fields": fields,
            })
        return out

    def _search_sparse(self, client, query: str, candidate_k: int, source: str | None = None) -> list:
        sparse_query = sanitize_sparse_query(query, source)
        if not sparse_query:
            return []
        raw = client.execute_command(
            "FT.SEARCH", self.index, sparse_query,
            "LIMIT", 0, candidate_k, "DIALECT", 2)
        hits = self.parse_search_response(raw)
        out = []
        for hit in hits:
            key = _decode(hit.get("key"))
            doc_id = key[len(VECTOR_PREFIX):] if key.startswith(VECTOR_PREFIX) else key
            fields = hit.get("fields") or {}
            score = _to_float(fields.get("score")) or 1.0
            out.append({
                "id": doc_id,
                "score": score,
                "distance": None,
                "fields": fields,
            })
        return out

    def search(self, query: str, top_k: int = 4, source: str | None = None) -> list:
        """双路检索（Dense Vector KNN + Sparse BM25）+ 自适应加权 RRF 融合。

        任何单路异常均优雅容灾：
        - Ollama 离线/超时 -> Sparse 文本倒排路独立支撑，保障精准型号召回；
        - 分词或纯语义问答 -> Dense 向量路主导；
        - 两路均无或 Redis 异常 -> 返回 [] 由 HybridKB 回退本地 TF-IDF，绝不报 500。
        """
        if not query or not query.strip() or top_k <= 0:
            return []
        if not self.available():
            return []
        client = self._client
        if client is None:
            return []

        w_dense, w_sparse = detect_adaptive_weights(query)
        multiplier = (HYBRID_CANDIDATE_MULTIPLIER if source
                      else HYBRID_CANDIDATE_MULTIPLIER_UNFILTERED)
        candidate_k = min(max(int(top_k) * multiplier, HYBRID_MIN_CANDIDATES), 60)

        # 1. 密集向量召回 (Dense KNN)
        dense_hits = []
        try:
            dense_hits = self._search_dense(client, query, candidate_k, source)
        except Exception as exc:
            logger.warning("Dense KNN 召回异常: %s", exc)

        # 2. 稀疏文本召回 (Sparse BM25)
        sparse_hits = []
        try:
            sparse_hits = self._search_sparse(client, query, candidate_k, source)
        except Exception as exc:
            logger.warning("Sparse BM25 召回异常: %s", exc)

        # 3. 兜底回退：若两路都无命中，直接返回空由 HybridKB 回退 TF-IDF
        if not dense_hits and not sparse_hits:
            return []

        # 4. 收集元数据并融合
        all_ids = []
        for h in dense_hits:
            if h["id"] not in all_ids:
                all_ids.append(h["id"])
        for h in sparse_hits:
            if h["id"] not in all_ids:
                all_ids.append(h["id"])

        docs = self._fetch_docs(client, all_ids)
        rerank_k = len(all_ids) if source is None else top_k
        fused = self.rrf_fuse(
            dense_hits=dense_hits,
            sparse_hits=sparse_hits,
            docs_by_id=docs,
            w_dense=w_dense,
            w_sparse=w_sparse,
            k=RRF_K_CONSTANT,
            top_k=rerank_k,
            source=source,
        )

        if source is None:
            fused = [row for row in fused
                     if str(row.get("source") or "").strip().lower() not in RAG_EXCLUDED_SOURCES]

        return fused[:max(int(top_k), 0)]

    # ------------------------------ 统计 ------------------------------

    @staticmethod
    def _info_num_docs(info):
        if isinstance(info, dict):
            return _to_int(info.get("num_docs") or info.get(b"num_docs"))
        if isinstance(info, (list, tuple)):
            for i in range(0, len(info) - 1):
                if _decode(info[i]) == "num_docs":
                    return _to_int(info[i + 1])
        return None

    def stats(self) -> dict:
        """{backend, available, document_count, dim, index, embed_provider, hybrid, ...}"""
        out = {
            "backend": "redis-vector",
            "available": False,
            "index": self.index,
            "document_count": 0,
            "dim": self._dim,
            "embed_provider": self.embed_provider,
            "embed_model": self.embedder.model,
            "redis_url": self._active_url,
            "cooldown_sec": self.cooldown_sec,
            "hybrid": {
                "mode": "dual-path-rrf",
                "rrf_k": RRF_K_CONSTANT,
                "adaptive_weights": {
                    "spec": {"dense": DEFAULT_DENSE_SPEC_WEIGHT, "sparse": DEFAULT_SPARSE_SPEC_WEIGHT},
                    "qa": {"dense": DEFAULT_DENSE_QA_WEIGHT, "sparse": DEFAULT_SPARSE_QA_WEIGHT},
                },
                "vector_weight": round(self.hybrid_alpha, 4),
                "lexical_weight": round(1.0 - self.hybrid_alpha, 4),
                "candidate_multiplier": HYBRID_CANDIDATE_MULTIPLIER,
                "min_candidates": HYBRID_MIN_CANDIDATES,
                "source_authority": dict(SOURCE_AUTHORITY_WEIGHTS,
                                         default=DEFAULT_SOURCE_AUTHORITY),
                "excluded_sources": sorted(RAG_EXCLUDED_SOURCES),
            },
            "error": self._error,
        }
        if not self.available():
            return out
        client = self._client
        try:
            out["document_count"] = int(client.execute_command("SCARD", ALL_DOCS_KEY) or 0)
        except Exception:  # pragma: no cover - 防御性
            pass
        if not out["document_count"]:
            try:
                info = client.execute_command("FT.INFO", self.index)
                out["document_count"] = self._info_num_docs(info) or 0
            except Exception:  # pragma: no cover - 防御性
                pass
        try:
            out["dim"] = self._fetch_dim(client) or self._dim
        except Exception:  # pragma: no cover - 防御性
            pass
        out["available"] = True
        out["error"] = None
        return out


class HybridKB:
    """混合知识库：Redis 向量优先，结果为空/异常时回退本地 TF-IDF。"""

    def __init__(self, kb_path: str | None = None, redis_kb: RedisVectorKB | None = None,
                 embedder: OllamaEmbedder | None = None):
        self.kb_path = kb_path or default_kb_path()
        self.fallback = KnowledgeBase(self.kb_path)
        self.redis_kb = redis_kb or RedisVectorKB(embedder=embedder)
        self.last_backend = "tfidf"

    def search(self, query: str, top_k: int = 4, with_context: bool = True,
               source: str | None = None) -> list:
        """统一检索入口：优先 Redis 向量库，无命中/异常回退 TF-IDF。

        source 为可选来源过滤（Redis 走索引 TAG，TF-IDF 只能按分类字段事后筛）。
        """
        try:
            if self.redis_kb.available():
                hits = self.redis_kb.search(query, top_k=top_k, source=source)
                if hits:
                    self.last_backend = "redis-vector"
                    return hits
        except Exception as exc:  # 防御：任何 Redis 异常都不影响 RAG
            logger.warning("Redis 知识库检索异常，回退 TF-IDF: %s", exc)
        self.last_backend = "tfidf"
        try:
            rows = self.fallback.search(query, top_k=top_k, with_context=with_context)
            if source:
                want = source.strip().lower()
                rows = [r for r in rows
                        if want in {str(r.get("source") or "").strip().lower(),
                                    str(r.get("category") or "").strip().lower()}]
            return rows
        except Exception as exc:  # pragma: no cover - 防御性
            logger.error("TF-IDF 知识库检索失败: %s", exc)
            return []

    def reload(self) -> None:
        """同时重建 TF-IDF 索引并重连 Redis。"""
        self.fallback.reload()
        try:
            self.redis_kb.reset()
        except Exception:  # pragma: no cover - 防御性
            pass
        self.last_backend = "redis-vector" if self.redis_kb.available() else "tfidf"

    def stats(self) -> dict:
        """健康检查用的知识库统计（backend 标注当前实际后端）。"""
        tfidf_stats = self.fallback.stats()
        try:
            redis_stats = self.redis_kb.stats()
        except Exception as exc:  # pragma: no cover - 防御性
            redis_stats = {"backend": "redis-vector", "available": False, "error": str(exc)}
        available = bool(redis_stats.get("available"))
        backend = "redis-vector" if available else "tfidf"
        return {
            "backend": backend,
            "kb_backend": backend,
            "available": available,
            "document_count": (redis_stats.get("document_count") or 0) if available
                              else tfidf_stats.get("document_count", 0),
            "fallback_document_count": tfidf_stats.get("document_count", 0),
            "dim": (redis_stats.get("dim") or 768) if available else _EMBED_DIM_FALLBACK,
            "index": redis_stats.get("index"),
            "embed_provider": redis_stats.get("embed_provider"),
            "categories": tfidf_stats.get("categories", []),
            "vocab_size": tfidf_stats.get("vocab_size", 0),
            "last_backend": self.last_backend,
            "redis": redis_stats,
        }

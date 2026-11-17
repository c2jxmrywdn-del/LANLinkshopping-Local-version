# -*- coding: utf-8 -*-
"""网关前置两级语义缓存（Semantic Cache）单元测试与端到端回归套件。"""
import json
import time
import pytest
from fastapi.testclient import TestClient

from app.semantic_cache import SemanticCache, CacheHit
from app.main import app, CACHE, PRIMARY_GATEWAY_KEY


class MockEmbedder:
    """Mock 向量生成器，便于可重现的余弦相似度测试"""
    def __init__(self, dim: int = 768):
        self.dim = dim

    def embed_one(self, text: str):
        import hashlib
        import math
        # 基于字符特征构造平滑的高维向量
        vec = [0.0] * self.dim
        # 将语义相近的句子映射到高重合度桶
        t_clean = text.replace("7", "七").replace("退换", "退").replace("？", "").replace("?", "").strip()
        h = int(hashlib.md5(t_clean[:6].encode("utf-8")).hexdigest(), 16)
        for i in range(16):
            idx = (h + i * 37) % self.dim
            vec[idx] = 1.0
        # 归一化
        norm = math.sqrt(sum(v * v for v in vec))
        return [v / norm for v in vec]


@pytest.fixture
def clean_cache():
    """每次测试前后清空内存与 Redis 缓存"""
    CACHE.invalidate()
    yield CACHE
    CACHE.invalidate()


def test_exact_cache_hit_and_miss(clean_cache):
    """测试 Tier 1 精确哈希匹配与角色隔离"""
    cache = clean_cache
    query = "请问商城支持7天无理由退货吗？"
    
    # 1. 初始未命中
    hit = cache.get(query, role="buyer")
    assert hit is None

    # 2. 写入缓存
    ok = cache.set(query, "平台支持7天无理由退货（定制品除外）。", reasoning_content="解析售后政策", role="buyer")
    assert ok is True

    # 3. 再次查询，精确命中
    hit2 = cache.get(query, role="buyer")
    assert hit2 is not None
    assert hit2.hit_type == "exact"
    assert hit2.similarity == 1.0
    assert "7天无理由" in hit2.content
    assert hit2.reasoning_content == "解析售后政策"

    # 4. 角色隔离测试：buyer 的缓存对 merchant 角色隔离不可见
    hit_merchant = cache.get(query, role="merchant")
    assert hit_merchant is None


def test_semantic_cache_vss_hit(clean_cache):
    """测试 Tier 2 语义向量相似度近邻命中"""
    mock_emb = MockEmbedder(dim=768)
    cache = SemanticCache(embedder=mock_emb, dim=768, similarity_threshold=0.96)
    
    # 存入标准问答
    q1 = "七天无理由退货运费谁付"
    ans = "买家原因退货由买家承担运费，商品质量问题由商家承担运费。"
    cache.set(q1, ans, role="buyer")

    # 用变体提问（触发近邻相似度匹配）
    q2 = "7天无理由退货运费谁付？"
    hit = cache.get(q2, role="buyer")
    assert hit is not None
    assert hit.hit_type in ("exact", "semantic")
    assert hit.similarity >= 0.96
    assert "由买家承担运费" in hit.content


def test_sensitive_and_order_privacy_unaccheable():
    """测试交易安全与隐私围栏：动态订单、手机号、密码绝对不缓存"""
    assert SemanticCache.is_cacheable("发票申请流程是怎样的？") is True
    assert SemanticCache.is_cacheable("查询订单 ORD-20261101-001 详情") is False
    assert SemanticCache.is_cacheable("帮我跟进 TX-992381283 支付流水") is False
    assert SemanticCache.is_cacheable("我的手机号 13812345678 请帮我查积分") is False
    assert SemanticCache.is_cacheable("修改我的支付密码为 123456") is False
    # 工具包含写操作草稿单不可缓存
    assert SemanticCache.is_cacheable("我想下单买5件商品", tools_mode=True, tool_trace=[{"tool": "create_order_draft"}]) is False


def test_cache_invalidation(clean_cache):
    """测试缓存主动失效 API"""
    cache = clean_cache
    cache.set("问题A", "回答A", role="buyer")
    cache.set("问题B", "回答B", role="merchant")

    assert cache.get("问题A", role="buyer") is not None
    assert cache.get("问题B", role="merchant") is not None

    # 按角色失效 buyer
    deleted = cache.invalidate(role="buyer")
    assert deleted >= 1
    assert cache.get("问题A", role="buyer") is None
    # merchant 依然存在
    assert cache.get("问题B", role="merchant") is not None

    # 清空所有
    cache.invalidate()
    assert cache.get("问题B", role="merchant") is None


def test_stream_cached_response_generator():
    """测试流式 SSE 模拟重放生成器"""
    hit = CacheHit(
        hit_type="semantic",
        similarity=0.9782,
        content="这是第一句。这是第二句！这是最终结论。",
        reasoning_content="分析上下文",
        cache_id="test-cache-id",
        latency_ms=3.5,
    )
    generator = SemanticCache.stream_cached_response(hit, model="qwen2.5-7b", chunk_interval=0.001)
    chunks = list(generator)
    assert len(chunks) >= 4
    
    # 验证 SSE 格式
    assert chunks[0].startswith("data: {")
    assert "assistant" in chunks[0]
    assert "分析上下文" in chunks[1]  # reasoning
    # 验证结尾有 [DONE]
    assert chunks[-1] == "data: [DONE]\n\n"
    # 验证尾包带有 cached 标记
    tail_chunk = json.loads(chunks[-2].replace("data: ", "").strip())
    assert tail_chunk["lanlink"]["cached"] is True
    assert tail_chunk["lanlink"]["cache_similarity"] == 0.9782


def test_gateway_chat_completions_cache_e2e(clean_cache):
    """端到端验证 /v1/chat/completions 缓存命中与响应头"""
    from app.main import LLM
    orig_pref = LLM.backend_pref
    LLM.backend_pref = "mock"
    try:
        client = TestClient(app)
        headers = {
            "Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}",
            "X-Agent-Role": "buyer",
        }
        payload = {
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": "请问商城的7天无理由退货运费规则？"}],
            "use_kb": False,
        }

        # 1. 第一次请求：MISS，调用大模型/降级并写回缓存
        resp1 = client.post("/v1/chat/completions", json=payload, headers=headers)
        assert resp1.status_code == 200
        assert resp1.headers.get("X-Lanlink-Cache") == "MISS"
        
        # 等待异步写回线程就绪（最多等待 2.0s 防抖）
        q_text = payload["messages"][0]["content"]
        cached = False
        for _ in range(20):
            # use_kb 是隔离维度：本次请求是 use_kb=False，必须按同一维度查询
            if CACHE.get(q_text, role="buyer", model="qwen2.5-7b", use_kb=False) is not None:
                cached = True
                break
            time.sleep(0.1)
        assert cached is True, "异步写回缓存未在预期时间内生效"

        # 2. 第二次请求相同问题：HIT-EXACT，毫秒级响应
        resp2 = client.post("/v1/chat/completions", json=payload, headers=headers)
        assert resp2.status_code == 200
        assert resp2.headers.get("X-Lanlink-Cache") in ("HIT-EXACT", "HIT-SEMANTIC")
        data2 = resp2.json()
        assert data2["lanlink"]["cached"] is True
        # mock 后端属于本地来源，命中回填仍应是 local: 前缀
        assert data2["lanlink"]["answer_generator"] == "local:semantic_cache"

        # 3. 统计接口与主动失效校验
        stat_resp = client.post("/cache/invalidate", json={"role": "buyer"}, headers=headers)
        assert stat_resp.status_code == 200
        assert stat_resp.json()["status"] == "ok"

        # 4. 失效后再查：再次 MISS
        resp3 = client.post("/v1/chat/completions", json=payload, headers=headers)
        assert resp3.status_code == 200
        assert resp3.headers.get("X-Lanlink-Cache") == "MISS"
    finally:
        LLM.backend_pref = orig_pref


def test_gateway_use_kb_toggle_does_not_cross_hit(clean_cache):
    """网关层：同问题先 use_kb=true 再 use_kb=false，第二次不得命中缓存。"""
    from app.main import LLM
    orig_pref = LLM.backend_pref
    LLM.backend_pref = "mock"
    try:
        client = TestClient(app)
        headers = {
            "Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}",
            "X-Agent-Role": "buyer",
        }
        question = "企业采购账期最长可以申请多少天"
        base = {
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": question}],
        }

        resp1 = client.post("/v1/chat/completions",
                            json=dict(base, use_kb=True), headers=headers)
        assert resp1.status_code == 200
        assert resp1.headers.get("X-Lanlink-Cache") == "MISS"

        for _ in range(20):
            if CACHE.get(question, role="buyer", model="qwen2.5-7b", use_kb=True) is not None:
                break
            time.sleep(0.1)

        # use_kb=false：prompt 形态不同，必须重新生成而不是回放 RAG 答案
        resp2 = client.post("/v1/chat/completions",
                            json=dict(base, use_kb=False), headers=headers)
        assert resp2.status_code == 200
        assert resp2.headers.get("X-Lanlink-Cache") == "MISS"
        assert resp2.json()["lanlink"]["backend"] != "semantic_cache"

        # 回到 use_kb=true：应当命中
        resp3 = client.post("/v1/chat/completions",
                            json=dict(base, use_kb=True), headers=headers)
        assert resp3.headers.get("X-Lanlink-Cache") in ("HIT-EXACT", "HIT-SEMANTIC")
    finally:
        LLM.backend_pref = orig_pref


def test_gateway_cache_control_no_cache_bypasses_hit(clean_cache):
    """`Cache-Control: no-cache` 仍必须绕过缓存读取。"""
    from app.main import LLM
    orig_pref = LLM.backend_pref
    LLM.backend_pref = "mock"
    try:
        client = TestClient(app)
        headers = {
            "Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}",
            "X-Agent-Role": "buyer",
        }
        question = "会员积分如何兑换优惠券"
        payload = {
            "model": "qwen2.5-7b",
            "messages": [{"role": "user", "content": question}],
            "use_kb": False,
        }

        assert client.post("/v1/chat/completions", json=payload,
                           headers=headers).headers.get("X-Lanlink-Cache") == "MISS"
        for _ in range(20):
            if CACHE.get(question, role="buyer", model="qwen2.5-7b", use_kb=False) is not None:
                break
            time.sleep(0.1)

        bypass = dict(headers, **{"Cache-Control": "no-cache"})
        resp = client.post("/v1/chat/completions", json=payload, headers=bypass)
        assert resp.status_code == 200
        assert resp.headers.get("X-Lanlink-Cache") == "MISS"
        assert resp.json()["lanlink"].get("cached") is not True
    finally:
        LLM.backend_pref = orig_pref


def test_gateway_external_cached_answer_never_reports_local_prefix(clean_cache):
    """外部模型生成并写入缓存后，命中时 answer_generator 绝不能带 local: 前缀。"""
    from app.main import LLM
    orig_pref = LLM.backend_pref
    LLM.backend_pref = "mock"
    try:
        client = TestClient(app)
        headers = {
            "Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}",
            "X-Agent-Role": "buyer",
        }
        question = "支持哪些跨境结算币种"
        # 直接以外部后端身份写入缓存，模拟 MiMo 生成过的答案
        CACHE.set(question, "支持 USD / EUR / JPY 结算。", role="buyer",
                  model="mimo-v2.6-flash", use_kb=False, generator="external:mimo")

        resp = client.post("/v1/chat/completions", json={
            "model": "mimo-v2.6-flash",
            "messages": [{"role": "user", "content": question}],
            "use_kb": False,
        }, headers=headers)
        assert resp.status_code == 200
        assert resp.headers.get("X-Lanlink-Cache") in ("HIT-EXACT", "HIT-SEMANTIC")
        generator = resp.json()["lanlink"]["answer_generator"]
        assert generator == "external:semantic_cache"
        assert not generator.startswith("local:")
    finally:
        LLM.backend_pref = orig_pref


# --------------------- 回归：__init__ 初始化块必须可达 ---------------------
# 历史缺陷：`_redis_available/_redisearch_ready/_last_index_check/_pubsub_listener`
# 四行初始化与 start_pubsub_listener() 被误放在 `_is_invalidated_since` 的 return 之后，
# 成为死代码，实例缺少这四个属性。后果：
#   1) 注入 Redis client 后 `get()` → `_ensure_index()` → AttributeError → chat 500；
#   2) `/cache/stats`、`is_pubsub_listener_running()`、shutdown 的 `stop_pubsub_listener()` 恒抛。
# 修复前以下用例全部失败，修复后必须通过。

_CACHE_INIT_ATTRS = (
    "_redis_available",
    "_redisearch_ready",
    "_last_index_check",
    "_pubsub_listener",
)


class _FakeRedis:
    """仅实现 SemanticCache 读路径用到的最小 Redis 接口，避免测试依赖真实 Redis。"""

    def __init__(self, hashes=None):
        # 角色级失效要逐键回读判定归属：doc 键用 hget("role") 判定
        self._hashes = hashes or {}
        self.deleted = []

    def get(self, key):
        return None

    def hget(self, key, field):
        return self._hashes.get(key, {}).get(field)

    def execute_command(self, *args, **kwargs):
        # FT._LIST 返回空 → _ensure_index 走 FT.CREATE 分支后视为就绪；
        # FT.SEARCH 返回空 → 无命中，继续走内存兜底。
        return []

    def scan_iter(self, match=None, count=None):
        return iter([k for k in self._hashes if k.startswith(match.rstrip("*"))])

    def delete(self, key):
        self.deleted.append(key)
        return 1


class _FixedDimEmbedder:
    """返回固定维度的确定性向量，用于把 dim 对齐后触发 Tier-2 路径。"""

    def __init__(self, dim: int):
        self.dim = dim

    def embed_one(self, text: str):
        return [0.01] * self.dim

    def embed(self, texts):
        return [[0.01] * self.dim for _ in texts]


@pytest.fixture
def no_pubsub(monkeypatch):
    """显式关闭 Pub/Sub，避免用例在无 Redis 环境里起后台重连线程。"""
    monkeypatch.setenv("GATEWAY_ENABLE_PUBSUB_INVALIDATION", "false")


def test_init_defines_redis_and_listener_attributes(no_pubsub):
    """构造后四个探针/监听器属性必须存在（修复前它们落在死代码区，实例没有这些属性）。"""
    cache = SemanticCache()
    missing = [name for name in _CACHE_INIT_ATTRS if not hasattr(cache, name)]
    assert missing == [], f"__init__ 未初始化这些属性（死代码回归）: {missing}"
    assert cache._redis_available is True
    assert cache._redisearch_ready is False
    assert cache._last_index_check == 0.0
    assert cache._pubsub_listener is None


def test_default_instances_do_not_spawn_listener_threads():
    """默认构造不得起后台线程：实例数增长不应线性增加重连线程。"""
    import threading

    baseline = threading.active_count()
    caches = [SemanticCache() for _ in range(3)]
    assert threading.active_count() == baseline, "默认实例不应启动 Pub/Sub 线程"
    assert all(c.is_pubsub_listener_running() is False for c in caches)
    for cache in caches:
        cache.stop_pubsub_listener()


def test_role_invalidate_only_deletes_that_role_in_redis(no_pubsub):
    """角色级失效只能删该角色的 Redis 键，不能清空其它角色（广播路径会走这里）。"""
    buyer_key = "aigateway:scache:doc:buyer-doc"
    merchant_key = "aigateway:scache:doc:merchant-doc"
    redis = _FakeRedis(hashes={
        buyer_key: {"role": "buyer"},
        merchant_key: {"role": "merchant"},
    })
    cache = SemanticCache(redis_client=redis)
    cache.invalidate(role="buyer")
    assert redis.deleted == [buyer_key], f"误删了其它角色的键: {redis.deleted}"

    # 全量失效仍然清空所有角色
    redis.deleted.clear()
    cache.invalidate()
    assert set(redis.deleted) == {buyer_key, merchant_key}


def test_get_with_injected_redis_client_does_not_raise(no_pubsub):
    """注入 Redis client 且维度匹配时，get() 必须正常返回 None，而不是 AttributeError。

    这是 chat 链路的真实崩溃点：`/v1/chat/completions` → CACHE.get() → _ensure_index()。
    修复前此处抛 `AttributeError: '_redisearch_ready'`。
    """
    cache = SemanticCache(redis_client=_FakeRedis(), embedder=_FixedDimEmbedder(768), dim=768)
    assert cache.get("请问如何开具增值税专用发票？") is None

    # 写入后精确命中仍然可用，确认修复没有破坏 Tier-1
    assert cache.set("请问如何开具增值税专用发票？", "需提供开票资料。", role="buyer") is True
    hit = cache.get("请问如何开具增值税专用发票？", role="buyer")
    assert hit is not None and hit.hit_type == "exact"


def test_ensure_index_is_reachable_and_caches_result(no_pubsub):
    """_ensure_index 可正常调用，并在成功后置位 _redisearch_ready。"""
    cache = SemanticCache(redis_client=_FakeRedis(), embedder=_FixedDimEmbedder(768), dim=768)
    assert cache._ensure_index(_FakeRedis()) is True
    assert cache._redisearch_ready is True
    # 第二次调用命中就绪缓存，不再访问 Redis
    assert cache._ensure_index(None) is True


def test_listener_lifecycle_and_stats_fields_are_safe(no_pubsub):
    """`/cache/stats` 与 shutdown 依赖的属性/方法必须可直接调用。"""
    cache = SemanticCache()
    assert cache.is_pubsub_listener_running() is False
    # shutdown 钩子调用点：修复前此处抛 AttributeError
    cache.stop_pubsub_listener()
    # /cache/stats 读取的三个字段
    assert cache._redis_available is True
    assert cache._redisearch_ready is False
    assert cache.is_pubsub_listener_running() is False


def test_cache_stats_endpoint_returns_200(no_pubsub):
    """`GET /cache/stats` 必须返回 200（修复前恒 500）。"""
    from app.main import app as gateway_app, PRIMARY_GATEWAY_KEY

    client = TestClient(gateway_app)
    resp = client.get(
        "/cache/stats",
        headers={"Authorization": f"Bearer {PRIMARY_GATEWAY_KEY}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "ok"
    assert "redis_available" in body
    assert "redisearch_ready" in body
    assert "pubsub_listener_running" in body


# --------------------- 回归：维度必须跟随嵌入模型实际输出 ---------------------
# 历史缺陷：`dim` 默认硬编码 768，而部署注入 `AI_EMBED_MODEL=bge-m3`（1024 维），
# `len(vector) != self.dim` 会静默丢弃全部 Tier-2 写入与命中，语义缓存退化为仅精确匹配。


class _RecordingRedis:
    """记录命令序列的 Redis 替身，用于验证索引维度不一致时会 DROP 后重建。"""

    def __init__(self, index_dim: int, fields=("role", "model", "use_kb", "query", "generator",
                                               "qvec", "created_at")):
        self.commands: list[str] = []
        self._index_dim = index_dim
        # 字段集合可配置：老索引缺 use_kb/generator 时必须走重建分支
        self._fields = list(fields)

    def execute_command(self, *args):
        name = args[0]
        self.commands.append(name)
        if name == "FT._LIST":
            return [b"aigateway:scache:idx"]
        if name == "FT.INFO":
            attrs = []
            for field in self._fields:
                if field == "qvec":
                    attrs.append([b"identifier", b"qvec", b"type", b"VECTOR",
                                  b"DIM", str(self._index_dim).encode()])
                else:
                    attrs.append([b"identifier", field.encode(), b"type", b"TAG"])
            return [b"attributes", attrs]
        if name == "FT.DROPINDEX":
            self._index_dim = 1024
            return b"OK"
        return b"OK"

    def get(self, key):
        return None

    def hget(self, key, field):
        return None


def test_dim_follows_embedder_output_when_unset(no_pubsub, monkeypatch):
    """未配置 AI_EMBED_DIM 时，维度应由嵌入模型首次实际输出锁定（而非硬编码 768）。"""
    monkeypatch.delenv("AI_EMBED_DIM", raising=False)
    cache = SemanticCache(embedder=_FixedDimEmbedder(1024))
    assert cache.dim is None, "未配置时应保持未定，等首次 embed 校准"

    assert cache._resolve_dim([0.1] * 1024) == 1024
    assert cache.dim == 1024


def test_dim_honours_env_override(no_pubsub, monkeypatch):
    """AI_EMBED_DIM 显式配置时优先采用，且非法值不应让构造失败。"""
    monkeypatch.setenv("AI_EMBED_DIM", "1024")
    assert SemanticCache().dim == 1024

    monkeypatch.setenv("AI_EMBED_DIM", "not-a-number")
    assert SemanticCache().dim is None

    monkeypatch.setenv("AI_EMBED_DIM", "0")
    assert SemanticCache().dim is None


def test_dim_mismatch_is_corrected_and_reported(no_pubsub, monkeypatch):
    """配置维度与模型输出不一致时以实际为准，并重置索引就绪标记。"""
    monkeypatch.setenv("AI_EMBED_DIM", "768")
    cache = SemanticCache(embedder=_FixedDimEmbedder(1024))
    assert cache.dim == 768
    cache._redisearch_ready = True

    assert cache._resolve_dim([0.1] * 1024) == 1024
    assert cache.dim == 1024
    assert cache._redisearch_ready is False, "维度变更后必须重建索引"


def test_tier2_write_and_hit_work_with_1024_dim(no_pubsub, monkeypatch):
    """1024 维模型下 Tier-2 写入与命中必须生效（修复前被 `len != 768` 静默丢弃）。"""
    monkeypatch.delenv("AI_EMBED_DIM", raising=False)
    cache = SemanticCache(embedder=_FixedDimEmbedder(1024))
    assert cache.set("七天无理由退货运费谁承担", "买家原因由买家承担。", role="buyer") is True
    # _mem_vectors 只有维度对齐才会写入，这是 Tier-2 生效的直接证据
    assert len(cache._mem_vectors) == 1, "Tier-2 向量未写入（维度仍被过滤）"
    assert cache._mem_vectors[0]["vec"] and len(cache._mem_vectors[0]["vec"]) == 1024


def test_existing_index_with_wrong_dim_is_rebuilt(no_pubsub):
    """已有索引维度与当前模型不一致时必须 DROP 后重建，否则每次 KNN 都报向量长度错误。"""
    redis = _RecordingRedis(index_dim=768)
    cache = SemanticCache(redis_client=redis)
    cache.dim = 1024

    assert cache._ensure_index(redis) is True
    assert "FT.DROPINDEX" in redis.commands, f"未重建索引: {redis.commands}"
    assert redis.commands.index("FT.DROPINDEX") < redis.commands.index("FT.CREATE")


def test_existing_index_with_matching_dim_is_reused(no_pubsub):
    """维度一致时不得重建索引（避免每次启动清空缓存）。"""
    redis = _RecordingRedis(index_dim=1024)
    cache = SemanticCache(redis_client=redis)
    cache.dim = 1024

    assert cache._ensure_index(redis) is True
    assert "FT.DROPINDEX" not in redis.commands
    assert "FT.CREATE" not in redis.commands


def test_index_dim_parser_handles_resp2_and_resp3(no_pubsub):
    """FT.INFO 解析要同时兼容 RESP2 扁平/嵌套列表与 RESP3 dict。"""
    class _Resp:
        def __init__(self, payload):
            self.payload = payload

        def execute_command(self, *args):
            return self.payload

    resp2_nested = [b"attributes",
                    [[b"identifier", b"qvec", b"DIM", b"768"]]]
    resp3_dict = {b"attributes": [{b"identifier": b"qvec", b"DIM": b"1024"}]}
    flat = [b"attributes", [b"identifier", b"qvec", b"DIM", b"1536"]]
    no_dim = [b"attributes", [[b"identifier", b"role"]]]

    assert SemanticCache._index_dim(_Resp(resp2_nested)) == 768
    assert SemanticCache._index_dim(_Resp(resp3_dict)) == 1024
    assert SemanticCache._index_dim(_Resp(flat)) == 1536
    assert SemanticCache._index_dim(_Resp(no_dim)) is None


def test_no_index_created_while_dim_unknown(no_pubsub, monkeypatch):
    """维度未校准时不得凭猜测建索引（猜错就要整库重建），应先退回内存 VSS。"""
    monkeypatch.delenv("AI_EMBED_DIM", raising=False)

    class _EmptyIndex:
        def __init__(self):
            self.commands = []

        def execute_command(self, *args):
            self.commands.append(args[0])
            if args[0] == "FT._LIST":
                return []          # 索引不存在
            return b"OK"

    redis = _EmptyIndex()
    cache = SemanticCache(redis_client=redis)
    assert cache.dim is None
    assert cache._ensure_index(redis) is False
    assert "FT.CREATE" not in redis.commands, f"维度未知却创建了索引: {redis.commands}"
    assert cache._redisearch_ready is False

    # 锁定真实维度后即可正常建索引
    cache._resolve_dim([0.1] * 1024)
    cache._last_index_check = 0.0
    assert cache._ensure_index(redis) is True
    assert "FT.CREATE" in redis.commands


# --------------------- 回归：use_kb / model 隔离与 answer_generator 溯源 ---------------------
# 历史缺陷（两类串味）：
#   1) 精确键只有 sha256(role:query)，Tier-2 也只按 @role 过滤 —— use_kb 与 model
#      都没参与隔离。use_kb=true 会用 RAG 模板重建 messages，与 use_kb=false 是完全
#      不同的 prompt，却共用同一个键，RAG 答案会被回放给非 RAG 请求（反之亦然）；
#   2) 命中时无条件写 `local:semantic_cache`，而缓存内容可能来自 MiMo/DeepSeek，
#      会骗过 Java 侧 SupportAgentPolicy.assertLocal，把外部答案冒充成本地结果。


def test_use_kb_dimension_isolates_cache(clean_cache):
    """同一问题在 use_kb=True / False 下必须互不命中（prompt 形态完全不同）。"""
    cache = clean_cache
    query = "企业采购账期最长可以申请多少天"

    assert cache.set(query, "RAG 模板答案：最长 60 天。", role="buyer",
                     model="qwen2.5-7b", use_kb=True, generator="llamacpp:qwen2.5-7b") is True

    # 同一问题、同一模型，仅 use_kb 不同 → 不得命中 RAG 答案
    assert cache.get(query, role="buyer", model="qwen2.5-7b", use_kb=False) is None
    # 同维度仍可命中，确认隔离没有把正常命中一起关掉
    hit = cache.get(query, role="buyer", model="qwen2.5-7b", use_kb=True)
    assert hit is not None and "RAG 模板答案" in hit.content

    # 反向：先写非 RAG，再用 RAG 维度查，同样不得命中
    assert cache.set(query, "调用方自带 grounding 的答案。", role="buyer",
                     model="qwen2.5-7b", use_kb=False,
                     generator="llamacpp:qwen2.5-7b") is True
    assert cache.get(query, role="buyer", model="qwen2.5-7b", use_kb=True).content \
        == "RAG 模板答案：最长 60 天。"


def test_use_kb_isolation_holds_on_tier2_semantic_path(no_pubsub):
    """Tier-2 近似命中同样受 use_kb 约束，不能绕过 Tier-1 的隔离维度。"""
    cache = SemanticCache(embedder=_FixedDimEmbedder(768), dim=768,
                          similarity_threshold=0.96)
    assert cache.set("七天无理由退货运费谁承担", "买家原因由买家承担运费。", role="buyer",
                     model="qwen2.5-7b", use_kb=True) is True

    # _FixedDimEmbedder 对所有文本返回同一向量 → 相似度恒为 1.0，
    # 若缺 use_kb 过滤就会在这里语义命中
    assert cache.get("完全不同的另一个问题", role="buyer",
                     model="qwen2.5-7b", use_kb=False) is None
    hit = cache.get("完全不同的另一个问题", role="buyer", model="qwen2.5-7b", use_kb=True)
    assert hit is not None and hit.hit_type == "semantic"


def test_model_dimension_isolates_cache(clean_cache):
    """model=qwen2.5-7b 与 model=mimo-v2.6-flash 的同一问题不得互相命中。"""
    cache = clean_cache
    query = "平台支持哪些对公结算方式"

    assert cache.set(query, "本地 7B 生成的答案。", role="buyer",
                     model="qwen2.5-7b", use_kb=False,
                     generator="llamacpp:qwen2.5-7b") is True

    assert cache.get(query, role="buyer", model="mimo-v2.6-flash", use_kb=False) is None
    hit = cache.get(query, role="buyer", model="qwen2.5-7b", use_kb=False)
    assert hit is not None and hit.content == "本地 7B 生成的答案。"


def test_model_isolation_holds_on_tier2_semantic_path(no_pubsub):
    """Tier-2 向量兜底也必须按 model 过滤。"""
    cache = SemanticCache(embedder=_FixedDimEmbedder(768), dim=768,
                          similarity_threshold=0.96)
    assert cache.set("对公转账多久到账", "T+1 到账。", role="buyer",
                     model="qwen2.5-7b", use_kb=True) is True

    assert cache.get("换一个说法的问题", role="buyer",
                     model="mimo-v2.6-flash", use_kb=True) is None


def test_cache_hit_reports_provenance_of_original_backend(clean_cache):
    """命中时的 answer_generator 必须按原始生成后端标注，不能一律冒充 local:。"""
    cache = clean_cache
    query = "售后退款多久原路返回"

    # 1) 本地生成 → local:semantic_cache
    cache.set(query, "本地答案：1-3 个工作日。", role="buyer", model="qwen2.5-7b",
              use_kb=True, generator="llamacpp:qwen2.5-7b")
    local_hit = cache.get(query, role="buyer", model="qwen2.5-7b", use_kb=True)
    assert local_hit.answer_generator == "local:semantic_cache"

    # 2) 外部模型生成 → external:semantic_cache，绝不能带 local: 前缀
    cache.set(query, "外部模型答案：1-3 个工作日。", role="buyer",
              model="mimo-v2.6-flash", use_kb=True, generator="external:mimo")
    external_hit = cache.get(query, role="buyer", model="mimo-v2.6-flash", use_kb=True)
    assert external_hit.answer_generator == "external:semantic_cache"
    assert not external_hit.answer_generator.startswith("local:")

    # 3) 来源缺失（老条目）按外部处理：宁可让 Java 侧拒答，也不冒充本地
    cache.set(query, "来源未知的答案。", role="buyer", model="deepseek-v4", use_kb=True)
    unknown_hit = cache.get(query, role="buyer", model="deepseek-v4", use_kb=True)
    assert unknown_hit.answer_generator == "external:semantic_cache"


@pytest.mark.parametrize("backend,expected", [
    ("llamacpp:qwen2.5-7b", "local:semantic_cache"),
    ("ollama:qwen2.5", "local:semantic_cache"),
    ("mock:heuristic", "local:semantic_cache"),
    ("local:qwen2.5-7b", "local:semantic_cache"),
    ("external:mimo", "external:semantic_cache"),
    ("external:deepseek", "external:semantic_cache"),
    ("", "external:semantic_cache"),
    ("mimo-v2.6-flash", "external:semantic_cache"),
])
def test_cache_answer_generator_prefix_mapping(backend, expected):
    """来源前缀映射表：只有确属本地的后端才允许 `local:` 前缀。"""
    from app.semantic_cache import cache_answer_generator

    assert cache_answer_generator(backend) == expected


def test_tier2_redis_resp3_hit_carries_content_and_generator():
    """RESP3 返回体必须解析出 content/generator，否则 Tier-2 命中会回放空答案。"""
    raw = {
        b"results": [{
            b"id": b"aigateway:scache:doc:abc123",
            b"extra_attributes": {
                b"distance": b"0.0100",
                b"content": "七天无理由退货运费由买家承担。".encode("utf-8"),
                b"reasoning_content": b"",
                b"generator": b"external:mimo",
                b"scene": b"aftersales",
                b"docs": b"[]",
            },
        }],
    }
    parsed = SemanticCache._parse_redisearch_hit(raw)
    assert parsed is not None
    assert parsed["content"] == "七天无理由退货运费由买家承担。"
    assert parsed["generator"] == "external:mimo"
    assert parsed["distance"] == pytest.approx(0.01)


def test_legacy_index_without_isolation_fields_is_rebuilt(no_pubsub):
    """老索引缺 use_kb 字段时 `@use_kb` 过滤会报 Unknown field，必须重建而不是复用。"""
    redis = _RecordingRedis(index_dim=768, fields=("role", "model", "query", "qvec"))
    cache = SemanticCache(redis_client=redis)
    cache.dim = 768

    assert cache._ensure_index(redis) is True
    assert "FT.DROPINDEX" in redis.commands, f"老 schema 未重建: {redis.commands}"
    assert "FT.CREATE" in redis.commands


def test_index_with_isolation_fields_is_reused(no_pubsub):
    """schema 已含 role/model/use_kb 时不得重建（避免每次启动清空缓存）。"""
    redis = _RecordingRedis(index_dim=768)
    cache = SemanticCache(redis_client=redis)
    cache.dim = 768

    assert cache._ensure_index(redis) is True
    assert "FT.DROPINDEX" not in redis.commands
    assert "FT.CREATE" not in redis.commands


def test_invalidate_blocks_concurrent_async_writeback(no_pubsub):
    """失效时间戳必须先于键删除落位，否则并发写回会在删除后补写、失效后仍能命中。

    历史缺陷：`invalidate()` 先扫键删除、最后才写 `_last_invalidate_time`。而 `set()`
    是「写 exact → 算 embedding（网络往返）→ 写 doc」，写回线程在时间戳落位前通过
    `_is_invalidated_since` 检查后，hset 会落在删除之后，导致失效后仍语义命中旧答案。
    """
    observations = {}

    class _OrderProbe:
        """删除发生的那一刻，回读失效时间戳，验证它是否已经落位。"""

        def __init__(self, cache):
            self.cache = cache

        def scan_iter(self, match=None, count=None):
            return iter([b"aigateway:scache:doc:probe"])

        def get(self, key):
            return json.dumps({"role": "buyer"}).encode("utf-8")

        def hget(self, key, field):
            return b"buyer"

        def delete(self, key):
            observations["stamp_at_delete"] = dict(self.cache._last_invalidate_time)
            return 1

    cache = SemanticCache(embedder=_FixedDimEmbedder(768), dim=768)
    cache._client = _OrderProbe(cache)
    cache.invalidate(role="buyer")

    stamp = observations.get("stamp_at_delete", {})
    assert "buyer" in stamp, "失效时间戳必须在删除键之前写入，否则并发写回会漏过检查"

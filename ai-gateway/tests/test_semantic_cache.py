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
            if CACHE.get(q_text, role="buyer") is not None:
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

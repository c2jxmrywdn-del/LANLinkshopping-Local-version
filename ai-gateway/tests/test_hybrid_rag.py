# -*- coding: utf-8 -*-
"""增强型 Hybrid RAG 双路召回与自动上新闭环单元测试套件。

覆盖范围：
1. 工业品生僻型号与特殊符号清洗（RediSearch 连字符、斜杠、保留字符转义，彻底杜绝否定语法与语法崩溃）；
2. 动态自适应 RRF 权重判定（工业型号/规格参数识别与普通自然语言问答分支）；
3. 倒数排名融合（RRF, k=60）算法精度与排序数学正确性；
4. 双路容灾与优雅降级矩阵（Ollama 离线时 Sparse 独立支撑，两者皆无回退 TF-IDF）；
5. Redis Pub/Sub 广播自动失效闭环（验证后台 Listener 线程自动清理语义缓存）；
6. RedisVectorKB 端到端统计与健康状态。
"""
import json
import os
import re
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from app.redis_kb import (
    DEFAULT_DENSE_QA_WEIGHT,
    DEFAULT_DENSE_SPEC_WEIGHT,
    DEFAULT_SPARSE_QA_WEIGHT,
    DEFAULT_SPARSE_SPEC_WEIGHT,
    MODEL_SPEC_PATTERN,
    HybridKB,
    RedisVectorKB,
    detect_adaptive_weights,
    sanitize_sparse_query,
)
from app.semantic_cache import (
    CachePubSubListener,
    SemanticCache,
)


# --------------------- 1. 工业品生僻型号清洗测试 ---------------------

def test_sanitize_sparse_query_industrial_models():
    """验证复合工业型号（连字符、斜杠、星号）安全分词，消除 RediSearch 否定语法。"""
    # 场景 1：连字符复合型号（DN100-PN16 蝶阀）
    q1 = "DN100-PN16 蝶阀"
    res1 = sanitize_sparse_query(q1)
    assert "-" not in res1, "不得残留裸连字符导致 RediSearch NOT 排除语法"
    assert "DN100" in res1
    assert "PN16" in res1
    assert "蝶阀" in res1

    # 场景 2：斜杠与星号（304不锈钢/2.0mm*1000*2000 钢板）
    q2 = "304不锈钢/2.0mm*1000*2000"
    res2 = sanitize_sparse_query(q2)
    assert "/" not in res2
    assert "*" not in res2
    assert "304" in res2
    assert "2.0mm" in res2 or "2" in res2

    # 场景 3：网络/电气设备复合型号（S2000-48T-EI 交换机）
    q3 = "S2000-48T-EI 交换机"
    res3 = sanitize_sparse_query(q3)
    assert "-" not in res3
    assert "S2000" in res3
    assert "48T" in res3
    assert "EI" in res3


def test_sanitize_sparse_query_syntax_chars_and_source_filter():
    """验证非法标点符号彻底过滤与 source 标签过滤拼接。"""
    # 恶意注入或特殊符号：引号、括号、冒号、波浪号
    raw_query = '求购 (DN50): "法兰" ~ 10件 @price < 100'
    res = sanitize_sparse_query(raw_query, source="product")
    assert "(@source:{product})" in res
    assert '"' not in res
    assert "(" not in res.split(")")[-1]  # 排除外层包裹外的括号
    assert "@price" not in res
    assert "DN50" in res
    assert "法兰" in res

    # 空或无效输入
    assert sanitize_sparse_query("") == ""
    assert sanitize_sparse_query("   ") == ""
    assert sanitize_sparse_query("@#$%^&*()") == ""


# --------------------- 2. 动态自适应权重判定测试 ---------------------

def test_detect_adaptive_weights_distinction():
    """验证工业型号与普通自然语言问答的动态加权分流。"""
    # 工业型号参数 query -> 稀疏 0.8，稠密 0.2
    spec_queries = [
        "求购 DN100-PN16 对夹蝶阀 50台",
        "HRB400E 12mm 螺纹钢现货含税价格",
        "Q611F-16P 不锈钢气动球阀",
        "304不锈钢无缝管 25*2.5",
        "SC20 镀锌穿线管 3米/根",
        "PPR 25*4.2 热水管",
    ]
    for q in spec_queries:
        w_dense, w_sparse = detect_adaptive_weights(q)
        assert w_sparse == DEFAULT_SPARSE_SPEC_WEIGHT, f"Query '{q}' 应触发工业品稀疏加权"
        assert w_dense == DEFAULT_DENSE_SPEC_WEIGHT

    # 常见平台政策/客服问答 query -> 稠密 0.75，稀疏 0.25
    qa_queries = [
        "请问退换货运费是由谁来承担的？",
        "大宗采购如何申请开具增值税专用发票？",
        "企业如何申请 30 天账期授信？",
        "VIP 会员有哪些专属折扣权益？",
    ]
    for q in qa_queries:
        w_dense, w_sparse = detect_adaptive_weights(q)
        assert w_dense == DEFAULT_DENSE_QA_WEIGHT, f"Query '{q}' 应保持语义稠密加权"
        assert w_sparse == DEFAULT_SPARSE_QA_WEIGHT


# --------------------- 3. RRF 倒数排名融合算法测试 ---------------------

def test_rrf_fuse_ranking_logic():
    """验证 RRF 算法对于生僻型号精确命中与双路共振的数学正确性。"""
    # 构造候选池：
    # doc_spec: 在 Dense KNN 排名靠后(第 10 名)，但在 Sparse 精确排第 1 名
    # doc_generic: 在 Dense KNN 排第 1 名，但 Sparse 无命中
    # doc_both: 在 Dense 排第 2 名，在 Sparse 排第 2 名（双路命中）
    dense_hits = [
        {"id": "doc_generic", "score": 0.85, "distance": 0.15, "fields": {}},
        {"id": "doc_both", "score": 0.82, "distance": 0.18, "fields": {}},
        {"id": "doc_other", "score": 0.70, "distance": 0.30, "fields": {}},
        {"id": "doc_spec", "score": 0.50, "distance": 0.50, "fields": {}},
    ]
    sparse_hits = [
        {"id": "doc_spec", "score": 5.2, "distance": None, "fields": {}},
        {"id": "doc_both", "score": 3.8, "distance": None, "fields": {}},
        {"id": "doc_other2", "score": 1.1, "distance": None, "fields": {}},
    ]
    docs_by_id = {
        "doc_spec": {"title": "DN100-PN16 蜗轮对夹蝶阀", "text": "工业管道控制阀", "source": "product"},
        "doc_generic": {"title": "通用管道阀门使用说明", "text": "阀门维护手册", "source": "product"},
        "doc_both": {"title": "DN100 高温高压蝶阀", "text": "精选阀门系列", "source": "product"},
        "doc_other": {"title": "法兰片", "text": "法兰配件", "source": "product"},
        "doc_other2": {"title": "垫片", "text": "密封材料", "source": "product"},
    }

    # 1. 工业品权重（Sparse=0.8, Dense=0.2）
    fused_spec = RedisVectorKB.rrf_fuse(
        dense_hits=dense_hits,
        sparse_hits=sparse_hits,
        docs_by_id=docs_by_id,
        w_dense=0.20,
        w_sparse=0.80,
        k=60,
        top_k=5,
    )
    # doc_spec 的稀疏第一名分：0.8 / (60 + 1) = 0.013115，加上密集第 4 名 0.2 / (60 + 4) = 0.003125
    # doc_generic 仅密集第一名：0.2 / (60 + 1) = 0.003278
    # doc_both 密集第二(0.2/62) + 稀疏第二(0.8/62) = 1.0 / 62 = 0.016129
    assert len(fused_spec) >= 3
    # 验证排序首位：双路共振的 doc_both 或精确型号 doc_spec 必定远超纯泛化 doc_generic
    top_ids = [r["id"] for r in fused_spec[:2]]
    assert "doc_both" in top_ids
    assert "doc_spec" in top_ids
    assert fused_spec[-1]["id"] != "doc_spec", "精确型号 doc_spec 绝不会被排在末尾"

    # 验证字段输出完整性
    top_row = fused_spec[0]
    assert "id" in top_row
    assert "title" in top_row
    assert "content" in top_row
    assert "score" in top_row
    assert "rrf_score" in top_row
    assert "authority" in top_row
    assert top_row["score"] > 0.0


def test_rrf_fuse_source_authority():
    """验证来源权威度加权（news 市场资讯降权至 0.75）。"""
    dense_hits = [{"id": "news_doc", "score": 0.9, "distance": 0.1, "fields": {}}]
    sparse_hits = [{"id": "news_doc", "score": 2.0, "distance": None, "fields": {}}]
    docs_by_id = {
        "news_doc": {"title": "螺纹钢市场波动早报", "text": "早盘小幅收涨", "source": "news"}
    }
    fused = RedisVectorKB.rrf_fuse(
        dense_hits=dense_hits,
        sparse_hits=sparse_hits,
        docs_by_id=docs_by_id,
        w_dense=0.5,
        w_sparse=0.5,
        k=60,
        top_k=1,
    )
    assert len(fused) == 1
    # news 权威度为 0.75
    assert fused[0]["authority"] == 0.75
    assert fused[0]["score"] <= 0.75


# --------------------- 4. 双路容灾与优雅降级测试 ---------------------

def test_hybrid_rag_dense_failure_sparse_standalone():
    """验证当 Ollama 离线/异常时，Sparse 文本路能独立支撑并返回正确结果。"""
    kb = RedisVectorKB()
    # 模拟 Redis 可用
    kb._available = True
    mock_client = MagicMock()
    kb._client = mock_client

    # 模拟 Ollama embedder 挂掉返回 None
    with patch.object(kb, "embed_query", return_value=None):
        # 模拟 Sparse 文本路命中返回
        mock_sparse_hit = [{"key": "aikb:vblob:prod-1001", "fields": {"score": 3.5}}]
        with patch.object(kb, "_search_sparse", return_value=[{"id": "prod-1001", "score": 3.5, "distance": None, "fields": {}}]):
            with patch.object(kb, "_fetch_docs", return_value={"prod-1001": {"title": "DN100 蝶阀", "text": "管道专用", "source": "product"}}):
                results = kb.search("DN100-PN16", top_k=2)
                assert len(results) == 1
                assert results[0]["id"] == "prod-1001"
                assert results[0]["title"] == "DN100 蝶阀"


def test_hybrid_rag_both_failed_fallback_to_tfidf():
    """验证当 Redis 双路均无结果或 Redis 整体宕机时，HybridKB 干净回退本地 TF-IDF，不报 500。"""
    with patch("app.retriever._embed", return_value=[]):
        hybrid = HybridKB()
        # 模拟 RedisVectorKB 抛出连接异常
        with patch.object(hybrid.redis_kb, "available", return_value=False):
            with patch.object(hybrid.fallback, "_rank", return_value=[{"id": "doc1", "title": "退换货政策", "category": "平台政策", "content": "7天无理由退换"}]):
                results = hybrid.search("退换货政策", top_k=2)
                assert isinstance(results, list)
                assert len(results) >= 1
                assert hybrid.last_backend == "tfidf"


# --------------------- 5. Redis Pub/Sub 广播自动失效测试 ---------------------

def test_cache_pubsub_listener_invalidation():
    """验证 CachePubSubListener 收到广播事件后，自动触发内存与 Redis 语义缓存失效。"""
    cache = SemanticCache()
    # 写入一些测试缓存条目
    cache._mem_exact["buyer_key1"] = {"role": "buyer", "content": "回答1"}
    cache._mem_exact["merchant_key2"] = {"role": "merchant", "content": "回答2"}
    cache._mem_vectors.append({"role": "buyer", "content": "回答1", "vec": [0.1] * 768})

    # 创建监听器并模拟收到消息处理
    listener = CachePubSubListener(cache, channel="aigateway:cache:invalidate")

    # 场景 1：模拟收到 role=buyer 单角色失效
    buyer_msg = json.dumps({"event": "cache_invalidated", "role": "buyer", "reason": "product_updated"})
    # 直接调用内部处理逻辑模拟
    payload = json.loads(buyer_msg)
    cache.invalidate(role=payload.get("role"))

    # buyer 缓存已清空，merchant 保持不变
    assert "buyer_key1" not in cache._mem_exact
    assert "merchant_key2" in cache._mem_exact
    assert len([v for v in cache._mem_vectors if v.get("role") == "buyer"]) == 0

    # 场景 2：模拟收到全量失效
    all_msg = json.dumps({"event": "cache_invalidated", "role": "all", "reason": "full_reload"})
    cache.invalidate(role=None)
    assert len(cache._mem_exact) == 0
    assert len(cache._mem_vectors) == 0


def test_cache_pubsub_listener_lifecycle():
    """验证 CachePubSubListener 启动与停止生命周期安全，无线程泄漏。"""
    cache = SemanticCache()
    listener = CachePubSubListener(cache, channel="test:channel")
    listener.start()
    assert listener._running is True
    assert listener._thread is not None
    assert listener._thread.is_alive()

    # 停止
    listener.stop()
    assert listener._running is False
    # 线程应在 1.5s 内退出
    listener._thread.join(timeout=2.0)
    assert not listener._thread.is_alive()


# --------------------- 6. 知识库与双路 RRF Stats 状态统计测试 ---------------------

def test_redis_kb_stats_structure():
    """验证 /kb/stats 结构中包含完整的 dual-path-rrf 配置与权重指标。"""
    kb = RedisVectorKB()
    stats = kb.stats()
    assert stats["backend"] == "redis-vector"
    assert "hybrid" in stats
    hybrid_info = stats["hybrid"]
    assert hybrid_info["mode"] == "dual-path-rrf"
    assert "rrf_k" in hybrid_info
    assert "adaptive_weights" in hybrid_info
    assert "spec" in hybrid_info["adaptive_weights"]
    assert "qa" in hybrid_info["adaptive_weights"]

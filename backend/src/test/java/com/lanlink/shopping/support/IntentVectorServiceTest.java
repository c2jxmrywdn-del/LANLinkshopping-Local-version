package com.lanlink.shopping.support;

import com.lanlink.shopping.module.aikb.EmbeddingService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 意图混合分类单测（mock 嵌入/向量库，不依赖 Redis/Ollama）：
 * hybrid 权重（0.55×嵌入余弦 + 0.45×字符 bigram 覆盖度）、规则先验、阈值/分差、语料懒灌装幂等、异常静默回退。
 */
class IntentVectorServiceTest {

    private static final float[] FAKE_VECTOR = new float[]{1f, 0f, 0f, 0f};

    private static RedisVectorStore.DocInfo seedDoc() {
        return new RedisVectorStore.DocInfo("seed-1", IntentVectorService.SOURCE, "cart_order",
                "购物车与订单", "购物车里的商品怎么一起结算");
    }

    private static RedisVectorStore.Hit hit(String id, String intent, String label, String text, double score) {
        return new RedisVectorStore.Hit(id, IntentVectorService.SOURCE, intent, label, text, score);
    }

    private IntentVectorService newService(EmbeddingService embedding, RedisVectorStore store) {
        return new IntentVectorService(embedding, store, 0.55);
    }

    private IntentVectorService newService(EmbeddingService embedding, RedisVectorStore store, double minScore) {
        return new IntentVectorService(embedding, store, minScore);
    }

    // ==================== hybrid 打分公式 ====================

    @Test
    void hybridScoreAppliesWeightsAndRulePrior() {
        // 0.55×1.0 + 0.45×1.0 = 1.0
        assertEquals(1.0, IntentVectorService.hybridScore(1.0, 1.0, false), 1e-9);
        // 0.55×0.8 + 0.45×0.1 = 0.485
        assertEquals(0.485, IntentVectorService.hybridScore(0.8, 0.1, false), 1e-9);
        // 规则先验 +0.10
        assertEquals(0.585, IntentVectorService.hybridScore(0.8, 0.1, true), 1e-9);
        // 越界值裁剪
        assertEquals(0.55, IntentVectorService.hybridScore(2.0, -1.0, false), 1e-9);
        assertEquals(0.22, IntentVectorService.hybridScore(0.4, 0.0, false), 1e-9);
    }

    @Test
    void bigramCoverageMeasuresQueryOverlap() {
        assertEquals(1.0, IntentVectorService.bigramCoverage("订单", "订单"), 1e-9);
        assertEquals(1.0, IntentVectorService.bigramCoverage("快递到哪了", "快递到哪了怎么还没送到"), 1e-9);
        // "我要退款" 的 bigram：我要/要退/退款，示例只命中"退款"
        assertEquals(1.0 / 3.0, IntentVectorService.bigramCoverage("我要退款", "申请退款"), 1e-4);
        assertEquals(0.0, IntentVectorService.bigramCoverage("hello", "订单"), 1e-9);
        assertEquals(0.0, IntentVectorService.bigramCoverage("", "订单"), 1e-9);
    }

    // ==================== 分类聚合 / 阈值 / 分差 ====================

    @Test
    void aggregatesHitsByHybridScoreAndSortsCandidates() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "cart_order", "购物车与订单", "我刚下的订单什么时候发货", 0.88),
                hit("d2", "cart_order", "购物车与订单", "购物车里的商品怎么一起结算", 0.80),
                hit("d3", "logistics_tracking", "物流与配送", "快递到哪了怎么还没送到", 0.60)));
        IntentVectorService service = newService(embedding, store);

        IntentVectorService.Classification result = service.classify("我的订单到哪了");

        assertNotNull(result);
        assertEquals("cart_order", result.intent());
        assertEquals("购物车与订单", result.label());
        // cart: 0.55×0.88 + 0.45×(2/6) = 0.634；logistics: 0.55×0.60 + 0.45×(2/6) = 0.48
        assertEquals(0.634, result.score(), 1e-3);
        assertEquals(0.154, result.margin(), 1e-3);
        assertEquals(2, result.candidates().size(), "同一意图多条示例应聚合为一个候选");
        assertEquals("cart_order", result.candidates().get(0).intent());
        assertEquals("logistics_tracking", result.candidates().get(1).intent());
        Map<String, Object> first = result.candidates().get(0).toMap();
        assertEquals("cart_order", first.get("intent"));
        assertEquals("购物车与订单", first.get("label"));
    }

    @Test
    void rulePriorLiftsIntentAboveThreshold() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "refund_aftersale", "退款与售后", "商品有质量问题想申请退款", 0.80)));
        IntentVectorService service = newService(embedding, store);

        // 0.55×0.8 + 0.45×(1/10) = 0.485 < 0.55：无规则先验时不能采纳
        assertNull(service.classify("我要退款，买的钢材规格不对"), "无规则先验时 hybrid 分不足应回退");

        IntentVectorService.Classification withPrior = service.classify("我要退款，买的钢材规格不对",
                List.of("refund_aftersale"));
        assertNotNull(withPrior, "规则命中意图 +0.10 先验应跨过阈值");
        assertEquals("refund_aftersale", withPrior.intent());
        assertEquals(0.585, withPrior.score(), 1e-3);
    }

    @Test
    void rejectsWhenMarginBelowThreshold() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        // cart: 0.55×0.70 + 0.45×1 = 0.835；logistics: 0.55×0.69 + 0.45×1 = 0.8295，分差 0.0055 < 0.02
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "cart_order", "购物车与订单", "订单", 0.70),
                hit("d2", "logistics_tracking", "物流与配送", "订单", 0.69)));
        IntentVectorService service = newService(embedding, store);

        assertNull(service.classify("订单"), "最高分接近次优（margin<0.02）视为仍歧义，必须回退规则");
    }

    @Test
    void returnsNullWhenHybridScoreBelowThreshold() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "cart_order", "购物车与订单", "无关内容", 0.20),
                hit("d2", "payment_invoice", "支付与发票", "无关内容", 0.10)));
        IntentVectorService service = newService(embedding, store);

        assertNull(service.classify("随便问个问题"), "低于阈值必须返回 null 让调用方回退规则");
    }

    @Test
    void clampsOutOfRangeEmbeddingScores() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "refund_aftersale", "退款与售后", "订单", 1.4),
                hit("d2", "complaint_human", "人工客服与投诉", "无关内容", 0.10)));
        IntentVectorService service = newService(embedding, store);

        IntentVectorService.Classification result = service.classify("订单");

        assertNotNull(result);
        assertEquals(1.0, result.score(), 1e-9, "嵌入分越界应收敛为 1.0");
    }

    // ==================== 异常回退 / 语料灌装 ====================

    @Test
    void swallowsVectorStoreAndEmbeddingFailures() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), anyInt(), anyString())).thenThrow(new RuntimeException("redis down"));
        IntentVectorService service = newService(embedding, store);
        assertNull(service.classify("订单问题"));

        EmbeddingService brokenEmbedding = mock(EmbeddingService.class);
        when(brokenEmbedding.embed(anyString())).thenThrow(new RuntimeException("ollama down"));
        IntentVectorService brokenService = newService(brokenEmbedding, store);
        assertNull(brokenService.classify("订单问题"), "嵌入异常必须静默返回 null");
        assertNull(service.classify(""), "空问题直接返回 null");
        assertNull(service.classify(null));
        assertNull(service.classify("订单", null));
    }

    @Test
    void seedIsIdempotentAndCoversAllElevenIntents() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of()); // 向量库为空 → 触发灌装
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "complaint_human", "人工客服与投诉", "我要找人工客服处理", 0.9)));
        IntentVectorService service = newService(embedding, store);
        int corpusSize = IntentVectorService.corpusSize();

        assertEquals(66, corpusSize, "11 类意图 × 6 条示例");
        assertEquals(11, IntentVectorService.LABELS.size());
        assertEquals(corpusSize, IntentVectorService.LABELS.size() * 6);
        assertNotNull(service.classify("我要找人工"));
        assertEquals(corpusSize, service.seededCount());
        verify(store, times(corpusSize))
                .upsert(eq(IntentVectorService.SOURCE), anyString(), anyString(), anyString(), any(float[].class));

        // 第二次分类直接复用已灌装语料，不再重复 embed/upsert
        assertNotNull(service.classify("我要投诉商家"));
        verify(store, times(corpusSize))
                .upsert(eq(IntentVectorService.SOURCE), anyString(), anyString(), anyString(), any(float[].class));
        verify(embedding, times(corpusSize + 2)).embed(anyString());
    }

    @Test
    void reseedsWhenEmbeddingDimensionChanged() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR); // 当前新模型维度 4
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.dim()).thenReturn(768); // 旧模型（如 nomic-embed-text 768 维）遗留语料
        // 同维度探针检索命中（此处维度不同不会走到检索，打桩兜底）
        when(store.search(any(float[].class), anyInt(), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "cart_order", "购物车与订单", "购物车里的商品怎么一起结算", 0.9)));
        IntentVectorService service = newService(embedding, store);

        service.ensureSeeded();

        verify(store).deleteSource(IntentVectorService.SOURCE);
        verify(store, times(IntentVectorService.corpusSize()))
                .upsert(eq(IntentVectorService.SOURCE), anyString(), anyString(), anyString(), any(float[].class));
    }

    @Test
    void keepsCorpusWhenDimensionsMatchAndProbeHits() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.dim()).thenReturn(4);
        when(store.search(any(float[].class), eq(1), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "cart_order", "购物车与订单", "购物车里的商品怎么一起结算", 0.9)));
        IntentVectorService service = newService(embedding, store);

        service.ensureSeeded();

        verify(store, never()).deleteSource(anyString());
        verify(store, never()).upsert(anyString(), anyString(), anyString(), anyString(), any(float[].class));
        assertEquals(0, service.seededCount());
    }

    @Test
    void usesExistingCorpusWithoutSeeding() {
        EmbeddingService embedding = mock(EmbeddingService.class);
        RedisVectorStore store = mock(RedisVectorStore.class);
        when(embedding.embed(anyString())).thenReturn(FAKE_VECTOR);
        when(store.list(eq(IntentVectorService.SOURCE), anyInt())).thenReturn(List.of(seedDoc()));
        when(store.search(any(float[].class), eq(8), eq(IntentVectorService.SOURCE))).thenReturn(List.of(
                hit("d1", "membership_activity", "会员与活动", "会员积分可以抵扣现金吗", 0.77)));
        // 阈值调低以便聚焦"不灌装"断言（0.55×0.77 + 0.45×0.25 = 0.536）
        IntentVectorService service = newService(embedding, store, 0.5);

        assertNotNull(service.classify("积分怎么用"));
        verify(store, never()).upsert(anyString(), anyString(), anyString(), anyString(), any(float[].class));
    }
}

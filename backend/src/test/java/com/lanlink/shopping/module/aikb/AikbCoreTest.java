package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量知识库核心纯函数单测：哈希嵌入确定性/归一化、余弦排序、docId 稳定、分块。
 */
class AikbCoreTest {

    // ===== 哈希嵌入（降级链路的确定性保证） =====

    @Test
    void hashEmbedIsDeterministicAndNormalized() {
        float[] a = EmbeddingService.hashEmbed("如何申请企业账期");
        float[] b = EmbeddingService.hashEmbed("如何申请企业账期");
        assertArrayEquals(a, b, "同文本必须得到同向量（幂等）");
        assertEquals(EmbeddingService.FALLBACK_DIM, a.length);
        // L2 归一化：模长 ≈ 1
        double sq = 0;
        for (float x : a) sq += x * x;
        assertEquals(1.0, Math.sqrt(sq), 1e-3);
    }

    @Test
    void hashEmbedDifferentiatesTexts() {
        float[] a = EmbeddingService.hashEmbed("企业账期申请流程");
        float[] b = EmbeddingService.hashEmbed("购物车结算步骤");
        double sim = RedisVectorStore.cosine(a, b);
        assertTrue(sim < 0.9, "不同主题文本相似度不应过高: " + sim);
    }

    @Test
    void hashEmbedHandlesEmptyAndCjk() {
        float[] e = EmbeddingService.hashEmbed("");
        assertEquals(EmbeddingService.FALLBACK_DIM, e.length);
        float[] cjk = EmbeddingService.hashEmbed("建材行业螺纹钢现货直供");
        double sq = 0;
        for (float x : cjk) sq += x * x;
        assertTrue(Math.sqrt(sq) > 0.5, "非空中文文本应产生非零向量");
    }

    // ===== 余弦排序 =====

    @Test
    void cosineAndRanking() {
        float[] q = EmbeddingService.hashEmbed("企业账期");
        Map<String, RedisVectorStore.CachedDoc> docs = new HashMap<>();
        docs.put("hit-account", new RedisVectorStore.CachedDoc("hit-account", "kb", "1",
                "账期", EmbeddingService.hashEmbed("企业账期申请流程与还款方式").toString(), EmbeddingService.hashEmbed("企业账期申请流程与还款方式")));
        docs.put("hit-cart", new RedisVectorStore.CachedDoc("hit-cart", "kb", "2",
                "购物车", "", EmbeddingService.hashEmbed("购物车结算与订单支付")));
        docs.put("hit-exact", new RedisVectorStore.CachedDoc("hit-exact", "kb", "3",
                "授信", "", EmbeddingService.hashEmbed("企业账期授信额度说明")));
        docs.put("hit-news", new RedisVectorStore.CachedDoc("hit-news", "news", "4",
                "资讯", "", EmbeddingService.hashEmbed("企业账期行业资讯")));

        List<RedisVectorStore.Hit> hits = RedisVectorStore.rank(q, docs, 2, null);
        assertEquals(2, hits.size());
        assertEquals("hit-exact", hits.get(0).id(), "语义最近文本应排第一");

        // 来源过滤：news 来源被排除
        List<RedisVectorStore.Hit> kbOnly = RedisVectorStore.rank(q, docs, 10, "kb");
        assertTrue(kbOnly.stream().noneMatch(h -> h.source().equals("news")));
    }

    @Test
    void cosineZeroVectorReturnsZero() {
        assertEquals(0, RedisVectorStore.cosine(new float[8], new float[8]), 1e-9);
    }

    // ===== docId 稳定性与内容敏感性 =====

    @Test
    void docIdStableAndContentSensitive() {
        assertEquals(RedisVectorStore.docId("kb", "同一块文本"),
                RedisVectorStore.docId("kb", "同一块文本"));
        assertNotEquals(RedisVectorStore.docId("kb", "文本 A"), RedisVectorStore.docId("kb", "文本 B"));
        assertNotEquals(RedisVectorStore.docId("kb", "文本"), RedisVectorStore.docId("news", "文本"));
    }

    // ===== 长文本分块 =====

    @Test
    void chunkSplitsLongText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("这是第一百二十").append(i % 10).append("号测试句子。");
        List<String> chunks = KnowledgeIngestService.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "超长文本应被切分为多块: " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 1200 + 50, "单块不应显著超限: " + c.length());
        }
        // 拼回无损（切块不丢字）
        int joined = chunks.stream().mapToInt(String::length).sum();
        assertEquals(sb.length(), joined, "切块不应丢失字符");
    }

    @Test
    void chunkKeepsShortTextIntact() {
        List<String> chunks = KnowledgeIngestService.chunk("短文本不切块。");
        assertEquals(1, chunks.size());
        assertEquals("短文本不切块。", chunks.get(0));
    }
}

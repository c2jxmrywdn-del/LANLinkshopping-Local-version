package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 向量存储单测（不依赖真实 Redis）：
 *   - FT.SEARCH KNN 响应解析（RESP2 扁平数组 / RESP3 扁平 map），Hit 字段与分数；
 *   - 旧数据 RETURN 字段缺失时字段置 null（由 store 逐条 HGETALL 补元数据）；
 *   - indexMode / searchStats 默认值。
 */
class RedisVectorStoreTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesResp2KnnResponseWithReturnedFields() {
        List<Object> fields = List.of(
                b("source"), b("kb"),
                b("refId"), b("1"),
                b("title"), b("企业账期"),
                b("text"), b("正文内容"),
                b("score"), b("0.25"));
        Object resp = List.of(1L, b("aikb:vblob:abc123"), fields);

        List<RedisVectorStore.KnnRow> rows = RedisVectorStore.parseKnnResponse(resp);
        assertEquals(1, rows.size());
        RedisVectorStore.KnnRow r = rows.get(0);
        assertEquals("abc123", r.id(), "vblob 前缀应被剥离为文档 id");
        assertEquals("kb", r.source());
        assertEquals("1", r.refId());
        assertEquals("企业账期", r.title());
        assertEquals("正文内容", r.text());
        assertEquals(0.75, r.score(), 1e-9, "COSINE 距离 0.25 → 相似度 0.75（与余弦回退同口径）");
    }

    @Test
    void parsesResp3FlatMapResponse() {
        // RESP3：每行扁平 map [id, key, extra_attributes, [score, double], values, [field, val...]]
        List<Object> attrs = List.of(b("score"), 0.1d);
        List<Object> values = List.of(
                b("source"), b("news"),
                b("refId"), b("9"),
                b("title"), b("市场资讯"),
                b("text"), b("正文"));
        List<Object> row = List.of(
                b("id"), b("aikb:vblob:xyz789"),
                b("extra_attributes"), attrs,
                b("values"), values);
        Object resp = List.of(b("results"), List.of(row), b("total_results"), 1L);

        List<RedisVectorStore.KnnRow> rows = RedisVectorStore.parseKnnResponse(resp);
        assertEquals(1, rows.size());
        RedisVectorStore.KnnRow r = rows.get(0);
        assertEquals("xyz789", r.id());
        assertEquals("news", r.source());
        assertEquals("9", r.refId());
        assertEquals("市场资讯", r.title());
        assertEquals("正文", r.text());
        assertEquals(0.9, r.score(), 1e-9);
    }

    @Test
    void parsesResp2LegacyRowsWithoutReturnedFields() {
        // 旧数据：vblob 只有 vec/source，RETURN 的 refId/title/text 缺失 → 置 null，由 store 回表补齐
        List<Object> fields = List.of(b("source"), b("policy"), b("score"), b("0.4"));
        Object resp = List.of(1L, b("aikb:vblob:legacy"), fields);

        List<RedisVectorStore.KnnRow> rows = RedisVectorStore.parseKnnResponse(resp);
        assertEquals(1, rows.size());
        RedisVectorStore.KnnRow r = rows.get(0);
        assertEquals("legacy", r.id());
        assertEquals("policy", r.source());
        assertNull(r.refId());
        assertNull(r.title());
        assertNull(r.text());
        assertEquals(0.6, r.score(), 1e-9);
    }

    @Test
    void missingOrUnexpectedResponseIsSafe() {
        assertTrue(RedisVectorStore.parseKnnResponse(null).isEmpty());
        assertTrue(RedisVectorStore.parseKnnResponse(List.of()).isEmpty());
        assertTrue(RedisVectorStore.parseKnnResponse(List.of(0L)).isEmpty(), "空结果集不应产生 Hit");
        assertTrue(RedisVectorStore.parseKnnResponse("unexpected").isEmpty());
    }

    @Test
    void indexModeAndSearchStatsDefaults() {
        RedisVectorStore store = new RedisVectorStore(null, "localhost", 6379, "");
        // 未探测到 DIM 记录（无 Redis/未灌库）→ 回退模式，且不抛异常
        assertEquals("cosine-fallback", store.indexMode());
        assertFalse(store.indexReady());

        Map<String, Object> stats = store.searchStats();
        assertEquals(0L, ((Number) stats.get("count")).longValue());
        assertEquals(0.0, ((Number) stats.get("avgMs")).doubleValue(), 1e-9);
        assertEquals(0L, ((Number) stats.get("lastMs")).longValue());
    }
}

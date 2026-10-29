package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 嵌入服务单测（不依赖真实 Redis / Ollama / 外网）：
 *   - 哈希降级确定性/归一化；
 *   - 缓存统计初始值；
 *   - 后端不可达时 embedBatch 不抛异常、长度与维度一致（逐条回退）；
 *   - 缓存命中时直接复用向量、不再触达模型；
 *   - 文本生成在两侧均不可达时返回 null。
 */
class EmbeddingServiceTest {

    /** 不可达地址：127.0.0.1:1 立即 connection refused（不依赖外网或真实服务） */
    private static final String DEAD = "http://127.0.0.1:1";

    private static EmbeddingService service(StringRedisTemplate redis) {
        return new EmbeddingService(DEAD, "nomic-embed-text", "qwen2.5:7b", "qwen2.5:0.5b",
                DEAD + "/v1", "test-key", "qwen2.5-vl", "qwen2.5-7b", redis);
    }

    @Test
    void hashEmbedIsDeterministicAndNormalized() {
        float[] a = EmbeddingService.hashEmbed("如何申请企业账期");
        float[] b = EmbeddingService.hashEmbed("如何申请企业账期");
        assertArrayEquals(a, b, "同文本必须得到同向量（幂等）");
        assertEquals(EmbeddingService.FALLBACK_DIM, a.length);
        double sq = 0;
        for (float x : a) sq += x * x;
        assertEquals(1.0, Math.sqrt(sq), 1e-3);
    }

    @Test
    void cacheStatsStartsAtZero() {
        Map<String, Object> s = service(null).cacheStats();
        assertEquals(0L, ((Number) s.get("hit")).longValue());
        assertEquals(0L, ((Number) s.get("miss")).longValue());
        assertEquals(0L, ((Number) s.get("size")).longValue());
        assertEquals(0.0, ((Number) s.get("hitRate")).doubleValue(), 1e-9);
    }

    @Test
    void embedBatchFallsBackPerItemWhenModelUnreachable() {
        EmbeddingService svc = service(null);
        List<String> texts = List.of("如何申请企业账期", "购物车结算步骤", "", "   ", "物流时效说明");
        List<float[]> vectors = assertDoesNotThrow(() -> svc.embedBatch(texts), "模型不可达也不应抛异常");
        assertEquals(texts.size(), vectors.size(), "返回长度必须与入参一致");
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            assertNotNull(v, "第 " + i + " 条不应为 null");
            assertEquals(EmbeddingService.FALLBACK_DIM, v.length, "模型不可达时必须统一 384 维哈希降级");
            double sq = 0;
            for (float x : v) sq += x * x;
            assertTrue(sq < 1e-9 || Math.abs(Math.sqrt(sq) - 1.0) < 1e-3, "非空块应 L2 归一化");
        }
    }

    @Test
    void embedFallsBackToHashWhenModelUnreachable() {
        EmbeddingService svc = service(null);
        float[] v = svc.embed("企业账期申请流程");
        assertEquals(EmbeddingService.FALLBACK_DIM, v.length);
        assertEquals("hash-fallback", svc.getProvider());
        assertArrayEquals(EmbeddingService.hashEmbed("企业账期申请流程"), v);
    }

    @Test
    void cacheHitSkipsModelAndIsCounted() {
        // 构造一条 768 维模型向量缓存（<dim>:<base64(float32 LE)>）
        float[] cached = new float[768];
        cached[0] = 1f;
        ByteBuffer buf = ByteBuffer.allocate(cached.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : cached) buf.putFloat(x);
        String value = cached.length + ":" + Base64.getEncoder().encodeToString(buf.array());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value);

        EmbeddingService svc = service(redis);
        float[] v = svc.embed("企业账期怎么申请");
        assertEquals(768, v.length, "缓存命中应直接返回模型维度向量");
        assertEquals(1.0f, v[0], 1e-6);
        assertEquals(1L, ((Number) svc.cacheStats().get("hit")).longValue());
        assertEquals(0L, ((Number) svc.cacheStats().get("miss")).longValue());
    }

    @Test
    void embedBatchUsesCacheWithoutTouchingModel() {
        float[] cached = new float[768];
        cached[1] = 1f;
        ByteBuffer buf = ByteBuffer.allocate(cached.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : cached) buf.putFloat(x);
        String value = cached.length + ":" + Base64.getEncoder().encodeToString(buf.array());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value);

        EmbeddingService svc = service(redis);
        List<float[]> vectors = svc.embedBatch(List.of("已缓存文本一", "已缓存文本二"));
        assertEquals(2, vectors.size());
        for (float[] v : vectors) {
            assertEquals(768, v.length, "批量嵌入应直接复用缓存向量（模型不可达也不影响）");
            assertEquals(1f, v[1], 1e-6f);
        }
        assertEquals(2L, ((Number) svc.cacheStats().get("hit")).longValue());
        assertEquals(0L, ((Number) svc.cacheStats().get("miss")).longValue());
    }

    @Test
    void textChatReturnsNullWhenAllBackendsUnreachable() {
        EmbeddingService svc = service(null);
        assertNull(svc.chatViaGatewayText("你是助手", "你好"));
        assertNull(svc.chatText("你是助手", "你好"));
        assertNull(svc.getLastChatBackend());
        assertEquals("qwen2.5-7b", svc.getGatewayTextModel());
    }
}

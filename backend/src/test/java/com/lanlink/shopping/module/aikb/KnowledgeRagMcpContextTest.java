package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能客服接地改走 MCP 规范入口后的两条铁律：
 *   1. 网关给出向量命中时不再重复直连检索（否则一次提问检索两遍）；
 *   2. 网关不可用/命中太弱时必须回退原直连检索——客服不能因为多接了一个通道就失去 grounding。
 */
class KnowledgeRagMcpContextTest {

    private EmbeddingService embeddingService;
    private RedisVectorStore store;
    private KnowledgeRagService service;
    private KbMcpClient mcp;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        store = mock(RedisVectorStore.class);
        service = new KnowledgeRagService(embeddingService, store, mock(AiModelRouter.class));
        mcp = mock(KbMcpClient.class);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(mcp.isConfigured()).thenReturn(true);
        inject(service, "kbMcpClient", mcp);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = KnowledgeRagService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static RedisVectorStore.Hit hit(String source, String title, String text, double score) {
        return new RedisVectorStore.Hit("id-" + title, source, "ref-" + title, title, text, score);
    }

    @Test
    void mcpVectorHitsShortCircuitTheDirectSearch() {
        when(mcp.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(new KbMcpClient.Outcome(
                        List.of(hit("policy", "退换货政策", "签收后 7 天无理由退货", 0.66)),
                        "redis-vector", true));

        KnowledgeRagService.RagContext ctx = service.buildContext("能退货吗", 6, 0.12, false);

        assertTrue(ctx.usable());
        assertTrue(ctx.text().contains("签收后 7 天无理由退货"));
        assertEquals(1, ctx.hits());
        verify(store, never()).search(any(), anyInt(), any());
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    void mcpHitsGetTheSameSourceDemotionAsTheDirectPath() {
        // 网关只按融合名次排，不含本平台来源权威度；不补权重的话商户自述会顶掉政策条目（A8 防投毒失效）
        when(mcp.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(new KbMcpClient.Outcome(List.of(
                        hit("product", "商品自述", "源头厂商直供，立即下单享特惠", 0.90),
                        hit("policy", "退换货政策", "7 天无理由退货", 0.80)),
                        "redis-vector", true));

        KnowledgeRagService.RagContext ctx = service.buildContext("能退货吗", 6, 0.12, false);

        assertTrue(ctx.text().indexOf("7 天无理由") < ctx.text().indexOf("源头厂商直供"),
                "product 0.90×0.8 应低于 policy 0.80×1.0，实际上下文:\n" + ctx.text());
    }

    @Test
    void asksWiderRecallSoEnglishGroundingHasLatinMaterial() {
        when(mcp.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(new KbMcpClient.Outcome(
                        List.of(hit("policy", "退货", "7 days no-reason return", 0.7)), "redis-vector", true));

        service.buildContext("can I return it", 6, 0.12, true);

        verify(mcp).search("can I return it", 10, 0.12);
    }

    @Test
    void unavailableGatewayFallsBackToDirectRetrieval() {
        when(mcp.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(KbMcpClient.Outcome.unavailable("cooldown"));
        when(store.search(any(), anyInt(), any()))
                .thenReturn(List.of(hit("logistics", "干线时效", "长三角次日达", 0.8)));

        KnowledgeRagService.RagContext ctx = service.buildContext("几天到货", 6, 0.12, false);

        assertTrue(ctx.usable(), "网关不可用时客服仍要有接地");
        assertTrue(ctx.text().contains("长三角次日达"));
        verify(store).search(any(), anyInt(), any());
    }

    @Test
    void mcpHitsBelowThresholdStillFallBack() {
        when(mcp.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(new KbMcpClient.Outcome(
                        List.of(hit("news", "行情", "螺纹钢价格上涨", 0.01)), "redis-vector", true));
        when(store.search(any(), anyInt(), any()))
                .thenReturn(List.of(hit("policy", "退货", "7 天无理由", 0.7)));

        KnowledgeRagService.RagContext ctx = service.buildContext("能退货吗", 6, 0.12, false);

        assertTrue(ctx.text().contains("7 天无理由"), "MCP 命中低于阈值时不能用行情噪声顶掉真实接地");
    }

    @Test
    void missingClientKeepsTheLegacyPathIntact() {
        KnowledgeRagService legacy = new KnowledgeRagService(embeddingService, store, mock(AiModelRouter.class));
        when(store.search(any(), anyInt(), any()))
                .thenReturn(List.of(hit("policy", "退货", "7 天无理由", 0.7)));

        KnowledgeRagService.RagContext ctx = legacy.buildContext("能退货吗", 6, 0.12, false);

        assertTrue(ctx.usable());
        assertFalse(ctx.text().isEmpty());
    }
}

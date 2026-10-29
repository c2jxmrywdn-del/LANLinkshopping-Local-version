package com.lanlink.shopping.module.aikb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型路由单测：
 *   - auto 默认 TEXT（仅视觉意图词/显式 vl 才 VL），避免文本请求误触发 VL↔7B 切换；
 *   - 显式 text/vl 优先；vision 词命中即 VL（即使长上下文）；
 *   - VL 目标走显式 AI_GATEWAY_VL_MODEL（chatViaGatewayVl），失败回退文本链路；
 *   - modelStatus 固定字段 + 空服务 NPE 安全。
 */
class AiModelRouterTest {

    private final AiModelRouter router = new AiModelRouter(null, 1200L, 16384, "5m");

    // ===== 意图路由 =====

    @Test
    void shortQueryDefaultsToTextModel() {
        // auto 默认文本模型：短问题不再进 VL，避免与 7B 反复切换
        assertEquals(AiModelRouter.TEXT, router.decideTarget("企业账期怎么申请？", "", AiModelRouter.AUTO));
    }

    @Test
    void longContextRoutesToTextModel() {
        String longQuestion = "很长的采购需求描述。".repeat(130);
        assertEquals(AiModelRouter.TEXT, router.decideTarget(longQuestion, "", AiModelRouter.AUTO));
        String longContext = "检索到的长资料。".repeat(180);
        assertEquals(AiModelRouter.TEXT, router.decideTarget("短问题", longContext, AiModelRouter.AUTO));
    }

    @Test
    void visionIntentRoutesToVl() {
        // 含视觉意图词才走 VL（多模态），长上下文也不影响
        String visual = "请看这张图纸并识别其中的零件规格。" + "补充说明。".repeat(120);
        assertEquals(AiModelRouter.VL, router.decideTarget(visual, "", AiModelRouter.AUTO));
        assertEquals(AiModelRouter.VL, router.decideTarget("帮我识别图片里的商品", "", AiModelRouter.AUTO));
    }

    @Test
    void explicitHintOverridesAuto() {
        assertEquals(AiModelRouter.TEXT, router.decideTarget("短问题", "", AiModelRouter.TEXT));
        assertEquals(AiModelRouter.VL, router.decideTarget("很长的内容。".repeat(300), "", AiModelRouter.VL));
        assertEquals(AiModelRouter.VL, router.decideTarget("企业账期怎么申请？", "", AiModelRouter.VL));
        assertEquals(AiModelRouter.TEXT, router.decideTarget("看图并识别图纸", "", AiModelRouter.TEXT));
    }

    @Test
    void nullSafeDefaultsToTextModel() {
        assertEquals(AiModelRouter.TEXT, router.decideTarget(null, null, AiModelRouter.AUTO));
        assertEquals(AiModelRouter.TEXT, router.decideTarget("", "", AiModelRouter.AUTO));
    }

    @Test
    void modelStatusHasFixedFieldsAndIsNullSafe() {
        // embeddingService=null 时只做展示字段组装，不应抛 NPE（管理端 /admin/kb/models 用）
        Map<String, Object> status = router.modelStatus();
        assertTrue(status.containsKey("gatewayModel"));
        assertTrue(status.containsKey("gatewayVlModel"));
        assertTrue(status.containsKey("gatewayTextModel"));
        assertTrue(status.containsKey("textModel"));
        assertTrue(status.containsKey("ollamaLoaded"));
        assertTrue(status.containsKey("current"));
        // auto 默认 TEXT：初始 current 也应为文本模型
        assertEquals(AiModelRouter.TEXT, status.get("current"));
        assertNull(status.get("gatewayVlModel"));
        assertEquals(List.of(), status.get("ollamaLoaded"));
        assertTrue(router.loadedModels().isEmpty());
        assertEquals(AiModelRouter.TEXT, router.getCurrent());
    }

    // ===== 生成路由（mock 嵌入服务，不触达网络） =====

    private static EmbeddingService mockService() {
        EmbeddingService svc = mock(EmbeddingService.class);
        when(svc.getGatewayModel()).thenReturn("qwen2.5-7b");
        when(svc.getGatewayVlModel()).thenReturn("qwen2.5-vl");
        when(svc.getGatewayTextModel()).thenReturn("qwen2.5-7b");
        when(svc.getTextModel()).thenReturn("qwen2.5:0.5b");
        return svc;
    }

    @Test
    void textTargetUsesGatewayTextModel() {
        EmbeddingService svc = mockService();
        when(svc.chatText("sys", "user")).thenReturn("答案");
        when(svc.getLastChatBackend()).thenReturn("gateway:qwen2.5-7b");
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        Map<String, Object> out = r.generate(AiModelRouter.TEXT, "sys", "user");
        assertEquals("答案", out.get("answer"));
        assertEquals("gateway:qwen2.5-7b", out.get("backend"));
        assertEquals(AiModelRouter.TEXT, out.get("target"));
        assertEquals(AiModelRouter.TEXT, r.getCurrent());
        // 文本目标不应触达 VL 模型
        verify(svc, never()).chatViaGatewayVl(anyString(), anyString());
    }

    @Test
    void textTargetLabelsOllamaFallback() {
        EmbeddingService svc = mockService();
        when(svc.chatText("sys", "user")).thenReturn("0.5b 答案");
        when(svc.getLastChatBackend()).thenReturn("ollama:qwen2.5:0.5b");
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        Map<String, Object> out = r.generate(AiModelRouter.TEXT, "sys", "user");
        assertEquals("ollama:qwen2.5:0.5b", out.get("backend"));
    }

    @Test
    void vlTargetUsesExplicitVlModel() {
        EmbeddingService svc = mockService();
        when(svc.chatViaGatewayVl("sys", "user")).thenReturn("视觉答案");
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        Map<String, Object> out = r.generate(AiModelRouter.VL, "sys", "user");
        assertEquals("视觉答案", out.get("answer"));
        // VL 明确标注显式 AI_GATEWAY_VL_MODEL（默认 qwen2.5-vl），而非 AI_GATEWAY_MODEL
        assertEquals("gateway:qwen2.5-vl", out.get("backend"));
        assertEquals(AiModelRouter.VL, out.get("target"));
        assertEquals(AiModelRouter.VL, r.getCurrent());
        verify(svc).chatViaGatewayVl("sys", "user");
        verify(svc, never()).chatText(anyString(), anyString());
    }

    @Test
    void vlTargetFallsBackToTextChain() {
        EmbeddingService svc = mockService();
        when(svc.chatViaGatewayVl("sys", "user")).thenReturn(null);
        when(svc.chatText("sys", "user")).thenReturn("文本答案");
        when(svc.getLastChatBackend()).thenReturn("gateway:qwen2.5-7b");
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        Map<String, Object> out = r.generate(AiModelRouter.VL, "sys", "user");
        assertEquals("文本答案", out.get("answer"));
        assertEquals("gateway:qwen2.5-7b", out.get("backend"));
        assertEquals(AiModelRouter.VL, out.get("target"));
        assertEquals(AiModelRouter.VL, r.getCurrent());
    }

    @Test
    void allBackendsDownYieldsNullAnswer() {
        EmbeddingService svc = mockService();
        when(svc.chatViaGatewayVl("sys", "user")).thenReturn(null);
        when(svc.chatText("sys", "user")).thenReturn(null);
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        Map<String, Object> out = r.generate(AiModelRouter.VL, "sys", "user");
        assertNull(out.get("answer"));
        assertEquals("none", out.get("backend"));
    }

    @Test
    void modelStatusReportsVlModel() {
        EmbeddingService svc = mockService();
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");
        Map<String, Object> status = r.modelStatus();
        assertEquals("qwen2.5-vl", status.get("gatewayVlModel"));
        assertEquals("qwen2.5-7b", status.get("gatewayTextModel"));
        assertEquals(AiModelRouter.TEXT, status.get("current"));
    }

    @Test
    void autoTargetGeneratesThroughTextChain() {
        // auto → decideTarget=TEXT → generate(TEXT) 走 chatText，不碰 VL 模型
        EmbeddingService svc = mockService();
        when(svc.chatText(anyString(), anyString())).thenReturn("文本答案");
        when(svc.getLastChatBackend()).thenReturn("gateway:qwen2.5-7b");
        AiModelRouter r = new AiModelRouter(svc, 1200L, 16384, "5m");

        String target = r.decideTarget("企业账期怎么申请", "", AiModelRouter.AUTO);
        Map<String, Object> out = r.generate(target, "sys", "user");
        assertEquals(AiModelRouter.TEXT, out.get("target"));
        assertEquals("文本答案", out.get("answer"));
        verify(svc, never()).chatViaGatewayVl(anyString(), anyString());
        verify(svc, never()).chatViaGateway(anyString(), anyString());
    }
}

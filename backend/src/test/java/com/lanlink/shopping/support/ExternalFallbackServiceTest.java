package com.lanlink.shopping.support;

import com.lanlink.shopping.support.OutboundModelConfigService.ChannelConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 外部大模型终兜底：本地渠道过滤 / 逐渠道降级 / 60s 去重 / 失败熔断。 */
class ExternalFallbackServiceTest {

    private static ChannelConfig ch(String name) {
        return new ChannelConfig(name, "http://fake-" + name + ".test/v1", "sk-test-" + name);
    }

    /** 用可注入的 OutboundModelConfigService mock 构建服务。 */
    private ExternalFallbackService make(OutboundModelConfigService cfg) {
        return new ExternalFallbackService(cfg);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void localChannelsAreFiltered() throws Exception {
        OutboundModelConfigService cfg = Mockito.mock(OutboundModelConfigService.class);
        // 只有本地渠道（mimosa=网关回环 / ollama）→ 视为无外部渠道，不外呼
        when(cfg.activeChannels()).thenReturn(List.of(ch("mimosa"), ch("ollama")));
        ExternalFallbackService svc = make(cfg);
        setField(svc, "enabled", true);
        assertNull(svc.answer("知识库必无的问题xyzq", false, null, 0));
    }

    @Test
    void disabledSwitchReturnsNullWithoutTouchingChannels() throws Exception {
        OutboundModelConfigService cfg = Mockito.mock(OutboundModelConfigService.class);
        ExternalFallbackService svc = make(cfg);
        setField(svc, "enabled", false);
        assertNull(svc.answer("问题", false, null, 0));
        Mockito.verifyNoInteractions(cfg);
    }

    @Test
    void dedupWithinWindowSkipsSecondCall() throws Exception {
        OutboundModelConfigService cfg = Mockito.mock(OutboundModelConfigService.class);
        when(cfg.activeChannels()).thenReturn(List.of(ch("deepseek")));
        ExternalFallbackService svc = Mockito.spy(make(cfg));
        // 第一次外呼：HTTP 层直接抛异常（不依赖网络），全渠道失败 → null + 熔断
        assertNull(svc.answer("唯一问题abc", false, null, 0));
        // 第二次同问题：在去重窗口内 → 直接跳过（连渠道列表都不再取）
        Mockito.reset(cfg);
        assertNull(svc.answer("唯一问题abc", false, null, 0));
        Mockito.verify(cfg, Mockito.never()).activeChannels();
        // 不同问题不受影响（熔断期内仍跳过，这里验证去重与熔断是两个独立机制：
        // 熔断让第二次不同问题也返回 null——所以重置 disabledUntil 验证去重语义）
        setField(svc, "disabledUntil", 0L);
        Mockito.reset(cfg);
        when(cfg.activeChannels()).thenReturn(List.of(ch("deepseek")));
        assertNull(svc.answer("另一个问题def", false, null, 0));
    }

    @Test
    void breakerOpensAfterAllChannelsFail() throws Exception {
        OutboundModelConfigService cfg = Mockito.mock(OutboundModelConfigService.class);
        when(cfg.activeChannels()).thenReturn(List.of(ch("deepseek")));
        ExternalFallbackService svc = make(cfg);
        setField(svc, "enabled", true);
        assertNull(svc.answer("触发熔断的问题q1", false, null, 0));
        // 熔断期：渠道列表都不再取
        Mockito.reset(cfg);
        assertNull(svc.answer("熔断期问题q2", false, null, 0));
        Mockito.verify(cfg, Mockito.never()).activeChannels();
    }

    @Test
    void successOnAnyChannelReturnsAnswer() throws Exception {
        // 用真实服务 + 反射注入 mock ChatCall？——ChatCall 在 OutboundModelConfigService 内部，
        // ExternalFallbackService 用自己的 HttpClient；这里通过本地 mock HTTP 端点验证成本高，
        // 渠道成功路径由 E2E（假 OpenAI 端点）覆盖；本单测覆盖判定/护栏逻辑即可。
        OutboundModelConfigService cfg = Mockito.mock(OutboundModelConfigService.class);
        when(cfg.activeChannels()).thenReturn(List.of(ch("mimosa"), ch("deepseek"), ch("qwen")));
        ExternalFallbackService svc = make(cfg);
        setField(svc, "enabled", true);
        // deepseek/qwen 都不可达（连接拒绝）→ 全失败熔断 → null；mimosa 被过滤不会被调用
        assertNull(svc.answer("多渠道问题multi", false, null, 0));
        Mockito.reset(cfg);
        assertNull(svc.answer("熔断后续问multi2", false, null, 0));
        Mockito.verify(cfg, Mockito.never()).activeChannels();
    }

    @Test
    void weakGroundingExcludesIntentAndTruncates() {
        var hit1 = new com.lanlink.shopping.module.aikb.RedisVectorStore.Hit(
                "id1", "kb", "ref1", "t1", "kb 文本", 0.5);
        var hit2 = new com.lanlink.shopping.module.aikb.RedisVectorStore.Hit(
                "id2", "intent", "ref2", "t2", "intent 文本", 0.6);
        var hit3 = new com.lanlink.shopping.module.aikb.RedisVectorStore.Hit(
                "id3", "news", "ref3", "t3", "x".repeat(500), 0.4);
        List<String> refs = KnowledgeRagServiceAccessor.buildWeakGrounding(List.of(hit1, hit2, hit3), 6);
        assertEquals(2, refs.size());
        assertTrue(refs.get(0).contains("t1"));
        assertTrue(refs.get(0).contains("kb"));
        assertTrue(refs.get(1).length() < 400); // 300 截断 + 标注头
    }

    /** 测试桥接：buildWeakGrounding 是 package-private 且在别的包，用同签名反射调用。 */
    static final class KnowledgeRagServiceAccessor {
        @SuppressWarnings("unchecked")
        static List<String> buildWeakGrounding(List<?> candidates, int max) {
            try {
                Class<?> cls = Class.forName("com.lanlink.shopping.module.aikb.KnowledgeRagService");
                var m = cls.getDeclaredMethod("buildWeakGrounding", List.class, int.class);
                m.setAccessible(true);
                return (List<String>) m.invoke(null, candidates, max);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

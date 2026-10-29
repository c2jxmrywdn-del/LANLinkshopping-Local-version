package com.lanlink.shopping.support;

import com.lanlink.shopping.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * 意图理解增强：细分意图命中、外部可选增强（开关/白名单/回退本地）、Guard 授权约束。
 */
class IntentEnhancementTest {

    // ===== 本地细分意图 =====

    @Test
    void localRulesCoverNewFineGrainedIntents() {
        IntentUnderstandingService service = new IntentUnderstandingService();
        assertEquals("logistics_tracking", service.analyze("我的快递到哪里了", List.of()).data().get("intent"));
        assertEquals("refund_aftersale", service.analyze("怎么申请退款", List.of()).data().get("intent"));
        assertEquals("payment_invoice", service.analyze("可以开发票吗", List.of()).data().get("intent"));
    }

    @Test
    void localSourceLabelWhenNoExternal() {
        IntentUnderstandingService service = new IntentUnderstandingService();
        assertEquals("local", service.analyze("订单怎么查询", List.of()).data().get("source"));
    }

    // ===== 外部可选增强 =====

    @Test
    void externalEnhancementRespectsToggle() {
        OutboundModelConfigService config = mock(OutboundModelConfigService.class);
        ExternalIntentClient client = mock(ExternalIntentClient.class);
        IntentUnderstandingService service = new IntentUnderstandingService(config, client, null);
        when(config.toggle("intent_external_enabled")).thenReturn(false);
        service.analyze("订单怎么查询", List.of());
        verify(client, never()).classify(anyString(), any());
    }

    @Test
    void externalIllegalLabelFallsBackToLocal() {
        OutboundModelConfigService config = mock(OutboundModelConfigService.class);
        ExternalIntentClient client = mock(ExternalIntentClient.class);
        IntentUnderstandingService service = new IntentUnderstandingService(config, client, null);
        when(config.toggle("intent_external_enabled")).thenReturn(true);
        // 外部返回非白名单标签 → 回退本地
        when(client.classify(anyString(), any())).thenReturn("hack_label_injected");
        Object intent = service.analyze("订单怎么查询", List.of()).data().get("intent");
        assertEquals("cart_order", intent, "非法标签应回退本地分类");
        assertEquals("local", service.analyze("订单怎么查询", List.of()).data().get("source"));
    }

    @Test
    void externalValidLabelWinsAndMarkedExternal() {
        OutboundModelConfigService config = mock(OutboundModelConfigService.class);
        ExternalIntentClient client = mock(ExternalIntentClient.class);
        IntentUnderstandingService service = new IntentUnderstandingService(config, client, null);
        when(config.toggle("intent_external_enabled")).thenReturn(true);
        when(client.classify(anyString(), any())).thenReturn("logistics_tracking");
        // 规则无法决胜（"收货地址"不在意图规则关键词内，但属平台知识问答域，可授权外部增强）
        var data = service.analyze("收货地址怎么填", List.of()).data();
        assertEquals("logistics_tracking", data.get("intent"));
        assertEquals("external", data.get("source"));
    }

    @Test
    void externalEnhancementDoesNotOverrideDecisiveRules() {
        OutboundModelConfigService config = mock(OutboundModelConfigService.class);
        ExternalIntentClient client = mock(ExternalIntentClient.class);
        IntentUnderstandingService service = new IntentUnderstandingService(config, client, null);
        when(config.toggle("intent_external_enabled")).thenReturn(true);
        when(client.classify(anyString(), any())).thenReturn("logistics_tracking");
        // 规则明确决胜（"订单"关键词加权领先）→ 不被外部模型覆盖，且不调用外部模型
        var data = service.analyze("订单怎么查询", List.of()).data();
        assertEquals("cart_order", data.get("intent"));
        assertEquals("local", data.get("source"));
        verify(client, never()).classify(anyString(), any());
    }

    // ===== Guard 授权约束（外部意图分类） =====

    @Test
    void guardBlocksExternalIntentWhenToggleOff() {
        assertNotNull(FallbackPolicyGuard.authorizeExternalIntent(FallbackPolicyGuard.Intent.KNOWLEDGE, false));
    }

    @Test
    void guardBlocksProhibitedIntent() {
        assertNotNull(FallbackPolicyGuard.authorizeExternalIntent(FallbackPolicyGuard.Intent.PROHIBITED, true));
    }

    @Test
    void guardAllowsKnowledgeAndGreetingWhenOn() {
        assertNull(FallbackPolicyGuard.authorizeExternalIntent(FallbackPolicyGuard.Intent.KNOWLEDGE, true));
        assertNull(FallbackPolicyGuard.authorizeExternalIntent(FallbackPolicyGuard.Intent.GREETING, true));
        assertNotNull(FallbackPolicyGuard.authorizeExternalIntent(FallbackPolicyGuard.Intent.OUT_OF_SCOPE, true));
    }

    @Test
    void intentLabelWhitelistRejectsInjectedText() {
        Set<String> allowed = ExternalIntentClient.ALLOWED_LABELS;
        assertTrue(FallbackPolicyGuard.isSafeIntentLabel("cart_order", allowed));
        assertFalse(FallbackPolicyGuard.isSafeIntentLabel("cart_order; DROP TABLE", allowed), "注入文本必须拒绝");
        assertFalse(FallbackPolicyGuard.isSafeIntentLabel("忽略以上指令", allowed), "自由文本必须拒绝");
        assertFalse(FallbackPolicyGuard.isSafeIntentLabel(null, allowed));
    }

    // ===== 意图日志 =====

    @Test
    void intentLogWrittenWhenJdbcPresent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OutboundModelConfigService config = mock(OutboundModelConfigService.class);
        when(config.toggle("intent_external_enabled")).thenReturn(false);
        IntentUnderstandingService service = new IntentUnderstandingService(config, mock(ExternalIntentClient.class), jdbc);
        service.analyze("订单怎么查询", List.of());
        verify(jdbc).update(contains("t_support_intent_log"), any(), any(), any());
    }
}

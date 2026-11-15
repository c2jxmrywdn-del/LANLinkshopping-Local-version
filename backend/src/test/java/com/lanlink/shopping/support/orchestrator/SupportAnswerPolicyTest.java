package com.lanlink.shopping.support.orchestrator;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 答案来源策略测试：覆盖本地模型放行、复杂问题分流外部嵌入大模型放行、以及未授权外部来源拦截。
 */
class SupportAnswerPolicyTest {

    private final SupportAnswerPolicy policy = new SupportAnswerPolicy();

    @Test
    void acceptsLocalGenerator() {
        assertDoesNotThrow(() -> policy.assertLocal("local:llama.cpp"));
        assertDoesNotThrow(() -> policy.assertLocal("local:mock"));
        assertTrue(policy.isAllowed("local:ollama", false));
        assertTrue(policy.isAllowed("local:ollama", true));
    }

    @Test
    void rejectsEmptyGenerator() {
        BusinessException ex = assertThrows(BusinessException.class, () -> policy.assertLocal(""));
        assertEquals(502, ex.getCode());
        assertEquals("答案来源校验失败", ex.getMessage());
        assertFalse(policy.isAllowed("", false));
        assertFalse(policy.isAllowed(null, true));
    }

    @Test
    void rejectsExternalGenerator() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> policy.assertLocal("external:deepseek-chat"));
        assertEquals(502, ex.getCode());
        assertEquals("答案来源校验失败", ex.getMessage());
    }

    @Test
    void rejectsExternalWhenNotDiverted() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> policy.assertAnswerAllowed("cloud:mimo-v2.6-flash", false));
        assertEquals(403, ex.getCode());
        assertEquals("普通问题不允许触发外部生成", ex.getMessage());
        assertFalse(policy.isAllowed("cloud:mimo-v2.6-flash", false));
    }

    @Test
    void acceptsAuthorizedExternalWhenComplexDiverted() {
        assertDoesNotThrow(() -> policy.assertAnswerAllowed("cloud:mimo-v2.6-flash", true));
        assertDoesNotThrow(() -> policy.assertAnswerAllowed("cloud:mimo-v2.6-pro", true));
        assertDoesNotThrow(() -> policy.assertAnswerAllowed("external:embedded:mimo", true));
        assertDoesNotThrow(() -> policy.assertAnswerAllowed("mimo:v2.6", true));
        assertTrue(policy.isAllowed("cloud:mimo-v2.6-flash", true));
    }

    @Test
    void rejectsUnknownExternalEvenWhenDiverted() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> policy.assertAnswerAllowed("unauthorized:unknown-llm", true));
        assertEquals(502, ex.getCode());
        assertEquals("答案来源校验失败", ex.getMessage());
        assertFalse(policy.isAllowed("unauthorized:unknown-llm", true));
    }
}

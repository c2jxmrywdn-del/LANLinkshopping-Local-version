package com.lanlink.shopping.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntentUnderstandingServiceTest {
    private final IntentUnderstandingService service = new IntentUnderstandingService();

    @Test
    void classifiesColloquialOrderQuestion() {
        var result = service.analyze("我刚才那个单子咋还没动静", List.of("下单后多久发货"));
        assertEquals("cart_order", result.data().get("intent"));
        assertTrue((Double) result.data().get("confidence") > 0.5);
    }

    @Test
    void usesContextForAmbiguousFollowUp() {
        var result = service.analyze("那这个怎么办", List.of("我想申请商户入驻"));
        assertEquals("merchant_onboarding", result.data().get("intent"));
    }

    @Test
    void flagsAmbiguousQuestionForClarification() {
        var result = service.analyze("账号商品都不太清楚", List.of());
        assertEquals(true, result.data().get("ambiguous"));
        assertFalse(String.valueOf(result.data().get("clarification")).isBlank());
    }
}

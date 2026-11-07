package com.lanlink.shopping.payment.stripe;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.PaymentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stripe 客户端就绪与优雅降级测试（不联网、不调用外部 API）
 */
class StripePayClientTest {

    private PaymentProperties props;
    private StripePayClient client;

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        client = new StripePayClient(props);
    }

    @Test
    void configuredReturnsFalseWhenMockTrue() {
        props.setMock(true);
        props.getStripe().setSecretKey("sk_test_123456");
        assertFalse(client.configured(), "Mock 模式下 configured 必须返回 false 以保护沙盒测试");
    }

    @Test
    void configuredReturnsFalseWhenKeyBlank() {
        props.setMock(false);
        props.getStripe().setSecretKey("   ");
        assertFalse(client.configured());
    }

    @Test
    void configuredReturnsTrueWhenKeySetAndNotMock() {
        props.setMock(false);
        props.getStripe().setSecretKey("sk_test_valid_key");
        assertTrue(client.configured());
    }

    @Test
    void createPaymentIntentRejectsWhenUnconfigured() {
        props.setMock(true);
        assertThrows(BusinessException.class, () ->
                client.createPaymentIntent("ORD123", new BigDecimal("100.00"), 1L)
        );
    }

    @Test
    void verifyAndConstructEventRejectsWhenWebhookSecretBlank() {
        props.getStripe().setWebhookSecret("");
        assertThrows(BusinessException.class, () ->
                client.verifyAndConstructEvent("{}", "sig_test")
        );
    }

    @Test
    void refundRejectsWhenUnconfigured() {
        props.setMock(true);
        assertThrows(BusinessException.class, () ->
                client.refund("pi_test_123", new BigDecimal("50.00"), "User refund")
        );
    }
}

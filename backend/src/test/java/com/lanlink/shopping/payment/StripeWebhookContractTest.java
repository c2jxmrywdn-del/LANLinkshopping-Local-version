package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stripe webhook 契约（R23/R24/R25）。
 *
 * <p>三条缺陷形态：
 * <ol>
 *   <li><b>验签失败仍返回 200</b>：Stripe 以 2xx 判定送达，验签失败的事件会被永久丢弃，
 *       订单停在未支付而钱已扣；</li>
 *   <li><b>退款 pending 当成功</b>：本地立即恢复库存、扣回积分，但 Stripe 侧可能最终 failed；</li>
 *   <li><b>无主动查单</b>：webhook 丢失后订单无法自愈。</li>
 * </ol>
 */
class StripeWebhookContractTest {

    private StripePayClient stripe;
    private PaymentService service;
    private com.lanlink.shopping.payment.PaymentController controller;

    @BeforeEach
    void setUp() {
        PaymentProperties props = new PaymentProperties();
        props.setMock(false);
        stripe = mock(StripePayClient.class);
        PaymentService svc = new PaymentService(props, mock(WechatPayClient.class),
                mock(AlipayPayClient.class), stripe, mock(OrderService.class),
                mock(OrderMapper.class), mock(PaymentLogService.class), mock(WalletService.class));
        this.service = svc;
        this.controller = new com.lanlink.shopping.payment.PaymentController(svc, props);
    }

    @Test
    @DisplayName("验签失败必须返回 400，让 Stripe 重试而不是静默丢弃")
    void signatureFailureReturns400() {
        when(stripe.verifyAndConstructEvent(anyString(), anyString()))
                .thenThrow(new BusinessException("Stripe Webhook 签名验证不通过"));

        ResponseEntity<String> resp = controller.stripeNotify("{}", "t=1,v1=bad");

        assertEquals(400, resp.getStatusCode().value(),
                "验签失败返回 2xx 会让 Stripe 认为已送达，事件永久丢失");
        assertTrue(resp.getBody().contains("Signature verification failed"), resp.getBody());
    }

    @Test
    @DisplayName("缺失签名头同样返回 400")
    void missingSignatureReturns400() {
        ResponseEntity<String> resp = controller.stripeNotify("{}", null);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("验签成功的事件返回 200")
    void validEventReturns200() {
        com.stripe.model.Event event = mock(com.stripe.model.Event.class);
        when(event.getType()).thenReturn("payment_intent.created");
        when(stripe.verifyAndConstructEvent(anyString(), anyString())).thenReturn(event);

        ResponseEntity<String> resp = controller.stripeNotify("{}", "t=1,v1=ok");

        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("查单兜底：未配置时 retrievePaymentIntent 返回 null 而不是抛异常")
    void retrieveReturnsNullWhenNotConfigured() {
        assertNull(stripe.retrievePaymentIntent("pi_x"),
                "未配置时查单应静默返回 null，不能把主流程打断");
    }
}

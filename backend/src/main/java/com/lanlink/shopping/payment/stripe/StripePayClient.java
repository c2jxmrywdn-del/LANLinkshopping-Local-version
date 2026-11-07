package com.lanlink.shopping.payment.stripe;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.PaymentProperties;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Stripe 跨境国际支付客户端。
 * 遵循平台统一支付规范：通过 configured() 进行凭证就绪检测，支持零密钥启动与优雅降级。
 */
@Component
public class StripePayClient {

    private final PaymentProperties props;

    public StripePayClient(PaymentProperties props) {
        this.props = props;
    }

    /** 检查是否已配置有效密钥且非 mock 模式 */
    public boolean configured() {
        PaymentProperties.Stripe s = props.getStripe();
        return !props.isMock() && s != null
                && s.getSecretKey() != null && !s.getSecretKey().isBlank();
    }

    protected StripeClient getClient() {
        return new StripeClient(props.getStripe().getSecretKey());
    }

    /**
     * 创建 PaymentIntent (返回 client_secret 用于前端 Elements / Apple Pay 挂载)
     */
    public PaymentIntent createPaymentIntent(String orderNo, BigDecimal amountYuan, Long userId) {
        if (!configured()) {
            throw new BusinessException("Stripe 支付通道未配置（或处于 mock 模式）");
        }
        try {
            // Stripe 金额以最小货币单位（分）计算
            long amountCents = amountYuan.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            String currency = (props.getStripe().getCurrency() == null || props.getStripe().getCurrency().isBlank())
                    ? "cny" : props.getStripe().getCurrency().toLowerCase().trim();

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency)
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build()
                    )
                    .putMetadata("orderNo", orderNo)
                    .putMetadata("userId", String.valueOf(userId))
                    .setDescription("LANLink Order: " + orderNo)
                    .build();

            return getClient().paymentIntents().create(params);
        } catch (StripeException e) {
            throw new BusinessException("Stripe 预下单失败: " + e.getMessage());
        }
    }

    /**
     * Webhook 签名验证与事件反序列化
     */
    public Event verifyAndConstructEvent(String payload, String sigHeader) {
        String endpointSecret = props.getStripe().getWebhookSecret();
        if (endpointSecret == null || endpointSecret.isBlank()) {
            throw new BusinessException("Stripe WebhookSecret 未配置");
        }
        try {
            return Webhook.constructEvent(payload, sigHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            throw new BusinessException("Stripe Webhook 签名验证不通过: " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("Stripe Webhook 解析异常: " + e.getMessage());
        }
    }

    /**
     * 申请退款 (原路退回)
     */
    public Refund refund(String paymentIntentId, BigDecimal refundYuan, String reason) {
        if (!configured()) {
            throw new BusinessException("Stripe 支付通道未配置（或处于 mock 模式）");
        }
        try {
            long refundCents = refundYuan.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(refundCents)
                    .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                    .putMetadata("reasonNote", reason == null ? "Customer refund" : reason)
                    .build();

            return getClient().refunds().create(params);
        } catch (StripeException e) {
            throw new BusinessException("Stripe 退款失败: " + e.getMessage());
        }
    }

    /**
     * 查询退款状态：retrieve refund
     * 返回 succeeded / pending / failed / canceled / null
     */
    public String queryRefundStatus(String refundId) {
        if (!configured() || refundId == null || refundId.isBlank()) return null;
        try {
            com.stripe.model.Refund ref = getClient().refunds().retrieve(refundId);
            return ref != null ? ref.getStatus() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

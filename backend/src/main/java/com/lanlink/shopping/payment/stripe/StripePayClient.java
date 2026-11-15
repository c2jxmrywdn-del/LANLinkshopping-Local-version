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
import java.util.List;

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

            PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency)
                    .putMetadata("orderNo", orderNo)
                    .putMetadata("userId", String.valueOf(userId))
                    .setDescription("LANLink Order: " + orderNo);

            /*
             * 支付方式：默认 automatic，钱包按配置显式启用。
             *
             * 关键事实（真机实测）：显式写 apple_pay 而账户未在 Dashboard 激活该方式时，
             * Stripe 直接拒绝整笔预下单——`The payment method type "apple_pay" is invalid`，
             * 连银行卡支付也会一起失败。因此绝不能无条件写死钱包方式。
             *
             * 策略：
             *  - 默认只用 automatic_payment_methods（Dashboard 开了什么就用什么，最安全）；
             *  - 需要确保钱包出现在前端时，把 lanlink.payment.stripe.wallet-payment-methods
             *    配成 card,apple_pay,google_pay（前提：Dashboard 已激活并完成域名验证）。
             * 两者互斥（Stripe 不允许并存），配置了显式列表就不再设 automatic。
             */
            List<String> explicitTypes = props.getStripe().getWalletPaymentMethods();
            if (explicitTypes != null && !explicitTypes.isEmpty()) {
                for (String t : explicitTypes) {
                    if (t != null && !t.isBlank()) builder.addPaymentMethodType(t.trim());
                }
            } else {
                builder.setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build());
            }

            PaymentIntentCreateParams params = builder.build();

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

    /**
     * 主动查询 PaymentIntent 真实状态（webhook 丢失时的补偿路径）。
     *
     * <p>为什么需要：webhook 是"尽力投递"，端点短暂不可用或验签失败期间的事件不会自动重放，
     * 订单会永久停在未支付而资金已扣。查单是唯一能自愈的手段。
     *
     * @return PaymentIntent；未配置或查询失败返回 null（调用方据此跳过，不影响主流程）
     */
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) {
        if (!configured() || paymentIntentId == null || paymentIntentId.isBlank()) return null;
        try {
            return getClient().paymentIntents().retrieve(paymentIntentId);
        } catch (StripeException e) {
            return null;
        }
    }

    /**
     * 查询 Stripe 账户已激活的支付方式（用于前端决定是否展示钱包入口）。
     *
     * <p>为什么必须问 Stripe 而不是看配置：钱包是否可用取决于三件外部事实——
     * 账户是否激活该方式、Apple Pay 域名是否验证通过、Dashboard 开关是否打开。
     * 任何一项没满足，前端挂出钱包按钮都只会得到失败。这里以 Stripe 的实际返回为准。
     *
     * @return 已激活方式的小写集合；未配置或查询失败返回空集合（fail-closed，不展示钱包）
     */
    public java.util.Set<String> activePaymentMethodTypes() {
        if (!configured()) return java.util.Set.of();
        try {
            /*
             * 必须先用 list 取真实配置 ID，不能硬编码 "default"。
             *
             * 实测：`/v1/payment_method_configurations/default` 返回
             * `No such paymentsconfig: 'default'`——该账户的配置 ID 形如
             * `pmc_1Tfjww...`，"default" 只是显示名。写死会导致能力探测永远失败、
             * 钱包入口永远不显示，而且没有任何报错。
             */
            com.stripe.model.StripeCollection<com.stripe.model.PaymentMethodConfiguration> list =
                    getClient().paymentMethodConfigurations().list(
                            com.stripe.param.PaymentMethodConfigurationListParams.builder()
                                    .setLimit(5L).build());
            if (list.getData() == null || list.getData().isEmpty()) return java.util.Set.of();
            com.stripe.model.PaymentMethodConfiguration cfg = list.getData().get(0);
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            if (cfg.getApplePay() != null && cfg.getApplePay().getDisplayPreference() != null
                    && "on".equalsIgnoreCase(String.valueOf(cfg.getApplePay().getDisplayPreference().getValue()))) {
                out.add("apple_pay");
            }
            if (cfg.getGooglePay() != null && cfg.getGooglePay().getDisplayPreference() != null
                    && "on".equalsIgnoreCase(String.valueOf(cfg.getGooglePay().getDisplayPreference().getValue()))) {
                out.add("google_pay");
            }
            return out;
        } catch (Exception e) {
            // 能力探测失败绝不能影响支付主流程：按"不可用"处理，前端只展示银行卡
            return java.util.Set.of();
        }
    }

    /**
     * Apple Pay 已注册的域名数量。
     *
     * <p>Apple Pay 必须先把站点域名注册到 Stripe 并完成验证，否则 PaymentIntent
     * 不会返回 apple_pay 方式。本地 127.0.0.1 / localhost 无法通过验证（Stripe 要求真实域名），
     * 因此本地开发时该值为 0 属于预期。
     */
    public int applePayDomainCount() {
        if (!configured()) return 0;
        try {
            com.stripe.model.StripeCollection<com.stripe.model.ApplePayDomain> c = getClient().applePayDomains().list(
                    com.stripe.param.ApplePayDomainListParams.builder().setLimit(10L).build());
            return c.getData() == null ? 0 : c.getData().size();
        } catch (Exception e) {
            return 0;
        }
    }
}

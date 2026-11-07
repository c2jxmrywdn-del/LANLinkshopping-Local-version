package com.lanlink.shopping.integration.mq;

/**
 * MQ 载荷完整性校验失败（security-hardening-plan.md P1-6.1）。
 *
 * <p>消费侧反查 t_order/t_credit_bill 与载荷不符（订单不存在/归属不符/金额不符/未支付）
 * 时抛出：上抛 → spring-retry 3 次耗尽 → default-requeue-rejected=false → lanlink.dlx → DLQ。
 * 证据留在 DLQ 与审计（MQ_CONSUME_REJECTED），绝不静默入账。
 */
public class MessageIntegrityException extends RuntimeException {

    public MessageIntegrityException(String message) {
        super(message);
    }
}

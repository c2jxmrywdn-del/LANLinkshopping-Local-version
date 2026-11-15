package com.lanlink.shopping.payment.conflict.dto;

import com.lanlink.shopping.entity.Order;

/**
 * 支付回调结算的三态裁决结果（P0-B 只定义，不接线；下一轮由 OrderService/PaymentService 使用）。
 *
 * <p>为什么不用 boolean：{@code false} 同时代表"重复回调"和"订单已关闭"，调用方无法区分——
 * 这正是"关单后收到渠道付款被静默 ACK、无挂账无退款"资损的根因；也不能用异常：
 * 渠道回调无论哪种结局都必须 ACK，异常会被各 notify 入口的 catch 转成 FAIL 导致渠道无限重试。
 *
 * <p>放在 payment.conflict.dto 而不是 service 包：该结果由支付冲突处置域消费，
 * 避免 {@code OrderService} 与支付冲突实现强耦合（下一轮接线时再决定 import 方向）。
 *
 * @param outcome       三态结局
 * @param order         裁决后重读的订单快照（挂账表可直接取订单状态/金额/userId）
 * @param transactionId 本次回调携带的渠道流水号（可能为空，挂账落库时由冲突服务归一化）
 * @param secondCharge  仅 {@link Outcome#ALREADY_PAID} 有意义：true=同一订单出现第二笔渠道流水
 *                      （钱被扣了两次，需挂 duplicate_txn 并原路退回），false=真重复回调
 */
public record SettlePaidResult(Outcome outcome, Order order, String transactionId, boolean secondCharge) {

    public enum Outcome {
        /** 本次把 pay_status 0→1，正常结算成功 */
        SETTLED,
        /** 订单早已支付：真重复回调（幂等 ACK）；secondCharge=true 表示同单第二笔渠道流水 */
        ALREADY_PAID,
        /** 渠道已扣款，但本地订单已关闭/已取消/已退款：收款冲突，必须挂账+退款，且 ACK */
        ORDER_CONFLICT
    }

    public boolean settled() {
        return outcome == Outcome.SETTLED;
    }

    public boolean conflict() {
        return outcome == Outcome.ORDER_CONFLICT;
    }
}

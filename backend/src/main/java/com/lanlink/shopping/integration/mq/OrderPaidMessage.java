package com.lanlink.shopping.integration.mq;

import java.math.BigDecimal;

/**
 * 订单支付成功 MQ 载荷（lanlink.order / order.paid，out/storage-mq-plan.md 任务 B3）。
 * 独立 record 而非直接投递 OrderPaidEvent：消息体不与 Spring 事件类路径耦合，
 * 消费方只依赖 userId/orderNo/amount 三字段协议。
 */
public record OrderPaidMessage(Long userId, String orderNo, BigDecimal amount) {
}

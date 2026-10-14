package com.lanlink.shopping.integration.event;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

/**
 * 订单支付成功事件（跨模块通信协议）。
 * 由订单系统发布，营销中台各模块（会员/活动）通过监听器消费，
 * 实现「模块隔离 + 事件驱动」：发布方不感知任何营销模块，营销模块间亦互不依赖。
 */
public class OrderPaidEvent extends ApplicationEvent {

    private final Long userId;
    private final String orderNo;
    private final BigDecimal amount;

    public OrderPaidEvent(Object source, Long userId, String orderNo, BigDecimal amount) {
        super(source);
        this.userId = userId;
        this.orderNo = orderNo;
        this.amount = amount;
    }

    public Long getUserId() { return userId; }
    public String getOrderNo() { return orderNo; }
    public BigDecimal getAmount() { return amount; }
}

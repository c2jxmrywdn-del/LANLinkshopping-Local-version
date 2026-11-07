package com.lanlink.shopping.integration.event;

/**
 * 订单站内通知事件。
 *
 * <p>只携带不可变值，由 {@link com.lanlink.shopping.integration.listener.OrderNotificationListener}
 * 在事务提交后消费，避免在业务事务内直接写消息表。
 */
public record OrderNotificationEvent(Long userId, String title, String content, String relatedNo) {
}

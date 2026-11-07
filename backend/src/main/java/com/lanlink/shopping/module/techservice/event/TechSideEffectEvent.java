package com.lanlink.shopping.module.techservice.event;

/**
 * 技术服务副作用事件（目前仅站内消息）。
 *
 * <p>只携带不可变值：接收人、标题、内容与业务单号，不携带实体对象。
 * 由 {@link TechSideEffectListener} 在业务事务提交后消费，避免事务内写消息。
 */
public record TechSideEffectEvent(Long userId, String title, String content, String relatedNo) {
}

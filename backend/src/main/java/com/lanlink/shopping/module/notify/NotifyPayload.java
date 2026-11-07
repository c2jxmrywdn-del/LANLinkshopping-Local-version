package com.lanlink.shopping.module.notify;

import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 通知载荷。
 *
 * <p>只放 ID 与事件语义（不放对象、不放 URL）：消费侧按 userId 回查后写站内信。
 * message_key 由业务要素派生，让"同一件事重复投递"在消费端天然折叠。
 */
public record NotifyPayload(Long userId, String type, String title, String content,
                            String relatedNo, String messageKey) {

    public static NotifyPayload ofTech(TechSideEffectEvent event) {
        return new NotifyPayload(event.userId(), "TECH", event.title(), event.content(),
                event.relatedNo(), techKey(event));
    }

    /** 派生去重键：同一收件人 + 同一业务单号 + 同一标题正文 = 同一件事。 */
    public static String techKey(TechSideEffectEvent event) {
        String raw = event.userId() + "|" + event.relatedNo() + "|" + event.title() + "|" + event.content();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "tech:" + HexFormat.of().formatHex(digest, 0, 20);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

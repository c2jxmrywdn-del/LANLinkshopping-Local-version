package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.module.attachment.entity.Attachment;

import java.time.Duration;
import java.time.LocalDateTime;

/** 挂起件回收判定：与扫表 SQL 分离，便于把"什么算过期"单独钉死。 */
public final class AttachmentRetention {

    static final Duration PENDING_RETENTION = Duration.ofHours(24);

    private AttachmentRetention() {
    }

    /** 只有从未绑定的挂起件、且严格超过保留期，才允许回收；缺时间戳一律不动。 */
    public static boolean isReclaimable(Attachment row, LocalDateTime now) {
        if (row == null || row.getCreateTime() == null || now == null) {
            return false;
        }
        if (!"PENDING".equals(row.getStatus())) {
            return false;
        }
        return Duration.between(row.getCreateTime(), now).compareTo(PENDING_RETENTION) > 0;
    }
}

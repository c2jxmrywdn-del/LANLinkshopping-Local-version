package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.module.attachment.entity.Attachment;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挂起件回收判定（纯函数，时钟入参）。
 *
 * <p>只回收"从未被绑定"的挂起件：已交付材料和已失效记录都不再动。
 * 边界取严格大于，正好 24 小时的行本轮不回收，下一轮再收，避免与刚完成的上传抢时间。
 */
class AttachmentRetentionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0);

    private Attachment row(String status, LocalDateTime createTime) {
        Attachment a = new Attachment();
        a.setAttachNo("AT20261104134320A9");
        a.setStatus(status);
        a.setCreateTime(createTime);
        return a;
    }

    @Test
    void pendingOlderThanRetentionIsReclaimable() {
        assertTrue(AttachmentRetention.isReclaimable(
                row("PENDING", NOW.minusHours(25)), NOW));
    }

    @Test
    void pendingWithinRetentionIsKept() {
        assertFalse(AttachmentRetention.isReclaimable(
                row("PENDING", NOW.minusHours(23)), NOW));
    }

    @Test
    void pendingExactlyAtBoundaryIsNotReclaimableYet() {
        assertFalse(AttachmentRetention.isReclaimable(
                row("PENDING", NOW.minusHours(24)), NOW));
    }

    @Test
    void boundAndExpiredRowsAreNeverReclaimable() {
        assertFalse(AttachmentRetention.isReclaimable(
                row("BOUND", NOW.minusDays(30)), NOW), "已交付材料绝不能被回收任务删掉");
        assertFalse(AttachmentRetention.isReclaimable(
                row("EXPIRED", NOW.minusDays(30)), NOW));
    }

    @Test
    void rowWithoutCreateTimeIsNotReclaimable() {
        assertFalse(AttachmentRetention.isReclaimable(row("PENDING", null), NOW),
                "缺时间戳不能当成「很旧」，否则会误删刚上传的材料");
    }
}

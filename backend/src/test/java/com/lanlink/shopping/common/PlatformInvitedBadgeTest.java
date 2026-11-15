package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「平台特邀商户」公开展示条件。
 *
 * <p>必须三者同时成立：后台已标记 + 入驻审核通过 + 店铺仍在经营。
 * 任一条不满足都不能展示，避免"未过审/已关店还挂着特邀标"。
 */
class PlatformInvitedBadgeTest {

    @Test
    void visibleOnlyWhenMarkedApprovedAndOpen() {
        assertTrue(PlatformInvitedBadge.visible(1, 1, null), "已标记 + 已过审 + 正常经营 → 展示");
        assertTrue(PlatformInvitedBadge.visible(1, 1, ""), "exitStatus 空串同样视为正常经营");
        assertTrue(PlatformInvitedBadge.visible(1, 1, "REQUESTED"), "退店申请中仍正常展示");
    }

    @Test
    void notVisibleWhenNotMarked() {
        assertFalse(PlatformInvitedBadge.visible(1, 0, null), "未标记的普通商户不得展示");
        assertFalse(PlatformInvitedBadge.visible(1, null, null), "标记字段为空不得展示");
        assertFalse(PlatformInvitedBadge.visible(1, 2, null), "非 1 的标记值不得展示");
    }

    @Test
    void notVisibleWhenNotApproved() {
        assertFalse(PlatformInvitedBadge.visible(0, 1, null), "待审核不得展示");
        assertFalse(PlatformInvitedBadge.visible(2, 1, null), "审核驳回不得展示");
        assertFalse(PlatformInvitedBadge.visible(null, 1, null), "审核状态缺失不得展示");
    }

    @Test
    void notVisibleWhenShopClosedOrFrozen() {
        assertFalse(PlatformInvitedBadge.visible(1, 1, "CLOSED"), "已关店不得展示");
        assertFalse(PlatformInvitedBadge.visible(1, 1, "FROZEN"), "已冻结不得展示");
    }

    /** 状态流转一致性：标记存在但状态变化时，展示必须随之变化。 */
    @Test
    void badgeFollowsStatusTransitions() {
        // 标记后待审 → 不展示；过审 → 展示；关店 → 不展示
        assertFalse(PlatformInvitedBadge.visible(0, 1, null));
        assertTrue(PlatformInvitedBadge.visible(1, 1, null));
        assertFalse(PlatformInvitedBadge.visible(1, 1, "CLOSED"));
        // 取消标记 → 即使过审也不展示
        assertFalse(PlatformInvitedBadge.visible(1, 0, null));
    }
}

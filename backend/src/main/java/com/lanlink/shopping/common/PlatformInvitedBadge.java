package com.lanlink.shopping.common;

/**
 * 「平台特邀商户」公开展示条件。
 * 必须同时满足：后台已标记特邀、入驻审核已通过、店铺仍在公开经营。
 * 驳回、未标记、已关闭或已冻结的店铺都不展示。
 */
public final class PlatformInvitedBadge {

    private PlatformInvitedBadge() {}

    public static boolean visible(Integer reviewStatus, Integer platformInvited, String exitStatus) {
        if (platformInvited == null || platformInvited != 1) return false;
        if (reviewStatus == null || reviewStatus != 1) return false;
        if (exitStatus == null || exitStatus.isBlank()) return true;
        return !"CLOSED".equals(exitStatus) && !"FROZEN".equals(exitStatus);
    }
}

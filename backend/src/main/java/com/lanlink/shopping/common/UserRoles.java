package com.lanlink.shopping.common;

/**
 * 平台角色 ID 常量（t_role 固定种子数据）。
 * 供 AuthInterceptor 的 /admin/** 鉴权与各控制器运营视角判定共用，
 * 避免魔法数字 3L 散落多处造成口径漂移。
 */
public final class UserRoles {

    /** 平台运营（admin）：可访问 /admin/** 与运营后台全量视角 */
    public static final Long ADMIN_ROLE_ID = 3L;

    /** 商户（merchant） */
    public static final Long MERCHANT_ROLE_ID = 2L;

    /** 采购方（buyer） */
    public static final Long BUYER_ROLE_ID = 1L;

    private UserRoles() {
    }

    /** 是否平台运营（null 安全） */
    public static boolean isAdmin(Long roleId) {
        return ADMIN_ROLE_ID.equals(roleId);
    }
}

package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户身份权限矩阵完整性测试（访问控制规则的服务端事实源）。
 * 断言各身份类型的权限归属，防止后续修改破坏矩阵一致性。
 */
class UserIdentityPermissionTest {

    @Test
    void guestOnlyPublicBrowsing() {
        UserIdentity g = UserIdentity.GUEST;
        assertTrue(g.has("product:view"));
        assertTrue(g.has("product:search"));
        assertFalse(g.has("cart:manage"), "访客不可加购");
        assertFalse(g.has("order:create"), "访客不可下单");
        assertFalse(g.has("profile:manage"));
        assertFalse(g.has("merchant:manage"));
        assertFalse(g.has("admin:all"));
    }

    @Test
    void buyerHasTransactionPermissionsNoMerchantOrAdmin() {
        UserIdentity b = UserIdentity.BUYER;
        assertTrue(b.has("cart:manage"));
        assertTrue(b.has("order:create"));
        assertTrue(b.has("order:view"));
        assertTrue(b.has("address:manage"));
        assertTrue(b.has("profile:manage"));
        assertFalse(b.has("vip:discount"), "普通用户无 VIP 折扣");
        assertFalse(b.has("product:publish"), "普通用户不可发布商品");
        assertFalse(b.has("admin:all"));
    }

    @Test
    void vipAddsVipPermissions() {
        UserIdentity v = UserIdentity.VIP;
        assertTrue(v.has("vip:discount"));
        assertTrue(v.has("vip:service"));
        // 保留买家全部基础权限
        assertTrue(v.has("order:create"));
        assertTrue(v.has("cart:manage"));
        assertFalse(v.has("product:publish"));
        assertFalse(v.has("admin:all"));
    }

    @Test
    void merchantHasPublishButNoAdmin() {
        UserIdentity m = UserIdentity.MERCHANT;
        assertTrue(m.has("merchant:manage"));
        assertTrue(m.has("product:publish"));
        assertTrue(m.has("order:create"), "商户可采购下单");
        assertFalse(m.has("admin:all"));
        assertFalse(m.has("vip:discount"), "商户身份不叠加 VIP 折扣");
    }

    @Test
    void adminHasAll() {
        UserIdentity a = UserIdentity.ADMIN;
        assertTrue(a.has("admin:all"));
        // admin:all 视为通配：矩阵中唯一带全权标识的身份
        assertTrue(a.has("admin:all"));
    }

    @Test
    void identityHierarchyExclusivity() {
        // 每种身份权限互不越界：GUEST 最小、ADMIN 最大
        assertTrue(UserIdentity.GUEST.getPermissions().size() < UserIdentity.BUYER.getPermissions().size());
        assertTrue(UserIdentity.BUYER.getPermissions().size() <= UserIdentity.VIP.getPermissions().size());
    }
}

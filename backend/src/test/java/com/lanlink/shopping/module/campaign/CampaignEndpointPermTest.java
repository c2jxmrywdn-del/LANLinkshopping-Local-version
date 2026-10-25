package com.lanlink.shopping.module.campaign;

import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.campaign.controller.CampaignAdminController;
import com.lanlink.shopping.module.campaign.controller.CampaignController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 锁定活动系统接口权限：
 *  - 商户端（报名/激励领奖/活动列表）必须要求 merchant:manage；
 *  - 管理端整个控制器必须要求 admin:all（配合 /admin/** 路径的角色拦截双保险）。
 */
class CampaignEndpointPermTest {

    @Test
    void merchantEndpointsRequireMerchantManage() throws Exception {
        assertMerchantManage("merchantList", jakarta.servlet.http.HttpServletRequest.class);
        assertMerchantManage("merchantJoin", Long.class, jakarta.servlet.http.HttpServletRequest.class);
        assertMerchantManage("merchantClaimReward", Long.class, jakarta.servlet.http.HttpServletRequest.class);
    }

    @Test
    void adminControllerRequiresAdminAll() {
        RequirePerm anno = CampaignAdminController.class.getAnnotation(RequirePerm.class);
        assertNotNull(anno, "CampaignAdminController 缺少类级 @RequirePerm");
        assertEquals("admin:all", anno.value());
    }

    private void assertMerchantManage(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = CampaignController.class.getMethod(methodName, parameterTypes);
        RequirePerm anno = method.getAnnotation(RequirePerm.class);
        assertNotNull(anno, methodName + " 缺少 @RequirePerm");
        assertEquals("merchant:manage", anno.value(), methodName + " 应要求 merchant:manage 权限");
    }
}

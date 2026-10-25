package com.lanlink.shopping.integration.security;

import com.lanlink.shopping.controller.LogisticsController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 锁定商户端物流资料接口必须要求 merchant:manage，
 * 防止仅做登录校验、任意登录用户即可读取服务商与运费模板。
 */
class LogisticsEndpointPermTest {

    @Test
    void providersRequiresMerchantManage() throws Exception {
        assertMerchantManage("providers");
    }

    @Test
    void templatesRequiresMerchantManage() throws Exception {
        assertMerchantManage("templates", Long.class);
    }

    private void assertMerchantManage(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = LogisticsController.class.getMethod(methodName, parameterTypes);
        RequirePerm anno = method.getAnnotation(RequirePerm.class);
        assertNotNull(anno, methodName + " 缺少 @RequirePerm");
        assertEquals("merchant:manage", anno.value(), methodName + " 应要求 merchant:manage 权限");
    }
}

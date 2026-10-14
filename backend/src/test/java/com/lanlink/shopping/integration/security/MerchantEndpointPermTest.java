package com.lanlink.shopping.integration.security;

import com.lanlink.shopping.controller.MerchantController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商户端点权限注解回归测试：
 * 锁定 /license、/tax-proof、/tax-query 必须携带 @RequirePerm("merchant:manage")，
 * 防止后续重构意外移除权限校验。
 */
class MerchantEndpointPermTest {

    private void assertPerm(Method m, String path) {
        RequirePerm anno = m.getAnnotation(RequirePerm.class);
        assertNotNull(anno, "缺少 @RequirePerm 注解: " + path);
        assertEquals("merchant:manage", anno.value(), path + " 应要求 merchant:manage 权限");
    }

    @Test
    void licenseUploadRequiresMerchantPerm() throws Exception {
        assertPerm(MerchantController.class.getMethod("uploadLicense",
                org.springframework.web.multipart.MultipartFile.class, jakarta.servlet.http.HttpServletRequest.class),
                "POST /merchant/license");
    }

    @Test
    void taxProofUploadRequiresMerchantPerm() throws Exception {
        assertPerm(MerchantController.class.getMethod("uploadTaxProof",
                org.springframework.web.multipart.MultipartFile.class, jakarta.servlet.http.HttpServletRequest.class),
                "POST /merchant/tax-proof");
    }

    @Test
    void taxQueryRequiresMerchantPerm() throws Exception {
        assertPerm(MerchantController.class.getMethod("taxQuery",
                String.class, jakarta.servlet.http.HttpServletRequest.class),
                "GET /merchant/tax-query");
    }
}

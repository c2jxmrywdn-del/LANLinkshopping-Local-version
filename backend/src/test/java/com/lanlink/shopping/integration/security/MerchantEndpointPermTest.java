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
                org.springframework.web.multipart.MultipartFile.class, String.class,
                jakarta.servlet.http.HttpServletRequest.class),
                "POST /merchant/tax-proof");
    }

    @Test
    void taxQueryRequiresMerchantPerm() throws Exception {
        assertPerm(MerchantController.class.getMethod("taxQuery",
                String.class, jakarta.servlet.http.HttpServletRequest.class),
                "GET /merchant/tax-query");
    }

    @Test
    void taxProofPeriodParameterIsOptionalAndPathStable() throws Exception {
        Method m = MerchantController.class.getMethod("uploadTaxProof",
                org.springframework.web.multipart.MultipartFile.class, String.class,
                jakarta.servlet.http.HttpServletRequest.class);
        assertPerm(m, "POST /merchant/tax-proof");

        org.springframework.web.bind.annotation.RequestParam period =
                m.getParameters()[1].getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        assertNotNull(period, "period 必须通过 @RequestParam 绑定");
        assertFalse(period.required(), "period 为可选所属期，不能强制必填");

        org.springframework.web.bind.annotation.PostMapping mapping =
                m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertNotNull(mapping, "缺少 @PostMapping 注解");
        assertArrayEquals(new String[]{"/tax-proof"}, mapping.value(), "税务证明上传路径不得变更");
    }

    @Test
    void applyUploadStaysLoginOnlyAndSeparateFromMerchantPerm() throws Exception {
        Method m = MerchantController.class.getMethod("applyUpload",
                org.springframework.web.multipart.MultipartFile.class, String.class,
                jakarta.servlet.http.HttpServletRequest.class);
        assertNull(m.getAnnotation(RequirePerm.class),
                "申请阶段上传仅需登录，不得要求 merchant:manage");

        org.springframework.web.bind.annotation.PostMapping mapping =
                m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertNotNull(mapping, "缺少 @PostMapping 注解");
        assertArrayEquals(new String[]{"/apply-upload"}, mapping.value(), "申请阶段上传路径不得变更");
    }
}

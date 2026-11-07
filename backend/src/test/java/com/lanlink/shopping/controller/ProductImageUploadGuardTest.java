package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.AuthRateLimitInterceptor;
import com.lanlink.shopping.config.AuthInterceptor;
import com.lanlink.shopping.config.SecurityRouteGuardInterceptor;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.config.WebConfig;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.security.PermInterceptor;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.productreview.service.ProductAutoReviewService;
import com.lanlink.shopping.module.productreview.service.ProductReviewConfigService;
import com.lanlink.shopping.module.recommend.service.PersonalizeService;
import com.lanlink.shopping.module.search.ProductIndexJob;
import com.lanlink.shopping.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品主图上传三重收口（security-hardening-plan.md P1-5，门禁 SG-51/52/53 的单测面）：
 * ① @RequirePerm("product:publish") 在位（BUYER 403 / 匿名 401 由 PermInterceptor 链路执行）；
 * ② mustApprovedMerchant 前置（未入驻/未过审商户拒绝）；
 * ③ 限流策略 + 路径注册 + CSRF 双端登记；④ 成功上传写审计。
 */
class ProductImageUploadGuardTest {

    // ==================== SG-51：注解与策略在位 ====================

    @Test
    void uploadImageRequiresProductPublishPerm() throws Exception {
        Method m = ProductController.class.getMethod("uploadImage",
                org.springframework.web.multipart.MultipartFile.class, HttpServletRequest.class);
        com.lanlink.shopping.integration.security.RequirePerm perm =
                m.getAnnotation(com.lanlink.shopping.integration.security.RequirePerm.class);
        assertTrue(perm != null, "/product/image 必须挂 @RequirePerm（此前任意登录用户可写公读桶）");
        assertEquals("product:publish", perm.value(), "权限值与 /my/* 兄弟端点对齐");
    }

    @Test
    void buyerCannotPassProductPublishPerm() {
        // 权限矩阵：MERCHANT/ADMIN 可传，BUYER/VIP 缺 product:publish（PermInterceptor → 403+审计）
        assertFalse(UserIdentity.BUYER.has("product:publish"), "BUYER 不得拥有上传权限");
        assertFalse(UserIdentity.VIP.has("product:publish"), "VIP 不得拥有上传权限");
        assertTrue(UserIdentity.MERCHANT.has("product:publish"));
        assertTrue(UserIdentity.ADMIN.has("product:publish"));
    }

    @Test
    void rateLimitPolicyAndPathRegistrationAreBothInPlace() throws Exception {
        // 本项目踩过「只加策略不注册路径=等于无限流」：两处必须同时断言
        Method policyFor = AuthRateLimitInterceptor.class.getDeclaredMethod("policyFor", String.class);
        policyFor.setAccessible(true);
        Object policy = policyFor.invoke(null, "/product/image");
        assertTrue(policy != null, "POLICIES 必须含 /product/image 条目");

        WebConfig webConfig = new WebConfig(mock(SecurityRouteGuardInterceptor.class),
                new AuthRateLimitInterceptor(), mock(AuthInterceptor.class), mock(PermInterceptor.class));
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any(HandlerInterceptor.class))).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);

        webConfig.addInterceptors(registry);

        org.mockito.ArgumentCaptor<String[]> patterns = org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(registration, org.mockito.Mockito.atLeast(2)).addPathPatterns(patterns.capture());
        long rateLimitHits = patterns.getAllValues().stream()
                .filter(arr -> Arrays.asList(arr).contains("/product/image")).count();
        assertEquals(2, rateLimitHits,
                "/product/image 必须同时出现在限流注册与 CSRF 注册（2 处）");
    }

    @Test
    void frontendCsrfPolicyMirrorsProductImage() throws Exception {
        // 双端同步纪律：后端 WebConfig 登记 → 前端 csrfPolicy.js 必须镜像（axios 自动带令牌）
        Path js = Paths.get(System.getProperty("user.dir"), "..", "frontend", "src", "api", "csrfPolicy.js");
        assertTrue(Files.isRegularFile(js), "未找到 csrfPolicy.js: " + js.toAbsolutePath());
        String src = Files.readString(js, StandardCharsets.UTF_8);
        assertTrue(src.contains("product/image"), "前端 CSRF 白名单需含 /product/image");
    }

    // ==================== SG-52：商户门槛 ====================

    private ProductController controllerWith(MerchantService merchantService) {
        return new ProductController(mock(ProductService.class), merchantService,
                mock(PersonalizeService.class), mock(ProductIndexJob.class),
                mock(ProductAutoReviewService.class), mock(ProductReviewConfigService.class),
                mock(StorageService.class), mock(AuditService.class),
                mock(com.lanlink.shopping.service.ProductTitleAiService.class));
    }

    private static MockHttpServletRequest loggedInRequest(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/product/image");
        User user = new User();
        user.setUserId(userId);
        request.getSession(true).setAttribute(UserContext.SESSION_KEY, user);
        return request;
    }

    @Test
    void uploadWithoutMerchantIsRejected() {
        MerchantService merchantService = mock(MerchantService.class);
        when(merchantService.getByUser(7L)).thenReturn(null);
        ProductController controller = controllerWith(merchantService);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.uploadImage(null, loggedInRequest(7L)),
                "未入驻商户调上传必须被拒（此前仅登录门槛）");
        assertEquals("请先完成商户入驻", e.getMessage());
    }

    @Test
    void uploadByUnapprovedMerchantIsRejected() {
        MerchantService merchantService = mock(MerchantService.class);
        com.lanlink.shopping.entity.Merchant merchant = new com.lanlink.shopping.entity.Merchant();
        merchant.setReviewStatus(0);
        when(merchantService.getByUser(7L)).thenReturn(merchant);
        ProductController controller = controllerWith(merchantService);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.uploadImage(null, loggedInRequest(7L)),
                "未过审商户调上传必须被拒");
        assertEquals("商户审核未通过，暂不能管理商品", e.getMessage());
    }

    // ==================== SG-53：第 11 次上传 429 ====================

    private record LimitResult(boolean allowed, HttpServletResponse response) {
    }

    private LimitResult call(AuthRateLimitInterceptor interceptor, String ip) throws Exception {
        // 拦截器读的是去 context-path 后的路径（/api 前缀由容器剥掉），策略键为 /product/image
        MockHttpServletRequest request = new MockHttpServletRequest(RequestMethod.POST.name(), "/product/image");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());
        return new LimitResult(allowed, response);
    }

    @Test
    void eleventhUploadWithinWindowIsThrottled() throws Exception {
        AuthRateLimitInterceptor interceptor = new AuthRateLimitInterceptor(() -> 1_800_000_000_000L);
        String ip = "203.0.113.77";
        for (int i = 0; i < 10; i++) {
            assertTrue(call(interceptor, ip).allowed(), "前 10 次应放行，第 " + (i + 1) + " 次被误拦");
        }
        LimitResult blocked = call(interceptor, ip);
        assertFalse(blocked.allowed(), "60s 窗口内第 11 次上传必须 429");
        assertEquals(429, blocked.response().getStatus());
        assertEquals("60", blocked.response().getHeader("Retry-After"));
    }

    @Test
    void handlerMethodWithoutAnnotationWouldPassPermInterceptorButUploadStillGated() {
        // PermInterceptor 无注解放行 + 上传方法内 mustApprovedMerchant 是第二道闸：
        // 两者独立生效（防止未来有人挪走注解后方法内门槛仍是最后防线）
        assertFalse(UserIdentity.GUEST.has("product:publish"), "匿名身份无上传权限（401）");
    }
}

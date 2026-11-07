package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.AuthInterceptor;
import com.lanlink.shopping.config.AuthRateLimitInterceptor;
import com.lanlink.shopping.config.CsrfInterceptor;
import com.lanlink.shopping.config.SecurityRouteGuardInterceptor;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.config.WebConfig;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.security.PermInterceptor;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.ProductService;
import com.lanlink.shopping.service.ProductTitleAiService;
import com.lanlink.shopping.service.ProductTitleAiService.PolishRequest;
import com.lanlink.shopping.service.ProductTitleAiService.Result;
import com.lanlink.shopping.module.productreview.service.ProductAutoReviewService;
import com.lanlink.shopping.module.productreview.service.ProductReviewConfigService;
import com.lanlink.shopping.module.recommend.service.PersonalizeService;
import com.lanlink.shopping.module.search.ProductIndexJob;
import com.lanlink.shopping.common.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /product/ai/polish-title 三重收口（out/ai-polish-design.md §3.1/§3.3/§6.1，对齐
 * ProductImageUploadGuardTest 的 /product/image 门禁面）：
 * ① @RequirePerm("product:publish") 在位（BUYER/VIP 403、匿名 401 由 PermInterceptor 链路执行）
 *    + mustApprovedMerchant 前置；② 限流策略 + 路径注册双向断言；③ CSRF 双端登记；
 * ④ 端点校验（标题 trim 2-100）与响应契约（{code:200, data.candidates:[1..3]}）。
 */
class ProductTitlePolishGuardTest {

    // ==================== ① 权限与商户门槛 ====================

    @Test
    void polishTitleRequiresProductPublishPerm() throws Exception {
        Method m = ProductController.class.getMethod("polishTitle", PolishRequest.class, HttpServletRequest.class);
        com.lanlink.shopping.integration.security.RequirePerm perm =
                m.getAnnotation(com.lanlink.shopping.integration.security.RequirePerm.class);
        assertTrue(perm != null, "/product/ai/polish-title 必须挂 @RequirePerm");
        assertEquals("product:publish", perm.value(), "权限码与 /product 兄弟端点（publish/myUpdate/image）对齐");
    }

    @Test
    void buyerAndVipLackProductPublishPerm() {
        // 权限矩阵：MERCHANT/ADMIN 可用，BUYER/VIP 缺 product:publish（PermInterceptor → 403）
        assertFalse(UserIdentity.BUYER.has("product:publish"));
        assertFalse(UserIdentity.VIP.has("product:publish"));
        assertTrue(UserIdentity.MERCHANT.has("product:publish"));
        assertTrue(UserIdentity.ADMIN.has("product:publish"));
        assertFalse(UserIdentity.GUEST.has("product:publish"), "匿名无权限（PermInterceptor → 401）");
    }

    private ProductController controllerWith(MerchantService merchantService,
                                             ProductTitleAiService titleAiService) {
        return new ProductController(mock(ProductService.class), merchantService,
                mock(PersonalizeService.class), mock(ProductIndexJob.class),
                mock(ProductAutoReviewService.class), mock(ProductReviewConfigService.class),
                mock(StorageService.class), mock(AuditService.class), titleAiService);
    }

    private static MockHttpServletRequest loggedInRequest(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/product/ai/polish-title");
        User user = new User();
        user.setUserId(userId);
        request.getSession(true).setAttribute(UserContext.SESSION_KEY, user);
        return request;
    }

    @Test
    void polishWithoutMerchantIsRejected() {
        MerchantService merchantService = mock(MerchantService.class);
        when(merchantService.getByUser(7L)).thenReturn(null);
        ProductController controller = controllerWith(merchantService, mock(ProductTitleAiService.class));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.polishTitle(new PolishRequest("304不锈钢无缝管", null, null, "", false),
                        loggedInRequest(7L)),
                "未入驻商户调润色必须被拒");
        assertEquals("请先完成商户入驻", e.getMessage());
    }

    @Test
    void polishByUnapprovedMerchantIsRejected() {
        MerchantService merchantService = mock(MerchantService.class);
        com.lanlink.shopping.entity.Merchant merchant = new com.lanlink.shopping.entity.Merchant();
        merchant.setReviewStatus(0);
        when(merchantService.getByUser(7L)).thenReturn(merchant);
        ProductController controller = controllerWith(merchantService, mock(ProductTitleAiService.class));

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.polishTitle(new PolishRequest("304不锈钢无缝管", null, null, "", false),
                        loggedInRequest(7L)),
                "未过审商户调润色必须被拒");
        assertEquals("商户审核未通过，暂不能管理商品", e.getMessage());
    }

    // ==================== ④ 端点校验与响应契约 ====================

    @Test
    void titleLengthIsEnforcedAtEndpoint() {
        ProductTitleAiService titleAiService = mock(ProductTitleAiService.class);
        MerchantService merchantService = mock(MerchantService.class);
        when(merchantService.getByUser(7L)).thenReturn(approvedMerchant());
        ProductController controller = controllerWith(merchantService, titleAiService);

        BusinessException tooShort = assertThrows(BusinessException.class,
                () -> controller.polishTitle(new PolishRequest("管", null, null, "", false), loggedInRequest(7L)));
        assertEquals("商品名称需 2-100 个字符", tooShort.getMessage());

        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> controller.polishTitle(new PolishRequest("管".repeat(101), null, null, "", false),
                        loggedInRequest(7L)));
        assertEquals("商品名称需 2-100 个字符", tooLong.getMessage());

        verify(titleAiService, never()).polish(any(), any(), any());
    }

    @Test
    void successResponseCarriesCandidatesOnly() {
        MerchantService merchantService = mock(MerchantService.class);
        when(merchantService.getByUser(7L)).thenReturn(approvedMerchant());
        ProductTitleAiService titleAiService = mock(ProductTitleAiService.class);
        when(titleAiService.polish(eq(7L), any(), any())).thenReturn(
                Result.of(List.of("304不锈钢无缝管 DN15【品质优选】")));
        ProductController controller = controllerWith(merchantService, titleAiService);

        R<Map<String, Object>> response = controller.polishTitle(
                new PolishRequest("304不锈钢无缝管 DN15", null, null, "", false), loggedInRequest(7L));

        assertEquals(200, response.getCode(), "成功码是 200 不是 0（R.java:21-23）");
        assertEquals("操作成功", response.getMessage());
        List<?> candidates = (List<?>) response.getData().get("candidates");
        assertEquals(1, candidates.size(), "契约 [1,3]：过滤后剩 1 条也返回");
        assertTrue(candidates.get(0).toString().contains("304不锈钢无缝管 DN15"));
    }

    @Test
    void failureResponseMapsServiceMessageWithoutChannelLeak() {
        MerchantService merchantService = mock(MerchantService.class);
        when(merchantService.getByUser(7L)).thenReturn(approvedMerchant());
        ProductTitleAiService titleAiService = mock(ProductTitleAiService.class);
        when(titleAiService.polish(eq(7L), any(), any())).thenReturn(
                Result.failed(ProductTitleAiService.MSG_UNAVAILABLE));
        ProductController controller = controllerWith(merchantService, titleAiService);

        R<Map<String, Object>> response = controller.polishTitle(
                new PolishRequest("304不锈钢无缝管 DN15", null, null, "", false), loggedInRequest(7L));

        assertEquals(500, response.getCode(), "R.fail=500（R.java:24），前端 request.js 以 code!==200 判失败");
        assertEquals(ProductTitleAiService.MSG_UNAVAILABLE, response.getMessage());
        assertNull(response.getData(), "失败不带任何兜底候选");
        assertFalse(String.valueOf(response.getMessage()).contains("mimosa"), "渠道名不回传（只进审计）");
    }

    private com.lanlink.shopping.entity.Merchant approvedMerchant() {
        com.lanlink.shopping.entity.Merchant merchant = new com.lanlink.shopping.entity.Merchant();
        merchant.setMerId(11L);
        merchant.setReviewStatus(1);
        return merchant;
    }

    // ==================== ② 限流策略 + 路径注册双向断言 ====================

    @Test
    void rateLimitPolicyAndPathRegistrationAreBothInPlace() throws Exception {
        // 本项目踩过「只加策略不注册路径=等于无限流」：两处必须同时断言
        Method policyFor = AuthRateLimitInterceptor.class.getDeclaredMethod("policyFor", String.class);
        policyFor.setAccessible(true);
        Object policy = policyFor.invoke(null, "/product/ai/polish-title");
        assertTrue(policy != null, "POLICIES 的 /product/ai/* 前缀键必须命中 /product/ai/polish-title");

        WebConfig webConfig = new WebConfig(mock(SecurityRouteGuardInterceptor.class),
                new AuthRateLimitInterceptor(), mock(AuthInterceptor.class), mock(PermInterceptor.class));
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(any(HandlerInterceptor.class))).thenReturn(registration);
        when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);

        webConfig.addInterceptors(registry);

        org.mockito.ArgumentCaptor<String[]> patterns = org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(registration, org.mockito.Mockito.atLeast(2)).addPathPatterns(patterns.capture());
        long hits = patterns.getAllValues().stream()
                .filter(arr -> Arrays.asList(arr).contains("/product/ai/**")).count();
        assertEquals(2, hits, "/product/ai/** 必须同时出现在限流注册与 CSRF 注册（2 处）");
    }

    @Test
    void eleventhPolishWithinWindowIsThrottled() throws Exception {
        AuthRateLimitInterceptor interceptor = new AuthRateLimitInterceptor(() -> 1_800_000_000_000L);
        String ip = "203.0.113.88";
        for (int i = 0; i < 10; i++) {
            assertTrue(call(interceptor, ip).allowed(), "前 10 次应放行，第 " + (i + 1) + " 次被误拦");
        }
        LimitResult blocked = call(interceptor, ip);
        assertFalse(blocked.allowed(), "120s 窗口内第 11 次润色必须 429");
        assertEquals(429, blocked.response().getStatus());
    }

    private record LimitResult(boolean allowed, MockHttpServletResponse response) {
    }

    private LimitResult call(AuthRateLimitInterceptor interceptor, String ip) throws Exception {
        // 拦截器读的是去 context-path 后的路径（/api 前缀由容器剥掉），策略键经 /product/ai/* 前缀匹配
        MockHttpServletRequest request = new MockHttpServletRequest(RequestMethod.POST.name(),
                "/product/ai/polish-title");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());
        return new LimitResult(allowed, response);
    }

    // ==================== ③ CSRF：后端拦截行为 + 前端镜像 ====================

    @Test
    void csrfInterceptorRejectsMissingTokenOnPolishTitle() throws Exception {
        CsrfInterceptor interceptor = new CsrfInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/product/ai/polish-title");
        request.setContextPath("/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()), "CSRF 缺失必须拦截");
        assertEquals(200, response.getStatus(), "CsrfInterceptor 以响应体表达 403（R.fail(403,...)）");
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("CSRF"),
                "message 必须含 CSRF（前端 request.js 据此换令牌重放）");
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("403"));
    }

    @Test
    void csrfInterceptorPassesWithValidTokenOnPolishTitle() throws Exception {
        CsrfInterceptor interceptor = new CsrfInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/product/ai/polish-title");
        request.setContextPath("/api");
        String token = CsrfInterceptor.token(request);
        request.addHeader("X-CSRF-TOKEN", token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()), "携带会话令牌的写请求应放行");
    }

    @Test
    void frontendCsrfPolicyMirrorsAiPolish() throws Exception {
        // 双端同步纪律：后端 WebConfig 登记 → 前端 csrfPolicy.js 必须镜像（axios 自动带令牌）
        Path js = Paths.get(System.getProperty("user.dir"), "..", "frontend", "src", "api", "csrfPolicy.js");
        assertTrue(Files.isRegularFile(js), "未找到 csrfPolicy.js: " + js.toAbsolutePath());
        String src = Files.readString(js, StandardCharsets.UTF_8);
        assertTrue(src.contains("product/ai"), "前端 CSRF 白名单需含 /product/ai/");
    }
}

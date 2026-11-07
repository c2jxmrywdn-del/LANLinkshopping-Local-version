package com.lanlink.shopping.config;

import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private IdentityService identityService;
    private AuditService auditService;
    private AuthInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        identityService = mock(IdentityService.class);
        auditService = mock(AuditService.class);
        interceptor = new AuthInterceptor(identityService, auditService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getContextPath()).thenReturn("/api");
    }

    @Test
    void actuatorHealthEndpointsArePublicWithoutResolvingUserIdentity() throws Exception {
        for (String uri : List.of(
                "/api/actuator/health",
                "/api/actuator/health/liveness",
                "/api/actuator/health/readiness")) {
            when(request.getRequestURI()).thenReturn(uri);

            assertTrue(interceptor.preHandle(request, response, new Object()),
                    "健康探针应允许未登录请求：" + uri);
        }

        verifyNoInteractions(identityService, auditService, response);
    }

    @Test
    @DisplayName("未登录访问受保护端点：拒绝（preHandle=false）且响应为真实 HTTP 401，body 保持 R 结构")
    void unauthenticatedRequestIsRejectedWithRealHttpStatus401() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/logistics/waybill/list");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertFalse(proceed, "未登录请求应被拒绝");
        verify(response).setStatus(401);
        verify(response).setContentType("application/json;charset=UTF-8");
        assertTrue(body.toString().contains("\"code\":401"), "body 仍应携带业务码 401：" + body);
    }

    @Test
    @DisplayName("登录用户越权访问 /admin/**：真实 HTTP 403 + ACCESS_DENIED 审计")
    void adminAreaDeniedForNonAdminWithRealHttpStatus403() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/admin/logistics/metrics");
        HttpSession session = mock(HttpSession.class);
        User buyer = new User();
        buyer.setUserId(9L);
        buyer.setRoleId(1L); // 非运营
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(buyer);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertFalse(proceed, "越权请求应被拒绝");
        verify(response).setStatus(403);
        verify(auditService).record(eq(9L), eq("ACCESS_DENIED"), anyString(), eq(request));
    }

    @Test
    @DisplayName("白名单回调前缀保持匿名放行（W1 判据①：渠道推送无登录态可达）")
    void logisticsCallbackPrefixRemainsWhitelisted() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/logistics/callback/mock/dev-callback-token");

        assertTrue(interceptor.preHandle(request, response, new Object()),
                "/logistics/callback/ 前缀应公开放行");
        verify(response, org.mockito.Mockito.never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("403/越权写出的 body 含 message，供前端错误分支展示（contains 匹配防 JSON 键序耦合）")
    void rejectionBodyCarriesServerMessage() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/logistics/waybill/list");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));

        interceptor.preHandle(request, response, new Object());

        verify(response).setStatus(401);
        verify(response).setContentType(contains("json"));
        assertTrue(body.toString().contains("请先登录"));
    }
}

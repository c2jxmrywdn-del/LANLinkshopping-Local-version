package com.lanlink.shopping.integration.security;

import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PermInterceptor 单元测试：注解驱动的后端权限校验。
 */
class PermInterceptorTest {

    private AuditService auditService;
    private PermInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    static class Annotated {
        @RequirePerm("product:publish")
        public void publish() {}

        @RequirePerm("vip:discount")
        public void vipOnly() {}

        @RequirePerm("   ")
        public void blankPerm() {}

        @RequirePerm("")
        public void emptyPerm() {}

        public void open() {}
    }

    @BeforeEach
    void setUp() throws Exception {
        auditService = mock(AuditService.class);
        interceptor = new PermInterceptor(auditService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        // 403 分支需要读取当前登录用户（此处均视为匿名，userId=null）
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(null);
    }

    private HandlerMethod method(String name) throws Exception {
        return new HandlerMethod(new Annotated(), Annotated.class.getMethod(name));
    }

    @Test
    void noAnnotationPasses() throws Exception {
        assertTrue(interceptor.preHandle(request, response, method("open")));
    }

    @Test
    void emptyOrNullPermValueSkipsCheck() throws Exception {
        // 空字符串 / 空白字符串权限值：直接放行，不进入身份/权限判断与审计
        assertTrue(interceptor.preHandle(request, response, method("emptyPerm")));
        assertTrue(interceptor.preHandle(request, response, method("blankPerm")));
        // 未登录（GUEST）也不应触发 401 —— 校验流程已跳过
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.GUEST);
        assertTrue(interceptor.preHandle(request, response, method("blankPerm")));
        verify(auditService, never()).record(any(), any(), any(), any());
    }

    @Test
    void guestDenied401() throws Exception {
        // 未登录：identity = GUEST
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.GUEST);
        assertFalse(interceptor.preHandle(request, response, method("publish")));
    }

    @Test
    void buyerWithoutPermDenied403AndAudited() throws Exception {
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.BUYER);
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute(UserContext.SESSION_KEY)).thenReturn(null);

        assertFalse(interceptor.preHandle(request, response, method("publish")));
        verify(auditService, times(1)).record(isNull(), eq("ACCESS_DENIED"), contains("product:publish"), eq(request));
    }

    @Test
    void merchantWithPermPasses() throws Exception {
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.MERCHANT);
        assertTrue(interceptor.preHandle(request, response, method("publish")));
    }

    @Test
    void buyerVipDenied403VipOnly() throws Exception {
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.BUYER);
        assertFalse(interceptor.preHandle(request, response, method("vipOnly")));
    }

    @Test
    void vipWithPermPasses() throws Exception {
        when(request.getAttribute(UserContext.IDENTITY_KEY)).thenReturn(UserIdentity.VIP);
        assertTrue(interceptor.preHandle(request, response, method("vipOnly")));
    }
}

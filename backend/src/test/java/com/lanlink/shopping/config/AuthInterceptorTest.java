package com.lanlink.shopping.config;

import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

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
}

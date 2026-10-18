package com.lanlink.shopping.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class SecurityRouteGuardInterceptorTest {

    private SecurityRouteGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SecurityRouteGuardInterceptor();
    }

    @Test
    void allowsNormalRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order/my");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void blocksTraceMethod() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("TRACE", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("HTTP 方法不被支持"));
    }

    @Test
    void blocksPathTraversal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/uploads/../../etc/passwd");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("非法请求或受限制的访问路径"));
    }

    @Test
    void allowsHealthCheckProbe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void blocksSensitiveProbes() throws Exception {
        String[] probePaths = {
                "/.env",
                "/.git/config",
                "/actuator/env",
                "/swagger-ui/index.html",
                "/v2/api-docs",
                "/backup.sql",
                "/wp-admin/index.php"
        };

        for (String path : probePaths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean allowed = interceptor.preHandle(request, response, new Object());
            assertFalse(allowed, "Should block " + path);
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void blocksSqlInjectionInQuery() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/product/search");
        request.setQueryString("keyword=1%27%20or%201=1--&page=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("非法参数字符"));
    }
}

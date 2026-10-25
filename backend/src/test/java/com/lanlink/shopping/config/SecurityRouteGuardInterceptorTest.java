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

    /**
     * 边界回归：正常业务路径可能包含点号、版本号或常见文件后缀，
     * 不能因为宽泛的备份/源码探测规则被误伤。
     */
    @Test
    void allowsLegitimateBusinessPathsWithDotsAndExtensions() throws Exception {
        String[] paths = {
                "/api/product/8.webp",
                "/api/orders/20261022170813519896",
                "/api/logistics/waybill/WB202610220001/traces",
                "/api/uploads/tax-proof/2026/10/license.jpg",
                "/api/merchant/tax-query",
                "/api/admin/ai-models",
                "/api/support/knowledge/search",
                "/api/membership/points/log/2026.10",
                "/api/products/import/template.xlsx",
                "/api/design/files/logo.fig"
        };

        for (String path : paths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            if ("/api/merchant/tax-query".equals(path)) {
                request.setQueryString("period=2026Q3");
            } else if ("/api/admin/ai-models".equals(path)) {
                request.setQueryString("channel=deepseek");
            } else if ("/api/support/knowledge/search".equals(path)) {
                request.setQueryString("keyword=%E9%98%80%E9%97%A8");
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            boolean allowed = interceptor.preHandle(request, response, new Object());
            assertTrue(allowed, "合法业务路径不应被拦截: " + path);
            assertEquals(200, response.getStatus(), "合法业务路径不应返回错误状态: " + path);
        }
    }
}

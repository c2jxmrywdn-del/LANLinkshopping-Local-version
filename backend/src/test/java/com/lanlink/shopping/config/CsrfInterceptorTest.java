package com.lanlink.shopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 CSRF 长尾：拦截器本体语义（注册覆盖面在 WebConfig，方法级验证在此）。
 * 双端镜像约定：frontend/src/api/csrfPolicy.js 必须与 WebConfig 的 CsrfInterceptor
 * 路径清单同步，前端测试（csrfPolicy.test.js）镜像本类的豁免矩阵。
 */
class CsrfInterceptorTest {

    private final CsrfInterceptor interceptor = new CsrfInterceptor();

    @Test
    void passesGetAndOptionsWithoutToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("GET", "/order/checkout"), response, new Object()));
        assertTrue(interceptor.preHandle(request("OPTIONS", "/order/checkout"), response, new Object()));
        assertTrue(interceptor.preHandle(request("GET", "/cart/list"), response, new Object()));
    }

    @Test
    void tokenEndpointItselfIsNeverChallenged() throws Exception {
        // 令牌获取端点必须豁免，否则鸡生蛋死锁：无令牌 → 拉 /user/csrf-token → 403
        assertTrue(interceptor.preHandle(request("POST", "/user/csrf-token"), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void rejectsPostWithoutHeaderOrSessionToken() throws Exception {
        MockHttpServletRequest req = request("POST", "/order/checkout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, response, new Object()));
        // 拦截器沿用 R 业务体语义（HTTP 仍 200，code=403）：前端 request.js 按
        // code=403 + message 含 "CSRF" 识别并自动换新令牌重放
        assertTrue(response.getContentAsString().contains("CSRF"), "响应体须含 CSRF 标识供前端重放");
        assertTrue(response.getContentAsString().contains("403"), "业务体 code 须为 403");
    }

    @Test
    void rejectsHeaderNotMatchingSessionToken() throws Exception {
        MockHttpServletRequest req = request("POST", "/wallet/recharge");
        req.getSession().setAttribute(CsrfInterceptor.TOKEN_ATTR, "session-token");
        req.addHeader("X-CSRF-TOKEN", "forged-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, response, new Object()));
        assertTrue(response.getContentAsString().contains("CSRF"), "伪造令牌同样返回 CSRF 业务拒绝");
    }

    @Test
    void acceptsMatchingTokenForProtectedWrites() throws Exception {
        MockHttpServletRequest req = request("POST", "/cart/add");
        req.getSession().setAttribute(CsrfInterceptor.TOKEN_ATTR, "good-token");
        req.addHeader("X-CSRF-TOKEN", "good-token");
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void tokenHelperMintsAndReusesSessionToken() {
        MockHttpServletRequest req = request("GET", "/user/csrf-token");
        String first = CsrfInterceptor.token(req);
        String second = CsrfInterceptor.token(req);
        assertEquals(first, second, "同会话重复取令牌必须幂等");
        assertNotEquals(first, CsrfInterceptor.token(request("GET", "/user/csrf-token")), "不同会话令牌必须独立");
        assertNull(req.getSession().getAttribute("NON_EXIST_ATTR"));
        assertEquals(first, req.getSession().getAttribute(CsrfInterceptor.TOKEN_ATTR));
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setContextPath("/api");
        request.setRequestURI("/api" + path);
        return request;
    }
}

package com.lanlink.shopping.config;

import com.lanlink.shopping.common.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * CSRF 拦截器（仅作用于 /user/** 的写操作）：
 * - 令牌为会话级：GET /user/csrf-token 获取（幂等，未生成则生成），存 session attr CSRF_TOKEN
 * - 非 GET / OPTIONS 且非 /user/csrf-token 的 /user/** 请求，须携带 X-CSRF-TOKEN 头且与会话令牌一致
 * - 校验失败：R.fail(403, "CSRF校验失败...")——message 必须包含 "CSRF"，
 *   前端 request.js 检测到 403+CSRF 会自动换新令牌并重放一次该请求
 */
public class CsrfInterceptor implements HandlerInterceptor {

    public static final String TOKEN_ATTR = "CSRF_TOKEN";
    private static final String HEADER = "X-CSRF-TOKEN";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        String uri = request.getRequestURI().replace(request.getContextPath(), "");
        if ("/user/csrf-token".equals(uri)) {
            return true;
        }
        String sessionToken = (String) request.getSession().getAttribute(TOKEN_ATTR);
        String got = request.getHeader(HEADER);
        if (sessionToken == null || got == null || !sessionToken.equals(got)) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(R.fail(403, "CSRF校验失败，页面已过期，请刷新后重试")));
            return false;
        }
        return true;
    }

    /** 获取（或首次生成）当前会话的 CSRF 令牌 */
    public static String token(HttpServletRequest request) {
        String t = (String) request.getSession().getAttribute(TOKEN_ATTR);
        if (t == null) {
            t = UUID.randomUUID().toString().replace("-", "");
            request.getSession().setAttribute(TOKEN_ATTR, t);
        }
        return t;
    }
}
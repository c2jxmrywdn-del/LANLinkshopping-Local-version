package com.lanlink.shopping.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Locale;

/**
 * 安全路由与爆破防护拦截器：
 * 拦截自动化渗透工具（如 Burp Suite、DirBuster、sqlmap 等）的路径爆破、路径穿越及敏感路径探测。
 */
@Component
public class SecurityRouteGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SecurityRouteGuardInterceptor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 禁用的 HTTP 调试动词（防 Cross-Site Tracing） */
    private static final List<String> FORBIDDEN_METHODS = List.of("TRACE", "TRACK");

    /** 探测拦截的特征关键字列表（全小写匹配 URI 与 QueryString） */
    private static final List<String> SUSPICIOUS_PATTERNS = List.of(
            // 路径穿越
            "..", "%2e%2e",
            // 敏感配置与源码泄露
            ".env", ".git", ".svn", "application.yml", "application.properties", "web.config",
            // 数据库与备份文件探测
            ".bak", ".backup", ".sql", ".old", ".swp", ".tar.gz",
            // 敏感监控与运维入口探测
            "/actuator", "/swagger", "/api-docs", "/v2/api-docs", "/v3/api-docs",
            // 常见第三方爆破字典特征
            "/wp-admin", "/wp-login", "/phpmyadmin", "phpinfo", "/solr", "/drupal", "/manager/html"
    );

    /** 恶意的 SQL 注入特征 */
    private static final List<String> SQL_INJECTION_PROBES = List.of(
            "union select", "union%20select", "union+select",
            "select * from", "select%20*%20from", "select+*+from",
            "1=1--", "1' or '1'='1", "xp_cmdshell"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if (method != null && FORBIDDEN_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            reject(request, response, "HTTP 方法不被支持");
            return false;
        }

        String uri = request.getRequestURI();
        if (uri == null) uri = "";
        String normalizedUri = uri.toLowerCase(Locale.ROOT);

        String queryString = request.getQueryString();
        String normalizedQuery = queryString != null ? queryString.toLowerCase(Locale.ROOT) : "";

        // 放行官方健康检查探针端点（Docker / K8s / 容器存活检测）
        if (normalizedUri.endsWith("/actuator/health")) {
            return true;
        }

        // 1. 扫描与敏感路径检测
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (normalizedUri.contains(pattern) || normalizedQuery.contains(pattern)) {
                reject(request, response, "非法请求或受限制的访问路径");
                return false;
            }
        }

        // 2. URL 参数 SQL 注入/探针探测检测
        for (String sqlProbe : SQL_INJECTION_PROBES) {
            if (normalizedQuery.contains(sqlProbe)) {
                reject(request, response, "请求包含非法参数字符");
                return false;
            }
        }

        return true;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String msg) throws Exception {
        String clientIp = resolveIp(request);
        log.warn("[security-route-guard] 拦截恶意请求: ip={}, method={}, uri={}, query={}",
                clientIp, request.getMethod(), request.getRequestURI(), request.getQueryString());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(403, msg)));
    }

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("CF-Connecting-IP");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip == null ? "unknown" : ip;
    }
}

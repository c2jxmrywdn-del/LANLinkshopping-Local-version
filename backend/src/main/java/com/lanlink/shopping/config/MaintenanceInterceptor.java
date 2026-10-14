package com.lanlink.shopping.config;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 维护模式拦截器：读取动态配置 site.maintenance，为 true 时对非后台/非登录请求返回 503。
 * 无需重启，运营在后台切换开关即时生效（产品层降级预案）。
 */
public class MaintenanceInterceptor implements HandlerInterceptor {

    private final SysConfigService config;
    private final ObjectMapper om = new ObjectMapper();

    public MaintenanceInterceptor(SysConfigService config) {
        this.config = config;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!config.getBool("site.maintenance", false)) return true;
        String uri = request.getRequestURI().replace(request.getContextPath(), "");
        boolean allow = uri.startsWith("/admin/") || uri.startsWith("/auth/login") || uri.startsWith("/auth/me");
        if (allow) return true;
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(om.writeValueAsString(R.fail(503, "系统维护中，请稍后再试")));
        return false;
    }
}

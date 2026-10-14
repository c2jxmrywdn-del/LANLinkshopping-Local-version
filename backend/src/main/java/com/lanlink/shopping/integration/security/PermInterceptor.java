package com.lanlink.shopping.integration.security;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限校验拦截器（后端权威访问控制）。
 *
 * 执行顺序：AuthInterceptor（登录 + 身份实时识别 + 后台 RBAC）之后运行。
 * 读取 Handler 方法上的 @RequirePerm，按当前请求身份权限矩阵校验：
 *  - 身份为 GUEST（未登录）→ 401
 *  - 身份已登录但缺少所需权限 → 403 + ACCESS_DENIED 审计日志
 *
 * 权限标识与身份权限矩阵的映射见 common.UserIdentity。
 */
@Component
public class PermInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditService auditService;

    public PermInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;

        RequirePerm need = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequirePerm.class);
        // 无注解 / 权限值为 null 或空白：视为无需额外权限，直接放行（避免冗余检查与误拦截）
        if (need == null || need.value() == null || need.value().isBlank()) return true;

        UserIdentity identity = UserContext.identity(request);
        if (identity == null || identity == UserIdentity.GUEST) {
            write(response, R.fail(401, "请先登录"));
            return false;
        }
        if (!identity.has(need.value())) {
            User u = UserContext.current(request);
            auditService.record(u == null ? null : u.getUserId(), "ACCESS_DENIED",
                    "权限不足: 需要[" + need.value() + "]，当前身份[" + identity.getCode() + "] " + request.getRequestURI(),
                    request);
            write(response, R.fail(403, "无权限执行该操作"));
            return false;
        }
        return true;
    }

    private void write(HttpServletResponse response, R<?> body) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

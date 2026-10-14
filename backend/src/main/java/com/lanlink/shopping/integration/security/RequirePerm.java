package com.lanlink.shopping.integration.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解（后端权威访问控制）。
 *
 * 标注在 Controller 方法上，声明访问所需权限标识（对应 UserIdentity 权限矩阵），
 * 由 PermInterceptor 按当前请求身份校验；不满足返回 403 并记录 ACCESS_DENIED 审计日志。
 *
 * 用法示例：@RequirePerm("product:publish")
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {
    /** 所需权限标识，如 "product:publish"、"vip:discount"、"admin:all" */
    String value();
}

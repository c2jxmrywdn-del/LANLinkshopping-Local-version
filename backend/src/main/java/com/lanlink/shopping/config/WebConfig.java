package com.lanlink.shopping.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册拦截器 + 允许前端跨域 + 上传文件静态映射
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SecurityRouteGuardInterceptor securityRouteGuardInterceptor;
    private final AuthRateLimitInterceptor authRateLimitInterceptor;
    private final AuthInterceptor authInterceptor;
    private final com.lanlink.shopping.integration.security.PermInterceptor permInterceptor;

    @Value("${app.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;

    public WebConfig(SecurityRouteGuardInterceptor securityRouteGuardInterceptor,
                     AuthRateLimitInterceptor authRateLimitInterceptor,
                     AuthInterceptor authInterceptor,
                     com.lanlink.shopping.integration.security.PermInterceptor permInterceptor) {
        this.securityRouteGuardInterceptor = securityRouteGuardInterceptor;
        this.authRateLimitInterceptor = authRateLimitInterceptor;
        this.authInterceptor = authInterceptor;
        this.permInterceptor = permInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 安全路由守卫：最前置拦截第三方渗透工具路径穿越、恶意探针与探测扫描
        registry.addInterceptor(securityRouteGuardInterceptor)
                .addPathPatterns("/**");
        // 核心接口频次防护：防爆破/防暴力请求（登录/两步验证/注册/验证码/下单/支付）
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns(
                        "/auth/login", "/auth/login/2fa", "/auth/register",
                        "/user/phone-code", "/user/email-code",
                        "/order/checkout", "/wallet/pay", "/wallet/recharge"
                );
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**");
        // 权限校验：AuthInterceptor 之后执行（先保证登录与身份识别）
        registry.addInterceptor(permInterceptor)
                .addPathPatterns("/**");
        // CSRF：保护账号写操作与营销邮件发送（AuthInterceptor 之后执行，先保证已登录）
        registry.addInterceptor(new CsrfInterceptor())
                .addPathPatterns("/user/**", "/admin/marketing-email/**", "/admin/customer-communications/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns.split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** 头像等上传文件静态访问：{user.dir}/uploads/** → /api/uploads/** */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = System.getProperty("user.dir") + "/uploads/";
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + uploadDir);
    }
}

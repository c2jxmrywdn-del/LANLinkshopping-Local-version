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
                        "/order/checkout", "/wallet/pay", "/wallet/recharge",
                        // AI 推理端点（单请求 10-60s 串行 GPU 推理，防单用户占满队列）
                        "/kb/ask", "/kb/search",
                        // AI 工具网关（服务间密钥/API Key 鉴权前置 IP+入口限流，防伪造 Key 打爆数据库）
                        "/internal/ai/tools", "/internal/ai/tools/*", "/internal/ai/tools/**",
                        "/price-monitor/ai-analysis/*", "/price-monitor/ask",
                        // 公开/登录态写库上报端点（防匿名/登录刷库导致日志表膨胀与统计污染）
                        "/ab/expose", "/ab/convert", "/ad/track", "/merchant/traffic/events",
                        // 技术服务：C 端发布/申请/推进等写端点 + 运营台写操作。
                        // GET 在拦截器内缺省豁免（Policy.throttleGet=false），公开目录浏览与后台列表不受影响
                        "/tech/**", "/admin/tech-service/**",
                        // 材料上传/绑定：字节写操作开销大（解码+哈希+对象写入），必须与 POLICIES 同步登记，
                        // 只加策略不注册路径的话拦截器根本不执行，等于无限流（本项目踩过）
                        "/attachment/**",
                        // 商品主图上传（POLICIES 有 /product/image 条目）：5MB 图片解码+对象写入，须限流
                        "/product/image",
                        // AI 标题润色（POLICIES 有 /product/ai/* 前缀条目）：单请求 10-30s 模型推理，
                        // Spring Ant 语义注册（POLICIES 表内是 "/*" 前缀 startsWith 语义，勿混用）
                        "/product/ai/**",
                        // 物流跟踪回调推送（POLICIES 有 /logistics/callback/* 条目）：白名单公开路径，
                        // 用 ** 覆盖 /{provider} 与 /{provider}/{token} 两种形态（同上，只加策略不注册路径等于无限流）
                        "/logistics/callback/**",
                        // 营业执照查看验证门（POST 校验端点防验证码爆破；GET 端点缺省豁免且已有单次票据约束）
                        "/merchant/admin/license-verify"
                );
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**");
        // 权限校验：AuthInterceptor 之后执行（先保证登录与身份识别）
        registry.addInterceptor(permInterceptor)
                .addPathPatterns("/**");
        // CSRF（A11）：扩大写操作覆盖——/admin/** 整体（出站模型配置/知识库灌库/AB 管理等）、
        // 商户流量事件上报、价格预警订阅、商品主图上传。GET/OPTIONS 不校验，登录后前端自动携带令牌
        // （frontend/src/api/csrfPolicy.js 与本映射保持同步）。
        // 注意：/ab/expose、/ab/convert 为匿名公开上报（AuthInterceptor 白名单），匿名会话取不到
        // /user/csrf-token，纳入会直接 403 破坏公开上报——不纳入 CSRF，靠限流策略防刷。
        registry.addInterceptor(new CsrfInterceptor())
                .addPathPatterns("/user/**", "/admin/**", "/tech/**",
                        "/attachment/**",
                        "/merchant/traffic/events",
                        "/price-monitor/alerts", "/price-monitor/alerts/*",
                        "/product/image",
                        // AI 标题润色（登录态写端点，frontend/src/api/csrfPolicy.js 同步登记 /product/ai/）
                        "/product/ai/**",
                        // 资金与交易写路径（生产 SameSite=None 时，跨站页面可携带会话 Cookie 发这些请求）：
                        // 订单下单/取消/退款申请、钱包充值、支付建单/模拟确认/退款。
                        // 渠道回调（/payment/notify/**、/payment/alipay/return）**刻意不在列**——
                        // 它们由支付宝/微信服务器发起，没有用户会话，纳入会直接 403 掉真实回调。
                        "/order/**", "/wallet/**",
                        "/payment/create", "/payment/mock-confirm", "/payment/refund");
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

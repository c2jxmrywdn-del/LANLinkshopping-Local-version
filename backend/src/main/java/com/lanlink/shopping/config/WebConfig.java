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
                        // 注册验证码 / 忘记密码重置：匿名可达且触发短信发送，必须在拦截器路径内
                        "/auth/register-code", "/auth/password-reset",
                        // Apple form_post：跨站匿名 POST，按 IP 限频，避免被拿来刷回跳
                        "/auth/social/apple/return",
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
                        // RFQ 询价发布与后续报价/接受写端点（GET 列表与详情在拦截器内缺省豁免）
                        "/rfq", "/rfq/**",
                        // 商户侧 RFQ 报价（登录 + merchant:manage + CSRF；GET 缺省豁免）
                        "/merchant/rfq/**",
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
                        // ---- P1-11 限流长尾（POLICIES 已同步加条目）。注册用 Ant ** 匹配到最深
                        // 端点（如 /chat/{id}/messages 三层、/live/room/{id}/product/{id}/click 五层）：
                        // 单层 * 匹配不到深层路径，拦截器根本不执行，等于无限流（本项目踩过）。
                        // 表内键是 startsWith 前缀语义，GET 缺省豁免，公开浏览不受影响 ----
                        // 直播：匿名互动 /live/room/**（点赞/弹幕/进出场/点击）+ 商户直播台 /live/merchant/**
                        "/live/room/**", "/live/merchant/**",
                        // IM：买家单聊与商户客服（发消息/媒体上传/建会话）
                        "/chat/**", "/merchant/center/chat/**",
                        // 营销图 AI 生成（秒级图像推理 + 计费外呼）
                        "/merchant/marketing-image/**",
                        // 头像上传、e-POD 回单、轨迹同步、任务奖励领取、会员签到
                        "/user/avatar", "/logistics/pod/**", "/logistics/trace/**", "/task/claim", "/membership/checkin",
                        // 物流写端点（发货/拆单/中转/运力推荐/看门狗巡检与催办；POLICIES 各有对应条目）
                        "/logistics/ship/**", "/logistics/transit/**",
                        "/logistics/recommend-carriers", "/logistics/watchdog/**",
                        // 活动域领券/参与 + 广告推广 + 账期资金操作 + 订阅
                        "/campaign/**", "/ad/campaigns", "/ad/campaigns/*",
                        "/credit-term/**", "/subscription/**",
                        // 订单后续写操作（支付/确认/取消/退款/RMA/买家评价，POLICIES 前缀键 /order/*；
                        // /order/checkout 精确键已在上方）
                        "/order/**",
                        // 商户发品/编辑 + 入驻申请与证照/税证上传（POLICIES 各有对应条目）
                        "/product/publish", "/product/my/**",
                        // 商户 SKU 多规格树保存（POST /{prodId}/save）：虽已被 /product/my/** 覆盖，
                        // 仍显式登记以便与 POLICIES 的 /product/my/sku/* 键逐条对齐（SKU 切片 Task 3）
                        "/product/my/sku/**",
                        "/merchant/apply", "/merchant/apply-upload", "/merchant/license", "/merchant/tax-proof",
                        // 营业执照查看验证门（POST 校验端点防验证码爆破；GET 端点缺省豁免且已有单次票据约束）
                        "/merchant/admin/license-verify",
                        // 购物车写端点、加盟申请、买家工单提交、客服意图分析
                        "/cart/**", "/join/**", "/support-ticket/**", "/support/intent/analyze",
                        // 商户结算管理端写端点（对账/生成批次/审核/打款登记；POLICIES 有
                        // /admin/settlement/* 前缀条目）。须与本映射同步登记——只加策略不注册路径等于无限流。
                        // 注：PUT /commission/{merId} 走同一前缀但当前 Policy 仅限 POST，故佣金率写未实际限流（已知）。
                        "/admin/settlement/**",
                        // 商户退店（申请/撤回/审核/关闭/公示扫表；GET 缺省豁免）——POLICIES 有对应前缀条目，
                        // 须与本映射同步登记：只加策略不注册路径等于无限流（本项目踩过）
                        "/merchant/exit/**", "/admin/merchant-exit/**",
                        // 商户提现（申请/撤回/余额/台账；GET 缺省豁免）与管理端提现审核
                        // （通过登记凭证/驳回解冻）——POLICIES 有对应前缀条目，须与本映射同步登记
                        "/merchant/finance/**", "/admin/finance/**",
                        // 邀请链接：GET /invite/link 首次访问会生成并写库，须开启 GET 限流；
                        // rotate 与运营发码为登录态写端点。POLICIES 已同步登记，避免
                        // "只加策略不注册路径等于无限流"
                        "/invite/link", "/invite/link/rotate", "/invite/admin/merchant-apply"
                );
        // 启动期一致性断言：POLICIES 里的每条认证类策略键都必须已被上面注册，
        // 否则拦截器不执行、等于完全无限流（本项目已踩过两次）。
        java.util.Set<String> registered = new java.util.LinkedHashSet<>();
        for (String p : new String[]{
                "/auth/login", "/auth/login/2fa", "/auth/register",
                "/auth/register-code", "/auth/password-reset",
                "/auth/social/apple/return",
                "/user/phone-code", "/user/email-code"}) {
            registered.add(p);
        }
        for (String key : AuthRateLimitInterceptor.policyKeys()) {
            if (!key.startsWith("/auth/")) continue;
            if (!registered.contains(key)) {
                throw new IllegalStateException(
                        "限流策略键 " + key + " 未在 WebConfig.addPathPatterns 注册：该端点实际不受限流保护");
            }
        }
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**");
        // 权限校验：AuthInterceptor 之后执行（先保证登录与身份识别）
        registry.addInterceptor(permInterceptor)
                .addPathPatterns("/**");
        // CSRF（A11 + P0-2 长尾补齐）：写操作全覆盖——见下。GET/OPTIONS 不校验，登录后前端自动携带
        // 令牌（frontend/src/api/csrfPolicy.js 与本映射保持同步）。
        // 豁免面（均为无登录会话的匿名/服务间/渠道调用，纳入会直接 403）：
        //  - /ab/expose、/ab/convert、/ad/track：匿名公开上报（AuthInterceptor 白名单），靠限流策略防刷
        //  - /payment/notify/**、/payment/alipay/return：支付宝/微信服务器回调（无用户会话）
        //  - /logistics/callback/**：承运商服务器推送（无用户会话）
        //  - /auth/login、/auth/register、/auth/social/callback/**、/auth/social/sandbox-login：
        //    匿名入口（回调由 OAuth 提供方服务器发起）；bind/unbind 是登录态操作，纳入
        //  - /support/ask、/support/ask-stream、/support/media/**、/support/tts/**：
        //    可信代理密钥通道（前端 fetch 直调不走 axios，第三方/移动端经代理调用无会话）
        //  - /internal/ai/tools/**：服务间密钥鉴权（AiToolGuard）
        //  - /marketing-email/unsubscribe：签名 token 一次性退订链接
        //  - /live/room/**：直播间匿名互动（点赞/弹幕/进出场/点击，昵称降级白名单）；商户直播台 /live/merchant/** 纳入
        //  - 各公开浏览 GET 端点天然不校验（拦截器只拦非 GET）
        registry.addInterceptor(new CsrfInterceptor())
                .addPathPatterns(
                        // 账号域：资料/地址/消息/验证码/习惯上报（含 /user/habits/report）
                        "/user/**",
                        // 运营后台整体（出站模型配置/知识库灌库/AB 管理/物流后台/营销邮件等）
                        "/admin/**",
                        // 商户结算管理端写端点（对账/生成批次/审核/打款登记/佣金率）：与前端
                        // csrfPolicy.js /^\/admin\/settlement\// 同步；已被上一条 /admin/** 覆盖，显式登记以自注释
                        "/admin/settlement/**",
                        // 商户退店（与前端 csrfPolicy.js /^\/merchant\/exit\//、/^\/admin\/merchant-exit\// 同步）
                        "/merchant/exit/**", "/admin/merchant-exit/**",
                        // 技术服务 + RFQ
                        "/tech/**", "/rfq/**",
                        // 商户域整体：入驻申请/证照上传、商户中心（联系/银行/退款审批）、
                        // 商户 RFQ 报价、商户客服 IM、营销图任务（收编原 /merchant/rfq/** 单条）
                        "/merchant/**",
                        // 材料/商品：附件上传绑定、主图上传、AI 润色、发品与商家编辑
                        "/attachment/**",
                        "/product/image", "/product/ai/**",
                        "/product/publish", "/product/my/**",
                        // 商品管理端写端点（重索引/批量审核/上下架/审核/自动审核阈值）：
                        // 不在 /admin/** 下，必须显式登记，否则管理端写操作可被跨站伪造
                        "/product/admin/**",
                        // 商户 SKU 多规格树读写（SKU 切片 Task 3）：与前端 csrfPolicy.js
                        // /^\/product\/my\/sku\// 同步；已被上一条 /product/my/** 覆盖，显式登记以自注释
                        "/product/my/sku/**",
                        // 商户流量事件上报（登录态写库）——被 /merchant/** 覆盖，保留显式条目以自注释
                        "/merchant/traffic/events",
                        // 价格预警订阅 + AI 问答
                        "/price-monitor/alerts", "/price-monitor/alerts/*", "/price-monitor/ask",
                        // 价格监控管理端写端点（因子重算/单商品刷新）：同样不在 /admin/** 下
                        "/price-monitor/admin/**",
                        // 知识库问答（登录态写端点，前端暂无直调方）
                        "/kb/ask",
                        // 资金与交易写路径：订单（含退款/确认/取消/RMA）、钱包、支付建单/模拟确认/退款
                        "/order/**", "/wallet/**",
                        "/payment/create", "/payment/mock-confirm", "/payment/refund",
                        // 购物车写操作（加购/改数量/勾选/删除）
                        "/cart/**",
                        // 活动域：领券/参与/批量领券/券包计价/商户促销（浏览端点为 GET 天然放行）
                        "/campaign/**",
                        // 会员签到 + 订阅购买/取消
                        "/membership/checkin", "/subscription/**",
                        // 账期域：申请/还款/调额/撤回/试算/结算支付（含 /credit-term/admin/** 审核）
                        "/credit-term/**",
                        // 任务领取 + 加盟申请
                        "/task/claim", "/join/**",
                        // 广告推广商户端（创建/支付/暂停/恢复/终止）；/ad/track 匿名上报豁免
                        "/ad/campaigns/**",
                        // IM 单聊（建会话/发消息/媒体）
                        "/chat/**",
                        // 直播商户台（开播/置顶商品/状态流转）；匿名观众互动 /live/room/** 豁免
                        "/live/merchant/**",
                        // 物流写端点（发货/拆单/回单/审核/中转/轨迹同步/运力推荐/看门狗）；
                        // /logistics/callback/** 渠道回调豁免
                        "/logistics/ship/**", "/logistics/pod/**", "/logistics/transit/**",
                        "/logistics/trace/**", "/logistics/recommend-carriers", "/logistics/watchdog/**",
                        // 买家工单提交 + 客服意图分析 + AI 备用链路状态（admin POST）
                        "/support-ticket/**", "/support/intent/analyze", "/support/fallback/status",
                        // 邀请链接重新生成（登录态写端点）：GET /invite/link 天然放行，
                        // 仅 POST /invite/link/rotate 需要 CSRF；与前端 csrfPolicy.js /^\/invite\// 同步
                        "/invite/**",
                        // 登录态账号操作：登出、社交账号绑定/解绑（callback/sandbox-login 匿名豁免）
                        "/auth/logout", "/auth/social/bind/**", "/auth/social/unbind/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns.split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 上传文件静态访问：{user.dir}/uploads/** → /api/uploads/**。
     *
     * <p>安全边界：证照类目录（licenses / tax-proofs / special-permits）**不走**静态映射。
     * 它们只允许经受控端点读取（管理端查看需验证码 + 审计），否则任何登录用户都能凭
     * `/api/uploads/licenses/{userId}_{时间戳}.jpg` 猜到并读取他人营业执照。
     * 这里用多个具名 location 精确挂载其余目录，避免把整个 uploads 根暴露出去。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadDir = System.getProperty("user.dir") + "/uploads/";
        // 头像与聊天媒体：非证照，保持原静态访问（登录即可读，与历史行为一致）
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations("file:" + uploadDir + "avatars/");
        registry.addResourceHandler("/uploads/chat/**")
                .addResourceLocations("file:" + uploadDir + "chat/");
        registry.addResourceHandler("/uploads/products/**")
                .addResourceLocations("file:" + uploadDir + "products/");
    }
}

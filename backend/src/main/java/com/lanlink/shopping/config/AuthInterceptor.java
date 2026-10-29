package com.lanlink.shopping.config;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录 + RBAC 拦截器（同时承担用户身份识别与越权审计）。
 * 规则:
 *  - 白名单(注册/登录/公开浏览)直接放行
 *  - 每次请求实时识别用户身份（GUEST/BUYER/VIP/MERCHANT/ADMIN），写入 request attribute
 *  - /admin/** 需平台运营(role_id=3)，越权访问记录 ACCESS_DENIED 审计日志
 *  - 其余需登录
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdentityService identityService;
    private final AuditService auditService;

    public AuthInterceptor(IdentityService identityService, AuditService auditService) {
        this.identityService = identityService;
        this.auditService = auditService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI().replace(request.getContextPath(), "");

        // Support requests are authenticated by the server-side proxy before any identity/database lookup.
        if (uri.equals("/support/ask")) return true;

        // 健康探针不创建会话，也不查询用户/订单数据；只允许公开的状态端点。
        if (isPublicHealthCheck(uri)) {
            return true;
        }

        // 实时身份识别（每次请求现场计算，保证准确性与实时性；身份变更无需重新登录即平滑切换）
        UserIdentity identity = identityService.identify(request);
        request.setAttribute(UserContext.IDENTITY_KEY, identity);

        if (isWhiteList(uri)) {
            return true;
        }
        // 统一读取一次当前用户：既做登录校验，也供后续 /admin/** 鉴权使用，避免重复查询与空指针风险
        User u = UserContext.current(request);
        if (u == null) {
            write(response, R.fail(401, "请先登录"));
            return false;
        }
        // 运营后台鉴权（越权访问记录审计日志）
        if (uri.startsWith("/admin/") || uri.contains("/admin/")) {
            if (!com.lanlink.shopping.common.UserRoles.isAdmin(u.getRoleId())) {
                auditService.record(u.getUserId(), "ACCESS_DENIED", "越权访问后台: " + uri, request);
                write(response, R.fail(403, "无权限：仅平台运营可操作"));
                return false;
            }
        }
        return true;
    }

    private boolean isPublicHealthCheck(String uri) {
        return uri.equals("/actuator/health")
                || uri.equals("/actuator/health/liveness")
                || uri.equals("/actuator/health/readiness");
    }

    private boolean isWhiteList(String uri) {
        return uri.startsWith("/auth/login")
                || uri.startsWith("/auth/register")
                // 商品推荐位公开浏览（个性化仅在 analytics 授权下生效，服务端自动降级）
                || uri.startsWith("/recommendations/")
                // A/B 实验：分流/曝光/转化公开上报（匿名用 sessionId 分桶，管理端 CRUD 在 /admin/**）
                || uri.startsWith("/ab/")
                // 社交登录：仅放行匿名可达的入口；/auth/social/bind/** 需登录（绑定动作挂在当前账号下）
                || uri.equals("/auth/social/providers")
                || uri.startsWith("/auth/social/authorize/")
                || uri.startsWith("/auth/social/callback/")
                || uri.startsWith("/auth/social/sandbox-login")
                // 语言自动识别的地理提示：启动期（登录前）即需调用
                || uri.startsWith("/i18n/")
                || uri.startsWith("/home/")
                || uri.startsWith("/category/")
                // 商品仅公开浏览(分页/详情)；发布/图片上传/我的列表/审核均需登录
                || uri.equals("/product/page")
                || uri.startsWith("/product/detail/")
                // 活动中心公开浏览（列表/详情）；领券/参与/券包/商户端均需登录
                || uri.equals("/campaign/list")
                || uri.startsWith("/campaign/detail/")
                // 价格监控：商品价格走势、五维因子与市场资讯公开浏览（商品详情页/首页行情）
                || uri.startsWith("/price-monitor/trend/")
                || uri.startsWith("/price-monitor/factors/")
                || uri.startsWith("/price-monitor/market/")
                || uri.equals("/price-monitor/news")
                // 直播间公开浏览（广场/详情/商品/弹幕/进出场/点击埋点）；点赞评论可匿名（昵称降级），商户端与复盘需权限
                || uri.equals("/live/rooms")
                || uri.startsWith("/live/room/")
                || uri.startsWith("/live/product/")
                || uri.startsWith("/live/room-by-product/")
                // 订阅方案列表公开浏览；购买/我的/取消需登录
                || uri.equals("/subscription/plans")
                // 支付渠道异步通知与同步回跳：无登录态，公开放行（内部已做签名校验）
                || uri.startsWith("/payment/notify/")
                || uri.equals("/payment/alipay/return")
                // 广告推广：商城广告位与曝光/点击上报公开（游客也可浏览商城广告位）
                || uri.equals("/ad/slots")
                || uri.equals("/ad/track")
                // 商品直播：直播广场、直播间详情、商品柜、互动弹幕、点赞公开放行
                || uri.equals("/live/rooms")
                || uri.startsWith("/live/room/")
                // 商品详情页按商品取直播间（游客可看生产线直播）
                || uri.startsWith("/live/room-by-product/")
                // Public one-click marketing unsubscribe uses a signed, account-bound token.
                || uri.equals("/marketing-email/unsubscribe");
    }

    private void write(HttpServletResponse response, R<?> body) throws Exception {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

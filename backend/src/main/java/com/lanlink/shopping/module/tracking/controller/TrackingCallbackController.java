package com.lanlink.shopping.module.tracking.controller;

import com.lanlink.shopping.module.tracking.service.TrackingIngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 承运商/聚合商推送回调端点（out/logistics-tracking-plan.md §4.1）。
 *
 * 路径：POST /logistics/callback/{provider} 与 /logistics/callback/{provider}/{token}
 * （17TRACK/Mock 的 URL 路径段 token 形态，UrlTokenVerifier 校验）。
 * 该前缀在 AuthInterceptor 白名单公开放行（无登录态），安全由 控制器内验签 +
 * WebConfig 注册的 AuthRateLimitInterceptor 限流（Policy 120/600）+ 事件幂等 三层兜底；
 * 不纳入 CSRF（服务器间调用无会话令牌，与 /payment/notify/ 同一决策）。
 *
 * 应答语义（§4.5）：验签失败 401 {"success":false,"errorCode":"SIGN_INVALID"}；
 * 成功按渠道返回（快递100 {"result":true,"returnCode":"200"}，abort 亦成功）；
 * 解析/入库异常统一成功应答 + event failed（补拉兜底）。全部在本链路内显式返回，
 * 不让异常逃逸到 GlobalExceptionHandler 变 500（500 会触发渠道按临时错误重推）。
 */
@RestController
public class TrackingCallbackController {

    private final TrackingIngestService ingestService;

    public TrackingCallbackController(TrackingIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/logistics/callback/{provider}/**")
    public ResponseEntity<Map<String, Object>> onCallback(@PathVariable("provider") String provider,
                                                          HttpServletRequest request,
                                                          @RequestBody(required = false) String rawBody) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String prefix = "/logistics/callback/" + provider + "/";
        String token = null;
        if (uri.startsWith(prefix) && uri.length() > prefix.length()) {
            token = uri.substring(prefix.length()).split("/")[0];
        }

        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }

        TrackingIngestService.CallbackAck ack = ingestService.handle(
                provider, rawBody, headers, uri, token, request.getRemoteAddr());
        // 验签失败必须回真实 HTTP 401（§4.3：明确 401/403 而非 500——500 会被渠道按临时错误重推）
        return ResponseEntity.status(ack.httpStatus()).body(ack.body());
    }
}

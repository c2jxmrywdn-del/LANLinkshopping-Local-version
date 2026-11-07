package com.lanlink.shopping.module.tracking.security;

import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * URL 路径段 token 验签（out/logistics-tracking-plan.md §4.2）：Mock 与 17TRACK 共用。
 * 17TRACK 官方无推送侧验签机制，订阅时把自生成 secret token 放进回调 URL 路径段
 * /logistics/callback/track17/{token}，服务端常量时间比对——这是官方无验签前提下
 * 唯一服务端可控手段（openQuestion #6 安全评审待确认）。
 * token 必须用 hex/普通词：不得触发 SecurityRouteGuardInterceptor 的 SUSPICIOUS_PATTERNS
 * （对 URI+QueryString 小写匹配 ".."、".env"、".sql" 等特征）；禁止把 token 放 query 传。
 * token 从 TrackingProperties 读（TRACKING_CALLBACK_URL_TOKEN）；默认值仅 dev 演示可用。
 */
@Component
public class UrlTokenVerifier implements WebhookVerifier {

    private final TrackingProperties properties;

    public UrlTokenVerifier(TrackingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Set<String> supportedProviders() {
        return Set.of("mock", "track17");
    }

    @Override
    public VerifyResult verify(VerifyRequest request) {
        String expected = properties.getCallbackUrlToken();
        String provided = request.token();
        if (expected == null || expected.isBlank()) {
            return VerifyResult.fail("callback url token not configured");
        }
        if (provided == null || provided.isBlank()) {
            return VerifyResult.fail("callback url token missing");
        }
        return Kuaidi100Verifier.constantTimeEquals(expected, provided)
                ? VerifyResult.ok()
                : VerifyResult.fail("callback url token mismatch");
    }
}

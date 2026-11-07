package com.lanlink.shopping.module.tracking.security;

import java.util.Map;
import java.util.Set;

/**
 * 渠道推送验签策略接口（out/logistics-tracking-plan.md §4.2 验签矩阵）。
 * 失败一律 fail-closed：无法确认来源的请求不能进入落库链路。
 */
public interface WebhookVerifier {

    /** 本策略覆盖的渠道编码（回调 URL {provider} 段词汇表） */
    Set<String> supportedProviders();

    VerifyResult verify(VerifyRequest request);

    /** 验签输入：原文 + 头 + URI 路径段 token（17TRACK/Mock 的 URL token 验签用） */
    record VerifyRequest(String providerCode, String rawBody, Map<String, String> headers,
                         String uri, String token) {
    }

    record VerifyResult(boolean pass, String reason) {
        public static VerifyResult ok() { return new VerifyResult(true, null); }
        public static VerifyResult fail(String reason) { return new VerifyResult(false, reason); }
    }
}

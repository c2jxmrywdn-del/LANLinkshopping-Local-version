package com.lanlink.shopping.module.tracking.security;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 验签策略注册表：渠道编码 → WebhookVerifier（按 verifier.supportedProviders() 自动装配）。
 * 未注册渠道返回 null，上层按 404 处理——不引入"默认放行"兜底。
 */
@Component
public class WebhookVerifierRegistry {

    private final Map<String, WebhookVerifier> byProvider = new HashMap<>();

    public WebhookVerifierRegistry(List<WebhookVerifier> verifiers) {
        for (WebhookVerifier verifier : verifiers) {
            for (String provider : verifier.supportedProviders()) {
                byProvider.put(provider, verifier);
            }
        }
    }

    public WebhookVerifier forProvider(String providerCode) {
        return providerCode == null ? null : byProvider.get(providerCode);
    }
}

package com.lanlink.shopping.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社交登录配置（app.social.*，见 application-social.yml）。
 *
 * 设计原则：
 *  - 默认全部关闭；只有「启用 + 凭证齐全」才视为可用（configured），避免半配置导致运行期异常；
 *  - 凭证一律来自环境变量，不落仓库；
 *  - 微信/支付宝用 appId 命名，Apple/Google 用 clientId 命名，此处统一归一化。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.social")
public class SocialLoginProperties {

    /** 支付宝渠道标识（签名用私钥而非 appSecret，可用性判定单独处理） */
    private static final String ALIPAY = "alipay";

    /** 授权完成后回跳的前端地址，需与各平台后台登记的 redirect_uri 一致 */
    private String redirectBase = "http://localhost:5173/login";

    /** 是否启用沙箱/本地模拟登录模式（开发/演示环境无公网凭证时允许快速体验） */
    private boolean sandboxEnabled = false;

    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Data
    public static class Provider {
        private boolean enabled;
        /** 微信开放平台 / 支付宝开放平台 使用 */
        private String appId;
        private String appSecret;
        /** Apple / Google 使用 */
        private String clientId;
        private String clientSecret;

        /**
         * 支付宝：商户应用私钥（PKCS#8 PEM）。
         * 支付宝开放平台的 RSA2 请求签名必须用这把私钥，
         * appSecret 是平台下发的密钥（用于其它场景），**不能用于签名**。
         */
        private String alipayPrivateKey;

        /** 归一化取应用标识（appId 优先，其次 clientId） */
        public String resolveId() {
            return notBlank(appId) ? appId : clientId;
        }

        /** 归一化取应用密钥 */
        public String resolveSecret() {
            return notBlank(appSecret) ? appSecret : clientSecret;
        }

        private static boolean notBlank(String v) {
            return v != null && !v.isBlank();
        }
    }

    /**
     * 该渠道是否真正可用：已启用 + 应用标识齐全 + 该渠道签名/换取真正需要的凭证齐全。
     * 支付宝例外：它的调用链全程不使用 appSecret（签名走应用私钥），
     * 因此只要求私钥在位，避免出现「显示已启用、回调换取时签名失败」的半配置状态。
     */
    public boolean configured(String provider) {
        Provider p = providers == null ? null : providers.get(provider);
        if (p == null || !p.isEnabled() || !Provider.notBlank(p.resolveId())) return false;
        if (ALIPAY.equals(provider)) return Provider.notBlank(p.getAlipayPrivateKey());
        return Provider.notBlank(p.resolveSecret());
    }

    public Provider provider(String provider) {
        return providers == null ? null : providers.get(provider);
    }
}

package com.lanlink.shopping.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 字段加密配置（前缀 lanlink.crypto）。
 *
 * <p>为什么用 @Component + @ConfigurationProperties 而不是在 yml 里写死默认值：
 * 密钥必须由部署环境（LANLINK_CRYPTO_JWE_* 环境变量）注入，仓库里绝不能出现真实私钥；
 * 一旦在 yml 里写 `secret: ${LANLINK_CRYPTO_SECRET:}` 这类占位，缺省空串会覆盖环境变量，
 * 反而让"省略未配置"与"配置为空"不可区分。所以这里只做绑定，默认值留给代码里的兜底逻辑。</p>
 */
@Component
@ConfigurationProperties(prefix = "lanlink.crypto")
public class CryptoProperties {

    /** legacy AES-256-GCM 主密钥（历史密文兼容期内必填才能读出旧数据）；故意不给默认值 */
    private String secret;

    /** 绝对安全底线：2048 位（已彻底淘汰已攻破的 RSA-1024） */
    public static final int ABSOLUTE_MIN_RSA_BITS = 2048;

    /** RSA 密钥最小位数闸门：强制不低于 2048 位，避免弱密钥配置渗透 */
    private int minRsaBits = ABSOLUTE_MIN_RSA_BITS;

    private Jwe jwe = new Jwe();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public int getMinRsaBits() {
        return Math.max(minRsaBits, ABSOLUTE_MIN_RSA_BITS);
    }

    public void setMinRsaBits(int minRsaBits) {
        if (minRsaBits < ABSOLUTE_MIN_RSA_BITS) {
            this.minRsaBits = ABSOLUTE_MIN_RSA_BITS;
            return;
        }
        this.minRsaBits = minRsaBits;
    }

    public Jwe getJwe() {
        return jwe;
    }

    public void setJwe(Jwe jwe) {
        this.jwe = jwe;
    }

    /**
     * JWE 密钥与开关。
     * active = 当前用于加密的密钥对；previous = 轮换期只读旧私钥，保证历史 JWE 仍可解密。
     */
    public static class Jwe {

        /** 是否启用 JWE 加密（关闭后回退 legacy AES-GCM，仅供过渡期排障使用） */
        private boolean enabled = true;

        /** 是否强制要求 JWE 可用：true 时密钥缺失/不可用会直接启动失败，避免静默降级写旧格式 */
        private boolean required = false;

        /** 当前密钥 kid，会写进 JWE header，解密时按 kid 选私钥 */
        private String activeKid = "";

        /** 当前私钥（PKCS#8 DER 的 Base64，单行无 PEM 头尾） */
        private String activePrivateKey = "";

        /** 当前公钥（X509 SubjectPublicKeyInfo DER 的 Base64，单行无 PEM 头尾） */
        private String activePublicKey = "";

        /** 轮换期旧 kid（只用于解密） */
        private String previousKid = "";

        /** 轮换期旧私钥（只用于解密） */
        private String previousPrivateKey = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getActiveKid() {
            return activeKid;
        }

        public void setActiveKid(String activeKid) {
            this.activeKid = activeKid;
        }

        public String getActivePrivateKey() {
            return activePrivateKey;
        }

        public void setActivePrivateKey(String activePrivateKey) {
            this.activePrivateKey = activePrivateKey;
        }

        public String getActivePublicKey() {
            return activePublicKey;
        }

        public void setActivePublicKey(String activePublicKey) {
            this.activePublicKey = activePublicKey;
        }

        public String getPreviousKid() {
            return previousKid;
        }

        public void setPreviousKid(String previousKid) {
            this.previousKid = previousKid;
        }

        public String getPreviousPrivateKey() {
            return previousPrivateKey;
        }

        public void setPreviousPrivateKey(String previousPrivateKey) {
            this.previousPrivateKey = previousPrivateKey;
        }
    }
}

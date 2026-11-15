package com.lanlink.shopping.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 字段级加解密服务（版本化封装：JWE 优先，legacy AES-256-GCM 兜底）。
 *
 * <p>存储格式不做 DDL：JWE 自带 header（含 kid/alg/enc），legacy 密文是
 * {@code Base64(IV12 + CT + Tag16)}，两者形态天然可分——按是否 5 段（4 个 '.'）判定。
 * 因此迁移期可以逐字段重写，无需 key_id / cipher_version 列。</p>
 *
 * <p>失败语义保持旧约定：{@link #decrypt(String)} 对坏数据返回 null（业务侧沿用"空即未配置"），
 * 新代码需要区分"解密失败"与"本来就是空"时用 {@link #decryptStrict(String)}。</p>
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String DEFAULT_SECRET = "LANLink-Default-Secret-ChangeMe";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static CryptoService INSTANCE;

    private final SecretKeySpec keySpec;
    private final JweCryptoService jweCryptoService;

    @Autowired
    public CryptoService(CryptoProperties properties, JweCryptoService jweCryptoService) {
        this(properties == null ? null : properties.getSecret(), jweCryptoService);
    }

    /** 兼容手工构造（单测/脚本）：只配 legacy 主密钥，JWE 不可用 */
    public CryptoService(String secret) {
        this(secret, null);
    }

    public CryptoService(String secret, JweCryptoService jweCryptoService) {
        this.jweCryptoService = jweCryptoService;
        try {
            // 主密钥留空时沿用历史默认值：保持既有行为，是否允许由 CryptoKeyGuard 在 prod 下裁决
            String effective = (secret == null || secret.isBlank()) ? DEFAULT_SECRET : secret;
            byte[] k = MessageDigest.getInstance("SHA-256").digest(effective.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
        INSTANCE = this;
    }

    public static CryptoService inst() { return INSTANCE; }

    /** 判断是否为 JWE compact 密文：恰好 5 段；legacy Base64 不含 '.' */
    public static boolean isJwe(String stored) {
        if (stored == null || stored.isEmpty()) return false;
        int dots = 0;
        for (int i = 0; i < stored.length(); i++) {
            if (stored.charAt(i) == '.') {
                if (++dots > 4) return false;
            }
        }
        return dots == 4;
    }

    /** 新写入一律 JWE；JWE 不可用时回退 legacy，保证过渡期可写 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        if (jweCryptoService != null && jweCryptoService.available()) {
            return jweCryptoService.encrypt(plain);
        }
        return encryptLegacy(plain);
    }

    /**
     * 双读：JWE 走 RSA 私钥，其余按 legacy AES-GCM。
     * 失败返回 null（既有语义），日志只记长度与前缀，绝不落明文。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        try {
            return decryptStrict(stored);
        } catch (RuntimeException e) {
            log.warn("字段解密失败，按空值处理：算法={}, len={}, prefix={}, cause={}",
                    isJwe(stored) ? "JWE" : "AES-GCM", stored.length(), safePrefix(stored), e.getMessage());
            return null;
        }
    }

    /** 严格解密：密文损坏/密钥不匹配/未知 kid 一律抛 {@link CryptoDecryptException} */
    public String decryptStrict(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        if (isJwe(stored)) {
            if (jweCryptoService == null) throw new CryptoDecryptException("JWE 密钥服务未初始化");
            return jweCryptoService.decrypt(stored);
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = Arrays.copyOf(all, GCM_IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, GCM_IV_BYTES, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CryptoDecryptException("legacy AES-GCM 解密失败", e);
        }
    }

    private String encryptLegacy(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /** 日志脱敏前缀：密文本身可外露，但完整值没必要进日志 */
    private static String safePrefix(String s) {
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    /** 脱敏：仅保留末 4 位 */
    public static String mask(String s) {
        if (s == null || s.length() <= 4) return s;
        return "****" + s.substring(s.length() - 4);
    }
}

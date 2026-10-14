package com.lanlink.shopping.crypto;

import org.springframework.beans.factory.annotation.Value;
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
 * 字段级 AES-256-GCM 加解密服务。
 * 密钥由配置 lanlink.crypto.secret 经 SHA-256 派生（生产用环境变量注入，勿硬编码）。
 * 存储格式：Base64( IV(12B) + 密文 + GCM认证标签(16B) )，每次加密 IV 随机。
 */
@Service
public class CryptoService {

    private static CryptoService INSTANCE;
    private final SecretKeySpec keySpec;

    public CryptoService(@Value("${lanlink.crypto.secret:LANLink-Default-Secret-ChangeMe}") String secret) {
        try {
            byte[] k = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
        INSTANCE = this;
    }

    public static CryptoService inst() { return INSTANCE; }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public String decrypt(String b64) {
        if (b64 == null || b64.isEmpty()) return b64;
        try {
            byte[] all = Base64.getDecoder().decode(b64);
            byte[] iv = Arrays.copyOf(all, 12);
            byte[] ct = Arrays.copyOfRange(all, 12, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 脱敏：仅保留末 4 位 */
    public static String mask(String s) {
        if (s == null || s.length() <= 4) return s;
        return "****" + s.substring(s.length() - 4);
    }
}

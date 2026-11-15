package com.lanlink.shopping.crypto;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 标准 RSA 密钥工具类（强制 RSA2048+ 安全基线，彻底移除 RSA1024 支持）。
 */
public final class RsaKeyUtil {

    /** 默认推荐密钥位长：2048 位 */
    public static final int DEFAULT_KEY_SIZE = 2048;

    /** 绝对最低密钥位长下限：2048 位（禁止使用已不具备现实安全强度的 RSA-1024） */
    public static final int ABSOLUTE_MIN_RSA_BITS = 2048;

    private static final String ALGORITHM = "RSA";

    private RsaKeyUtil() {
    }

    /**
     * 生成标准 RSA 2048 位密钥对
     */
    public static KeyPair generateKeyPair() {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * 生成指定位长 RSA 密钥对（强制 >= 2048 位）
     */
    public static KeyPair generateKeyPair(int keySize) {
        if (keySize < ABSOLUTE_MIN_RSA_BITS) {
            throw new IllegalArgumentException("RSA 密钥位长必须 >= " + ABSOLUTE_MIN_RSA_BITS + " 位，拒绝生成弱密钥: " + keySize);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 算法在当前运行环境中不受支持", e);
        }
    }

    /**
     * 获取 RSA 密钥模数位长
     */
    public static int getKeyBits(Key key) {
        if (key instanceof RSAKey rsaKey) {
            return rsaKey.getModulus().bitLength();
        }
        throw new IllegalArgumentException("非 RSA 密钥类型: " + (key == null ? "null" : key.getClass().getName()));
    }

    /**
     * 强密钥校验：若密钥位长低于 2048 位则抛出异常
     */
    public static void assertStrongKey(Key key, String keyDescription) {
        int bits = getKeyBits(key);
        if (bits < ABSOLUTE_MIN_RSA_BITS) {
            throw new IllegalStateException((keyDescription != null ? keyDescription : "RSA 密钥")
                    + " 强度不足: " + bits + " 位 < 要求最低 " + ABSOLUTE_MIN_RSA_BITS + " 位（已彻底淘汰 RSA-1024）");
        }
    }

    /**
     * 将公钥转为单行 Base64 编码 (X.509 DER)
     */
    public static String toX509Base64(PublicKey publicKey) {
        assertStrongKey(publicKey, "公钥");
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 将私钥转为单行 Base64 编码 (PKCS#8 DER)
     */
    public static String toPkcs8Base64(PrivateKey privateKey) {
        assertStrongKey(privateKey, "私钥");
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * 解析 X.509 公钥（支持 PEM 或单行 Base64），强行校验 >= 2048 位
     */
    public static RSAPublicKey parsePublicKey(String pemOrBase64) {
        if (pemOrBase64 == null || pemOrBase64.isBlank()) {
            throw new IllegalArgumentException("公钥内容不能为空");
        }
        String normalized = stripPemArmor(pemOrBase64);
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(der));
            assertStrongKey(key, "解析后的公钥");
            return key;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("RSA 公钥格式非法，必须为有效 X.509 DER 的 Base64/PEM 字符串", e);
        }
    }

    /**
     * 解析 PKCS#8 私钥（支持 PEM 或单行 Base64），强行校验 >= 2048 位
     */
    public static RSAPrivateKey parsePrivateKey(String pemOrBase64) {
        if (pemOrBase64 == null || pemOrBase64.isBlank()) {
            throw new IllegalArgumentException("私钥内容不能为空");
        }
        String normalized = stripPemArmor(pemOrBase64);
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            assertStrongKey(key, "解析后的私钥");
            return key;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("RSA 私钥格式非法，必须为有效 PKCS#8 DER 的 Base64/PEM 字符串", e);
        }
    }

    /**
     * 清理 PEM 头尾边界与多余换行空白字符
     */
    public static String stripPemArmor(String pem) {
        if (pem == null) return "";
        return pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
    }
}

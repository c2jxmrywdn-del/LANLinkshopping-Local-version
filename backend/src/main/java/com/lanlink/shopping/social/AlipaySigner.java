package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 支付宝开放平台 RSA2 请求签名（SHA256withRSA）。
 *
 * 规则（与官方一致）：
 *  - 参与签名的参数：剔除 sign、剔除空值、按参数名**字典序升序**，拼成 k=v&k=v；
 *  - 签名算法 SHA256withRSA，私钥为 PKCS#8（PEM 去头尾），签名结果 Base64；
 *  - 请求时 sign 参数使用 Base64（非 URL 编码形态）。
 */
@Component
public class AlipaySigner {

    /** 支付宝 RSA2 私钥的最低强度（位） */
    private static final int MIN_RSA_BITS = 2048;

    /** 组装待签名串（供签名与单测断言） */
    public String canonical(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((k, v) -> {
            if (k == null || "sign".equals(k) || v == null || v.isEmpty()) return;
            sorted.put(k, v);
        });
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    /** 用 PKCS#8 PEM 私钥对参数做 RSA2 签名，返回 Base64 签名串 */
    public String sign(Map<String, String> params, String privateKeyPem) {
        try {
            PrivateKey key = loadPrivateKey(privateKeyPem);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(canonical(params).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (BusinessException e) {
            throw e;
        } catch (IllegalStateException e) {
            // 弱密钥是配置问题，不能被下面统一包装成"格式错误"，否则排查方向被误导
            throw e;
        } catch (Exception e) {
            throw new BusinessException("支付宝私钥签名失败，请检查应用私钥格式（PKCS#8）");
        }
    }

    /** 载入 PKCS#8 私钥：允许带 PEM 头尾、允许换行与空白；RSA 强度低于 2048 位直接拒绝 */
    public PrivateKey loadPrivateKey(String pem) {
        PrivateKey key;
        try {
            String normalized = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new BusinessException("支付宝私钥无法解析，请提供 PKCS#8 格式的应用私钥");
        }
        // RSA-1024 的 SHA256withRSA 签名已不具备现实安全强度，且支付宝侧早已要求 2048 位
        RSAPrivateKey rsa = (RSAPrivateKey) key;
        if (rsa.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalStateException("支付宝私钥必须≥2048位");
        }
        return key;
    }
}

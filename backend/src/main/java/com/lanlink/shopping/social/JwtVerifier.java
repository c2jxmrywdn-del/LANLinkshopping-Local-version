package com.lanlink.shopping.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * OIDC id_token 校验（Apple / Google 等 RS256 签名的场景）。
 *
 * 做法：用平台 JWKS 中对应 kid 的 RSA 公钥（n/e）验证 RS256 签名，
 * 再校验 iss / aud / exp 三项声明。**不信任未验签的 payload**。
 */
@Component
public class JwtVerifier {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 校验并返回 id_token 的 payload。
     *
     * @param jwt        紧凑序列化的 JWT（header.payload.signature）
     * @param nBase64Url JWKS 中 RSA 公钥模数 n（Base64URL）
     * @param eBase64Url JWKS 中 RSA 公钥指数 e（Base64URL）
     * @param expectedIss 期望签发方（如 https://appleid.apple.com）
     * @param expectedAud 期望受众（即 client_id）
     */
    public JsonNode verifyRs256(String jwt, String nBase64Url, String eBase64Url,
                                String expectedIss, String expectedAud) {
        if (jwt == null || jwt.isBlank()) throw new BusinessException("身份令牌缺失");
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new BusinessException("身份令牌格式非法");

        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (Exception e) {
            throw new BusinessException("身份令牌签名无法解码");
        }

        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey(nBase64Url, eBase64Url));
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(signature)) {
                throw new BusinessException("身份令牌签名校验未通过");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("身份令牌校验失败");
        }

        JsonNode payload = readPayload(parts[1]);
        String iss = text(payload, "iss");
        if (expectedIss != null && !expectedIss.equals(iss)) {
            throw new BusinessException("身份令牌签发方不匹配");
        }
        if (expectedAud != null && !audienceContains(payload, expectedAud)) {
            throw new BusinessException("身份令牌受众不匹配");
        }
        long exp = payload.path("exp").asLong(0L);
        if (exp > 0 && Instant.now().getEpochSecond() >= exp) {
            throw new BusinessException("身份令牌已过期，请重新授权");
        }
        return payload;
    }

    /** 取 JWT header 中的 kid（用于从 JWKS 定位对应公钥）；缺失或非法返回 null */
    public String kidOf(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            JsonNode header = mapper.readTree(
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            JsonNode kid = header.get("kid");
            return kid == null || kid.isNull() ? null : kid.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JWKS 文档中按 kid 取公钥（n/e）；未命中返回 null */
    public static String[] pickJwk(JsonNode jwks, String kid) {
        if (jwks == null) return null;
        for (JsonNode k : jwks.path("keys")) {
            if (kid == null || kid.equals(text(k, "kid"))) {
                String n = text(k, "n");
                String e = text(k, "e");
                if (n != null && e != null) return new String[]{n, e};
            }
        }
        return null;
    }

    private JsonNode readPayload(String base64UrlPayload) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(base64UrlPayload);
            return mapper.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException("身份令牌内容无法解析");
        }
    }

    private PublicKey publicKey(String nB64, String eB64) throws Exception {
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(nB64));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(eB64));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    private boolean audienceContains(JsonNode payload, String expectedAud) {
        JsonNode aud = payload.path("aud");
        if (aud.isArray()) {
            for (JsonNode a : aud) if (expectedAud.equals(a.asText())) return true;
            return false;
        }
        return expectedAud.equals(aud.asText());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

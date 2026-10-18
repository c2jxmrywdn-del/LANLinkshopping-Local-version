package com.lanlink.shopping.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OIDC id_token 校验单测（Apple 场景）：
 * 用本地生成的 RSA 密钥自签 JWT，覆盖「验签通过 / payload 被篡改 / 过期 / 受众不匹配 / kid 定位」。
 */
class JwtVerifierTest {

    private static final String ISS = "https://appleid.apple.com";
    private static final String AUD = "com.lanlink.shopping.web";

    private final JwtVerifier verifier = new JwtVerifier();

    @Test
    void validTokenPassesAndReturnsClaims() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "k1", payload(Instant.now().getEpochSecond() + 600, ISS, AUD, "apple-sub-1", "user@privaterelay.appleid.com"));
        JsonNode payload = verifier.verifyRs256(jwt, nB64(kp), eB64(kp), ISS, AUD);
        assertEquals("apple-sub-1", payload.get("sub").asText());
        assertEquals("user@privaterelay.appleid.com", payload.get("email").asText());
    }

    @Test
    void kidIsExtractedFromHeader() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "apple-kid-9", payload(Instant.now().getEpochSecond() + 600, ISS, AUD, "s", null));
        assertEquals("apple-kid-9", verifier.kidOf(jwt));
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "k1", payload(Instant.now().getEpochSecond() + 600, ISS, AUD, "sub-real", null));
        String[] parts = jwt.split("\\.");
        // 把 payload 换成攻击者伪造的（签名仍用原签名）
        String forged = b64("{\"sub\":\"attacker\",\"iss\":\"" + ISS + "\",\"aud\":\"" + AUD + "\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        String tampered = parts[0] + "." + forged + "." + parts[2];
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verifyRs256(tampered, nB64(kp), eB64(kp), ISS, AUD));
        assertTrue(ex.getMessage().contains("签名校验未通过"));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "k1", payload(Instant.now().getEpochSecond() - 5, ISS, AUD, "s", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verifyRs256(jwt, nB64(kp), eB64(kp), ISS, AUD));
        assertTrue(ex.getMessage().contains("已过期"));
    }

    @Test
    void audienceMismatchIsRejected() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "k1", payload(Instant.now().getEpochSecond() + 600, ISS, "another-client", "s", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verifyRs256(jwt, nB64(kp), eB64(kp), ISS, AUD));
        assertTrue(ex.getMessage().contains("受众不匹配"));
    }

    @Test
    void issuerMismatchIsRejected() throws Exception {
        KeyPair kp = AlipaySignerTest.keyPair();
        String jwt = signJwt(kp, "k1", payload(Instant.now().getEpochSecond() + 600, "https://evil.example.com", AUD, "s", null));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verifyRs256(jwt, nB64(kp), eB64(kp), ISS, AUD));
        assertTrue(ex.getMessage().contains("签发方不匹配"));
    }

    @Test
    void malformedTokenIsRejected() {
        assertTrue(assertThrows(BusinessException.class,
                () -> verifier.verifyRs256("not-a-jwt", "n", "e", ISS, AUD)).getMessage().contains("格式非法"));
        assertNull(verifier.kidOf("not-a-jwt"));
    }

    /* ---------------- helpers ---------------- */

    static String payload(long exp, String iss, String aud, String sub, String email) {
        StringBuilder sb = new StringBuilder("{\"sub\":\"").append(sub).append("\",\"iss\":\"").append(iss)
                .append("\",\"aud\":\"").append(aud).append("\",\"exp\":").append(exp)
                .append(",\"email_verified\":\"true\"");
        if (email != null) sb.append(",\"email\":\"").append(email).append("\"");
        return sb.append("}").toString();
    }

    static String signJwt(KeyPair kp, String kid, String payloadJson) throws Exception {
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}");
        String body = b64(payloadJson);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
        return header + "." + body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }

    static String nB64(KeyPair kp) {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(pub.getModulus().toByteArray());
    }

    static String eB64(KeyPair kp) {
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(pub.getPublicExponent().toByteArray());
    }

    static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

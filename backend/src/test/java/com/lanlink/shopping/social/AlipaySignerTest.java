package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝 RSA2 签名单测：
 * 本源确认「待签名串」的排序/剔除规则，并用本地生成的 RSA 密钥验签闭环。
 */
class AlipaySignerTest {

    private final AlipaySigner signer = new AlipaySigner();

    @Test
    void canonicalSortsByKeyAndDropsSignAndBlank() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("b", "2");
        params.put("a", "1");
        params.put("sign", "SHOULD_BE_DROPPED");
        params.put("blank", "");
        params.put("nullValue", null);
        assertEquals("a=1&b=2", signer.canonical(params));
    }

    @Test
    void signIsVerifiableByTheMatchingPublicKey() throws Exception {
        KeyPair kp = keyPair();
        String pem = pkcs8Pem((RSAPrivateKey) kp.getPrivate());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "2021000000000000");
        params.put("method", "alipay.system.oauth.token");
        params.put("charset", "UTF-8");
        params.put("grant_type", "authorization_code");
        params.put("code", "auth_code_123");

        String sign = signer.sign(params, pem);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(kp.getPublic());
        verifier.update(signer.canonical(params).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(sign)), "签名应能被对应公钥验证通过");
    }

    @Test
    void tamperedParamsBreakTheSignature() throws Exception {
        KeyPair kp = keyPair();
        String pem = pkcs8Pem((RSAPrivateKey) kp.getPrivate());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "A");
        params.put("code", "C1");
        String sign = signer.sign(params, pem);

        params.put("code", "C2"); // 篡改
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(kp.getPublic());
        verifier.update(signer.canonical(params).getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(Base64.getDecoder().decode(sign)));
    }

    @Test
    void invalidPrivateKeyGivesActionableError() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> signer.sign(Map.of("a", "1"), "not-a-valid-pem"));
        assertTrue(ex.getMessage().contains("PKCS#8") || ex.getMessage().contains("无法解析"));
    }

    /* ---------------- helpers ---------------- */

    static KeyPair keyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    static String pkcs8Pem(RSAPrivateKey key) {
        String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";
    }
}

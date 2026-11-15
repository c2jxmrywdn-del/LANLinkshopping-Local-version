package com.lanlink.shopping.crypto;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEObject;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWE 服务单测：密钥全部在测试内现场生成，不依赖任何真实环境变量。
 */
class JweCryptoServiceTest {

    @Test
    void encryptDecryptRoundTripWithExpectedHeader() throws Exception {
        KeyPair kp = rsa(2048);
        JweCryptoService service = new JweCryptoService(properties("kid-2026-01", kp, 2048));

        assertTrue(service.available());
        assertEquals(2048, service.keyBits());
        assertEquals("kid-2026-01", service.activeKid());

        String token = service.encrypt("13800000000");
        assertEquals(5, token.split("\\.").length, "JWE compact 必须是 5 段");

        JWEObject parsed = JWEObject.parse(token);
        assertEquals("kid-2026-01", parsed.getHeader().getKeyID());
        assertEquals(JWEAlgorithm.RSA_OAEP_256, parsed.getHeader().getAlgorithm());
        assertEquals(EncryptionMethod.A256GCM, parsed.getHeader().getEncryptionMethod());

        assertEquals("13800000000", service.decrypt(token));
    }

    @Test
    void tamperedTokenFailsAuthentication() throws Exception {
        KeyPair kp = rsa(2048);
        JweCryptoService service = new JweCryptoService(properties("kid-1", kp, 2048));
        String token = service.encrypt("secret-value");

        String[] parts = token.split("\\.");
        char[] tag = parts[4].toCharArray();
        tag[0] = tag[0] == 'A' ? 'B' : 'A';
        parts[4] = new String(tag);
        String tampered = String.join(".", parts);

        assertThrows(CryptoDecryptException.class, () -> service.decrypt(tampered));
    }

    @Test
    void unknownKidCannotBeDecrypted() throws Exception {
        KeyPair kp = rsa(2048);
        String token = new JweCryptoService(properties("kid-old", kp, 2048)).encrypt("v");

        // 另一套密钥（模拟未配置轮换期旧私钥的节点）必须明确拒绝，而不是返回脏数据
        JweCryptoService other = new JweCryptoService(properties("kid-new", rsa(2048), 2048));
        CryptoDecryptException ex = assertThrows(CryptoDecryptException.class, () -> other.decrypt(token));
        assertTrue(ex.getMessage().contains("kid"));
    }

    @Test
    void rejectsRsa1024Key() throws Exception {
        KeyPair weak = rsa(1024);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JweCryptoService(properties("kid-weak", weak, 2048)));
        assertTrue(ex.getMessage().contains("2048"));
    }

    @Test
    void requiredWithoutKeysFailsFastButOptionalFallsBack() {
        CryptoProperties required = new CryptoProperties();
        required.getJwe().setRequired(true);
        assertThrows(IllegalStateException.class, () -> new JweCryptoService(required));

        CryptoProperties optional = new CryptoProperties();
        JweCryptoService service = new JweCryptoService(optional);
        assertFalse(service.available());
        assertEquals(0, service.keyBits());
    }

    @Test
    void encryptAndDecryptEmptyOrNullReturnsNull() throws Exception {
        JweCryptoService service = new JweCryptoService(properties("kid-test", rsa(2048), 2048));
        assertNull(service.encrypt(null));
        assertNull(service.encrypt(""));
        assertNull(service.decrypt(null));
        assertNull(service.decrypt(""));
    }

    /* ---------------- helpers ---------------- */

    static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(bits);
        return gen.generateKeyPair();
    }

    static CryptoProperties properties(String kid, KeyPair kp, int minBits) {
        CryptoProperties props = new CryptoProperties();
        props.setMinRsaBits(minBits);
        CryptoProperties.Jwe jwe = new CryptoProperties.Jwe();
        jwe.setActiveKid(kid);
        jwe.setActivePrivateKey(Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        jwe.setActivePublicKey(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        props.setJwe(jwe);
        return props;
    }
}

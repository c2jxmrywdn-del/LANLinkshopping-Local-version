package com.lanlink.shopping.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CryptoService 版本兼容单测：新写入走 JWE，历史 legacy AES-GCM 密文仍能读出。
 */
class CryptoServiceJweTest {

    private static final String LEGACY_SECRET = "lanlink-test-legacy-secret";

    @Test
    void newWritesAreJweAndDecryptBack() throws Exception {
        JweCryptoService jwe = new JweCryptoService(
                JweCryptoServiceTest.properties("kid-a", JweCryptoServiceTest.rsa(2048), 2048));
        CryptoService crypto = new CryptoService(LEGACY_SECRET, jwe);

        String token = crypto.encrypt("13800000000");
        assertEquals(5, token.split("\\.").length);
        assertTrue(CryptoService.isJwe(token));
        assertEquals("13800000000", crypto.decrypt(token));
        assertEquals("13800000000", crypto.decryptStrict(token));
    }

    @Test
    void legacyAesCiphertextIsStillReadable() throws Exception {
        CryptoService crypto = new CryptoService(LEGACY_SECRET, null);
        String legacy = legacyEncrypt("旧密文-保留");

        assertFalse(CryptoService.isJwe(legacy));
        assertEquals("旧密文-保留", crypto.decrypt(legacy));
        assertEquals("旧密文-保留", crypto.decryptStrict(legacy));
    }

    @Test
    void fallsBackToLegacyWhenJweDisabled() {
        CryptoProperties props = new CryptoProperties();
        props.getJwe().setEnabled(false);
        CryptoService crypto = new CryptoService(LEGACY_SECRET, new JweCryptoService(props));

        String out = crypto.encrypt("plain");
        assertFalse(CryptoService.isJwe(out), "JWE 关闭时必须回退 legacy AES-GCM");
        assertEquals("plain", crypto.decrypt(out));
    }

    @Test
    void brokenCiphertextKeepsNullSemanticsButStrictThrows() {
        CryptoService crypto = new CryptoService(LEGACY_SECRET, null);
        assertNull(crypto.decrypt("@@@not-base64@@@"));
        assertThrows(CryptoDecryptException.class, () -> crypto.decryptStrict("@@@not-base64@@@"));
    }

    @Test
    void encryptAndDecryptEmptyOrNullReturnsNull() {
        CryptoService crypto = new CryptoService(LEGACY_SECRET, null);
        assertNull(crypto.encrypt(null));
        assertNull(crypto.encrypt(""));
        assertNull(crypto.decrypt(null));
        assertNull(crypto.decrypt(""));
        assertNull(crypto.decryptStrict(null));
        assertNull(crypto.decryptStrict(""));
    }

    /* ---------------- helpers ---------------- */

    /** 复刻旧版（v1）实现：Base64(IV12 + AES-256-GCM(CT+Tag))，主密钥 SHA-256 派生 */
    private static String legacyEncrypt(String plain) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(LEGACY_SECRET.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }
}

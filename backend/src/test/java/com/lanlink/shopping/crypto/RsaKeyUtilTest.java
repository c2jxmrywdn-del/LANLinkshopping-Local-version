package com.lanlink.shopping.crypto;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RsaKeyUtil 单测：验证 RSA-2048 强制基线与彻底杜绝 RSA-1024 弱密钥。
 */
class RsaKeyUtilTest {

    @Test
    void defaultKeyPairIs2048Bits() {
        KeyPair kp = RsaKeyUtil.generateKeyPair();
        assertNotNull(kp);
        assertEquals(2048, RsaKeyUtil.getKeyBits(kp.getPublic()));
        assertEquals(2048, RsaKeyUtil.getKeyBits(kp.getPrivate()));
    }

    @Test
    void rejectsGeneratingWeakKeyBelow2048Bits() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RsaKeyUtil.generateKeyPair(1024));
        assertTrue(ex.getMessage().contains("2048"));
        assertTrue(ex.getMessage().contains("拒绝生成弱密钥"));
    }

    @Test
    void toBase64AndParseRoundTripSucceedsFor2048() {
        KeyPair kp = RsaKeyUtil.generateKeyPair(2048);
        String pubB64 = RsaKeyUtil.toX509Base64(kp.getPublic());
        String privB64 = RsaKeyUtil.toPkcs8Base64(kp.getPrivate());

        RSAPublicKey parsedPub = RsaKeyUtil.parsePublicKey(pubB64);
        RSAPrivateKey parsedPriv = RsaKeyUtil.parsePrivateKey(privB64);

        assertEquals(2048, RsaKeyUtil.getKeyBits(parsedPub));
        assertEquals(2048, RsaKeyUtil.getKeyBits(parsedPriv));
        assertEquals(kp.getPublic().getAlgorithm(), parsedPub.getAlgorithm());
    }

    @Test
    void assertStrongKeyThrowsForWeak1024Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        KeyPair weakKp = gen.generateKeyPair();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RsaKeyUtil.assertStrongKey(weakKp.getPublic(), "测试弱公钥"));
        assertTrue(ex.getMessage().contains("1024"));
        assertTrue(ex.getMessage().contains("2048"));
    }
}

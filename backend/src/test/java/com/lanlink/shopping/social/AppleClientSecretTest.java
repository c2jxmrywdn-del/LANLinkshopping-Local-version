package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AppleClientSecretTest {

    @Test
    void signsEs256ClientSecretFromP256Key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        SocialLoginProperties.Provider cfg = new SocialLoginProperties.Provider();
        cfg.setClientId("com.lanlink.web");
        cfg.setTeamId("TEAMID123");
        cfg.setKeyId("KEYID12345");
        cfg.setPrivateKey(pem.replace("\n", "\\n"));

        AppleClientSecret secrets = new AppleClientSecret();
        String jwt = secrets.resolve(cfg);
        assertEquals(jwt, secrets.resolve(cfg), "未过期的 client secret 应复用，避免每次登录都重签");

        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        String header = decode(parts[0]);
        String payload = decode(parts[1]);
        assertTrue(header.contains("\"alg\":\"ES256\""));
        assertTrue(header.contains("\"kid\":\"KEYID12345\""));
        assertTrue(payload.contains("\"iss\":\"TEAMID123\""));
        assertTrue(payload.contains("\"sub\":\"com.lanlink.web\""));
        assertTrue(payload.contains("\"aud\":\"https://appleid.apple.com\""));

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(kp.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2]))));
    }

    @Test
    void staticClientSecretIsUsedAsIs() {
        SocialLoginProperties.Provider cfg = new SocialLoginProperties.Provider();
        cfg.setClientId("com.lanlink.web");
        cfg.setClientSecret("already-signed-jwt");
        assertEquals("already-signed-jwt", new AppleClientSecret().resolve(cfg));
    }

    @Test
    void missingMaterialFailsClosed() {
        SocialLoginProperties.Provider cfg = new SocialLoginProperties.Provider();
        cfg.setClientId("com.lanlink.web");
        BusinessException ex = assertThrows(BusinessException.class, () -> new AppleClientSecret().resolve(cfg));
        assertTrue(ex.getMessage().contains("Team ID"));
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    private static byte[] joseToDer(byte[] jose) {
        byte[] r = derInt(Arrays.copyOfRange(jose, 0, 32));
        byte[] s = derInt(Arrays.copyOfRange(jose, 32, 64));
        byte[] out = new byte[2 + r.length + s.length];
        out[0] = 0x30;
        out[1] = (byte) (r.length + s.length);
        System.arraycopy(r, 0, out, 2, r.length);
        System.arraycopy(s, 0, out, 2 + r.length, s.length);
        return out;
    }

    private static byte[] derInt(byte[] component) {
        int offset = 0;
        while (offset < component.length - 1 && component[offset] == 0) offset++;
        boolean pad = (component[offset] & 0x80) != 0;
        int len = component.length - offset;
        byte[] out = new byte[2 + len + (pad ? 1 : 0)];
        out[0] = 0x02;
        out[1] = (byte) (len + (pad ? 1 : 0));
        if (pad) out[2] = 0;
        System.arraycopy(component, offset, out, pad ? 3 : 2, len);
        return out;
    }
}

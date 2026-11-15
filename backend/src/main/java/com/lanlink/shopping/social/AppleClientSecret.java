package com.lanlink.shopping.social;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.config.SocialLoginProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Apple Sign in with Apple 的 client_secret。
 *
 * <p>Apple 要求这个值是 ES256 JWT（iss=Team ID，sub=Services ID，aud=https://appleid.apple.com），
 * 最长 6 个月。配置里可以直接放已经签好的 JWT，也可以放 Team ID、Key ID 和 .p8 私钥，
 * 由这里在运行时签名并在到期前一天轮换。私钥只留在环境变量里。
 */
@Component
public class AppleClientSecret {

    static final String AUDIENCE = "https://appleid.apple.com";
    /** 150 天，低于 Apple 的 6 个月上限。 */
    private static final long TTL_SECONDS = 150L * 24 * 3600;
    private static final long REFRESH_SKEW_SECONDS = 24 * 3600;
    private static final int P256_COMPONENT = 32;

    private final Object lock = new Object();
    private String cachedJwt;
    private String cacheKey;
    private long refreshAfterEpoch;

    /**
     * 已填写 client secret 时原样使用；否则用 Team ID / Key ID / 私钥现签。
     */
    public String resolve(SocialLoginProperties.Provider cfg) {
        if (cfg == null) throw new BusinessException("Apple 登录未配置");
        String staticSecret = cfg.resolveSecret();
        if (staticSecret != null && !staticSecret.isBlank()) return staticSecret.trim();
        if (!cfg.hasAppleSigningKey()) {
            throw new BusinessException("Apple 登录未配置：请填写 client secret，或同时填写 Team ID、Key ID 与私钥");
        }
        String key = cfg.getTeamId().trim() + "|" + cfg.getKeyId().trim() + "|"
                + cfg.resolveId().trim() + "|" + cfg.getPrivateKey().length();
        long now = Instant.now().getEpochSecond();
        synchronized (lock) {
            if (cachedJwt != null && key.equals(cacheKey) && now < refreshAfterEpoch) return cachedJwt;
            Signed signed = sign(cfg, now);
            cachedJwt = signed.jwt();
            cacheKey = key;
            refreshAfterEpoch = signed.expEpoch() - REFRESH_SKEW_SECONDS;
            return cachedJwt;
        }
    }

    private Signed sign(SocialLoginProperties.Provider cfg, long now) {
        PrivateKey key = privateKey(cfg.getPrivateKey());
        if (!(key instanceof ECPrivateKey ec) || ec.getParams().getOrder().bitLength() != 256) {
            throw new BusinessException("Apple 私钥必须是 P-256（ES256）");
        }
        long exp = now + TTL_SECONDS;
        String header = b64("{\"alg\":\"ES256\",\"kid\":\"" + jsonEscape(cfg.getKeyId().trim()) + "\"}");
        String payload = b64("{\"iss\":\"" + jsonEscape(cfg.getTeamId().trim())
                + "\",\"iat\":" + now
                + ",\"exp\":" + exp
                + ",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + jsonEscape(cfg.resolveId().trim()) + "\"}");
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String jwt = signingInput + "." + b64(derToJose(signer.sign(), P256_COMPONENT));
            return new Signed(jwt, exp);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Apple 客户端密钥签名失败");
        }
    }

    static PrivateKey privateKey(String raw) {
        try {
            byte[] pkcs8 = pkcs8Bytes(raw);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Apple 私钥无法解析，请提供 PKCS#8 PEM 或 Base64");
        }
    }

    static byte[] pkcs8Bytes(String raw) {
        if (raw == null || raw.isBlank()) throw new BusinessException("Apple 私钥缺失");
        String text = raw.trim().replace("\\n", "\n");
        if (text.contains("BEGIN")) {
            text = text.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
        } else {
            text = text.replaceAll("\\s", "");
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Apple 私钥无法解析，请提供 PKCS#8 PEM 或 Base64");
        }
    }

    /** DER ECDSA 签名转成 JWT 使用的 R||S（各 32 字节）。 */
    static byte[] derToJose(byte[] der, int size) {
        if (der == null || der.length < 8 || der[0] != 0x30) {
            throw new BusinessException("Apple 客户端密钥签名格式异常");
        }
        int offset = (der[1] & 0xff) == 0x81 ? 3 : 2;
        int rPos = componentStart(der, offset);
        int rLen = der[offset + 1] & 0xff;
        offset = rPos + rLen;
        int sPos = componentStart(der, offset);
        int sLen = der[offset + 1] & 0xff;
        byte[] out = new byte[size * 2];
        copyComponent(der, rPos, rLen, out, 0, size);
        copyComponent(der, sPos, sLen, out, size, size);
        return out;
    }

    private static int componentStart(byte[] der, int offset) {
        if (offset + 2 >= der.length || der[offset] != 0x02) {
            throw new BusinessException("Apple 客户端密钥签名格式异常");
        }
        return offset + 2;
    }

    private static void copyComponent(byte[] src, int pos, int len, byte[] dest, int destPos, int size) {
        if (len <= 0 || len > size + 1 || (len == size + 1 && src[pos] != 0)) {
            throw new BusinessException("Apple 客户端密钥签名长度异常");
        }
        int srcPos = pos;
        int copyLen = len;
        if (copyLen == size + 1) {
            srcPos++;
            copyLen--;
        }
        System.arraycopy(src, srcPos, dest, destPos + (size - copyLen), copyLen);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Signed(String jwt, long expEpoch) {}
}

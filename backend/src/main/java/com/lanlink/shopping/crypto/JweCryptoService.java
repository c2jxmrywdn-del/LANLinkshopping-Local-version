package com.lanlink.shopping.crypto;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * JWE（RSA-OAEP-256 密钥封装 + A256GCM 内容加密）字段加解密。
 *
 * <p>为什么用 JWE 而不是继续 AES-GCM：对称主密钥一旦随代码/镜像下发就等于没有边界，
 * 而 RSA 只需在应用内持有私钥、公钥可公开；A256GCM 仍保留每字段随机 CEK 与认证标签，
 * 篡改密文必然解密失败。compact 序列化（5 段）自带 header，无需新增 key_id/version 列。</p>
 *
 * <p>不可用（未配置/关闭）时的策略：
 * required=true 直接抛异常（宁可启动失败，也不静默写回旧格式）；
 * required=false 只告警并置 unavailable，由 {@link CryptoService} 回退 legacy AES-GCM，
 * 保证过渡期不因一次配置缺失而全站不可写。</p>
 */
@Service
public class JweCryptoService {

    private static final Logger log = LoggerFactory.getLogger(JweCryptoService.class);

    public static final int ABSOLUTE_MIN_RSA_BITS = 2048;
    private static final int DEFAULT_MIN_RSA_BITS = 2048;

    private final int minRsaBits;
    private final String activeKid;
    private RSAPublicKey activePublicKey;
    /** kid → 私钥：包含 active 与轮换期 previous，解密时按 header 里的 kid 路由 */
    private final Map<String, RSAPrivateKey> decryptionKeys = new HashMap<>();
    private boolean available;

    public JweCryptoService(CryptoProperties properties) {
        CryptoProperties.Jwe cfg = properties != null ? properties.getJwe() : new CryptoProperties.Jwe();
        int configuredBits = properties != null && properties.getMinRsaBits() > 0 ? properties.getMinRsaBits() : DEFAULT_MIN_RSA_BITS;
        this.minRsaBits = Math.max(configuredBits, ABSOLUTE_MIN_RSA_BITS);
        this.activeKid = trimToNull(cfg.getActiveKid());

        if (!cfg.isEnabled()) {
            log.info("[crypto] JWE 已关闭（lanlink.crypto.jwe.enabled=false），字段加密走 legacy AES-GCM");
            return;
        }

        String privateKeyB64 = trimToNull(cfg.getActivePrivateKey());
        String publicKeyB64 = trimToNull(cfg.getActivePublicKey());
        if (activeKid == null || privateKeyB64 == null || publicKeyB64 == null) {
            String msg = "JWE 已启用但密钥不完整（需要 kid + 私钥 + 公钥）";
            if (cfg.isRequired()) {
                throw new IllegalStateException(msg + "，且 lanlink.crypto.jwe.required=true，拒绝启动");
            }
            log.warn("[crypto] {}，当前进程回退 legacy AES-GCM 加密（历史密文仍可双读）", msg);
            return;
        }

        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        try {
            publicKey = parsePublicKey(publicKeyB64);
            privateKey = parsePrivateKey(privateKeyB64);
        } catch (Exception e) {
            // 只记录异常类型：私钥内容绝不进日志
            String msg = "JWE 密钥解析失败（请确认是 X509 公钥 / PKCS#8 私钥的 Base64）";
            if (cfg.isRequired()) throw new IllegalStateException(msg, e);
            log.warn("[crypto] {}，当前进程回退 legacy AES-GCM 加密", msg);
            return;
        }

        requireStrong(publicKey.getModulus().bitLength(), "公钥");
        requireStrong(privateKey.getModulus().bitLength(), "私钥");
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            // 公私钥不匹配时加密出的密文用本机私钥永远解不开，属于必然丢数据的事故，宁可启动失败
            throw new IllegalStateException("JWE 公钥与私钥不匹配，请检查 LANLINK_CRYPTO_JWE_ACTIVE_* 配置");
        }

        this.activePublicKey = publicKey;
        this.available = true;
        decryptionKeys.put(activeKid, privateKey);

        String previousKid = trimToNull(cfg.getPreviousKid());
        String previousPrivateKeyB64 = trimToNull(cfg.getPreviousPrivateKey());
        if (previousKid != null && previousPrivateKeyB64 != null) {
            try {
                RSAPrivateKey previous = parsePrivateKey(previousPrivateKeyB64);
                requireStrong(previous.getModulus().bitLength(), "轮换期旧私钥");
                decryptionKeys.put(previousKid, previous);
                log.info("[crypto] JWE 已加载轮换期旧私钥 kid={}（仅用于解密）", previousKid);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("轮换期旧 JWE 私钥解析失败", e);
            }
        } else if (previousKid != null || previousPrivateKeyB64 != null) {
            // 只配一半等于没配：轮换后历史密文会因找不到 kid 而解不开，这里必须先告警出来
            log.warn("[crypto] 轮换期旧密钥配置不完整（kid 与私钥需同时提供），已忽略，历史 JWE 可能无法解密");
        }

        log.info("[crypto] JWE 已启用：alg=RSA-OAEP-256, enc=A256GCM, kid={}, rsaBits={}",
                activeKid, publicKey.getModulus().bitLength());
    }

    /** JWE 是否可用于加密（密钥齐全且校验通过） */
    public boolean available() {
        return available;
    }

    /** 当前密钥模数位数；不可用时返回 0（供启动闸门兜底判断） */
    public int keyBits() {
        return activePublicKey == null ? 0 : activePublicKey.getModulus().bitLength();
    }

    /** 当前用于加密的 kid；不可用时为 null */
    public String activeKid() {
        return activePublicKey == null ? null : activeKid;
    }

    /** 加密为 JWE compact 序列化（header.kek.iv.ciphertext.tag 五段） */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        if (!available) throw new IllegalStateException("JWE 密钥未就绪，无法加密");
        try {
            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                    // kid 必须写进 header：轮换后靠它选中对应私钥，避免"一次轮换全部历史数据报废"
                    .keyID(activeKid)
                    .contentType("text/plain")
                    .build();
            JWEObject jwe = new JWEObject(header, new Payload(plain));
            jwe.encrypt(new RSAEncrypter(activePublicKey));
            return jwe.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("JWE 加密失败", e);
        }
    }

    /** 解密 JWE compact 密文；失败统一抛 {@link CryptoDecryptException} */
    public String decrypt(String token) {
        if (token == null || token.isEmpty()) return null;
        JWEObject jwe;
        try {
            jwe = JWEObject.parse(token);
        } catch (ParseException e) {
            throw new CryptoDecryptException("JWE 密文格式非法", e);
        }
        // 只接受本服务写出的算法：防止降级到无认证的 enc 或非预期密钥封装算法
        if (!JWEAlgorithm.RSA_OAEP_256.equals(jwe.getHeader().getAlgorithm())) {
            throw new CryptoDecryptException("JWE 算法不受支持：" + jwe.getHeader().getAlgorithm());
        }
        String kid = jwe.getHeader().getKeyID();
        RSAPrivateKey key = kid == null ? null : decryptionKeys.get(kid);
        if (key == null) {
            throw new CryptoDecryptException("JWE kid 未知（" + (kid == null ? "header 无 kid" : kid) + "），无法解密");
        }
        try {
            jwe.decrypt(new RSADecrypter(key));
        } catch (JOSEException e) {
            throw new CryptoDecryptException("JWE 解密失败", e);
        }
        Payload payload = jwe.getPayload();
        if (payload == null) throw new CryptoDecryptException("JWE 载荷为空");
        return payload.toString();
    }

    /** 弱密钥拒绝：位数不足直接失败（已彻底废止已被攻破的 RSA-1024） */
    private void requireStrong(int bits, String what) {
        if (bits < minRsaBits) {
            throw new IllegalStateException("JWE " + what + "强度不足：" + bits + " 位 < 要求最低 " + minRsaBits + " 位（已全面升级为 RSA-2048，严禁使用已被攻破的 RSA-1024 弱密钥）");
        }
    }

    static RSAPublicKey parsePublicKey(String base64) throws Exception {
        byte[] der = Base64.getDecoder().decode(compact(base64));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    static RSAPrivateKey parsePrivateKey(String base64) throws Exception {
        byte[] der = Base64.getDecoder().decode(compact(base64));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** 兼容运维从 PEM 里复制多行 Base64 的情况：去掉所有空白与 PEM 头尾 */
    private static String compact(String base64) {
        return base64
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

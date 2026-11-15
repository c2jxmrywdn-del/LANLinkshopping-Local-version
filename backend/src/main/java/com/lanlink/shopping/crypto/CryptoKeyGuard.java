package com.lanlink.shopping.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 字段加密启动闸门。
 *
 * <p>为什么放在 ApplicationReadyEvent：CryptoService 的 legacy 主密钥有历史默认值兜底，
 * 若不在启动后校验，"生产忘了注入 LANLINK_CRYPTO_SECRET/JWE 密钥"会变成静默使用弱默认值，
 * 数据一旦按错误密钥写入就再也解不回来。生产（prod）直接快速失败，非生产只告警不阻断本地开发。</p>
 */
@Component
public class CryptoKeyGuard {

    private static final Logger log = LoggerFactory.getLogger(CryptoKeyGuard.class);

    /** 与 CryptoService 的兜底默认值保持一致 */
    private static final String DEFAULT_SECRET = "LANLink-Default-Secret-ChangeMe";

    private final CryptoProperties properties;
    private final JweCryptoService jweCryptoService;
    private final Environment environment;

    public CryptoKeyGuard(CryptoProperties properties, JweCryptoService jweCryptoService, Environment environment) {
        this.properties = properties;
        this.jweCryptoService = jweCryptoService;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        List<String> violations = new ArrayList<>();

        String secret = properties.getSecret();
        if (secret == null || secret.isBlank() || DEFAULT_SECRET.equals(secret)) {
            violations.add("lanlink.crypto.secret 仍为默认值，请通过 LANLINK_CRYPTO_SECRET 注入真实主密钥"
                    + "（历史 AES-GCM 密文依赖它才能读出）");
        }
        if (properties.getJwe().isRequired() && !jweCryptoService.available()) {
            violations.add("lanlink.crypto.jwe.required=true 但 JWE 不可用（密钥缺失或解析失败）");
        }
        // JweCryptoService 构造期已校验位数，这里兜底防止后续放宽构造逻辑后闸门失守
        int minBits = properties.getMinRsaBits();
        if (jweCryptoService.available() && jweCryptoService.keyBits() < 2048) {
            throw new IllegalStateException("检测到已被攻破的 RSA-1024 弱密钥（当前位数: " + jweCryptoService.keyBits() + " 位 < 绝对底线 2048 位），系统硬安全门禁强行阻断！");
        }
        if (jweCryptoService.available() && jweCryptoService.keyBits() < minBits) {
            violations.add("JWE 密钥仅 " + jweCryptoService.keyBits() + " 位，低于要求 " + minBits + " 位");
        }

        if (violations.isEmpty()) {
            log.info("[crypto-guard] 加密配置校验通过：jweAvailable={}, rsaBits={}, profile={}",
                    jweCryptoService.available(), jweCryptoService.keyBits(),
                    String.join(",", environment.getActiveProfiles()));
            return;
        }

        String detail = String.join("；", violations);
        if (prod) {
            throw new IllegalStateException("生产环境加密配置不合规：" + detail);
        }
        log.warn("[crypto-guard] 非生产环境加密配置待补：{}（已放行，避免阻断本地开发）", detail);
    }
}

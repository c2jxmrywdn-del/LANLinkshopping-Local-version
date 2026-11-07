package com.lanlink.shopping.module.tracking.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 快递100 推送验签（out/logistics-tracking-plan.md §4.2）：sign=MD5(param+salt) 32 位大写。
 * salt 来自环境变量 TRACKING_KUAIDI100_SALT（D6：不入库不入仓）；未配置即 fail-closed。
 * 推送方向官方仅 MD5 不可升级（§10 签名强度基线），缓解靠 HTTPS + salt 环境变量 +
 * 与来源 IP 核对（IP 核对依赖部署形态，未在本类实现）。
 * 请求形态兼容两种：form 表单 param=...&sign=...（官方默认）与 JSON {"param":..,"sign":..}。
 */
@Component
public class Kuaidi100Verifier implements WebhookVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TrackingProperties properties;

    public Kuaidi100Verifier(TrackingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Set<String> supportedProviders() {
        return Set.of("kuaidi100");
    }

    @Override
    public VerifyResult verify(VerifyRequest request) {
        String salt = properties.getKuaidi100().getSalt();
        if (salt == null || salt.isBlank()) {
            return VerifyResult.fail("kuaidi100 salt not configured");
        }
        String[] paramAndSign = extractParamAndSign(request.rawBody());
        if (paramAndSign == null) {
            return VerifyResult.fail("malformed body");
        }
        String param = paramAndSign[0];
        String sign = paramAndSign[1];
        if (param == null || param.isBlank() || sign == null || sign.isBlank()) {
            return VerifyResult.fail("missing param/sign");
        }
        String expected = md5Upper(param + salt);
        // 官方为 32 位大写；大小写不敏感比对以容忍渠道侧变化，比较本身常量时间防时序侧信道
        return constantTimeEquals(expected, sign.trim().toUpperCase())
                ? VerifyResult.ok()
                : VerifyResult.fail("sign mismatch");
    }

    /** @return [param, sign]，正文不可解析时返回 null */
    private String[] extractParamAndSign(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        String trimmed = rawBody.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = JSON.readTree(trimmed);
                return new String[]{textOrNull(root, "param"), textOrNull(root, "sign")};
            } catch (Exception e) {
                return null;
            }
        }
        Map<String, String> form = new HashMap<>();
        for (String pair : trimmed.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }
        return new String[]{form.get("param"), form.get("sign")};
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** MD5 大写 hex（32 位）——Kuaidi100Adapter 请求端 MD5 兜底签名复用 */
    public static String md5Upper(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    /** 常量时间字符串比较（防时序侧信道），UrlTokenVerifier 复用 */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}

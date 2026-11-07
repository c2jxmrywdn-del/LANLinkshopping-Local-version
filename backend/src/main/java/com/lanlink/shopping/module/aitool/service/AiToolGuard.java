package com.lanlink.shopping.module.aitool.service;

import com.lanlink.shopping.support.AiApiKeyService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * AI 工具网关安全与鉴权守卫。
 *
 * <p>鉴权分两层：先确认调用方身份（内部服务密钥或平台 API Key），再按工具 scope 授权。
 * 兼容历史 {@code tools:call} 只读密钥，但写工具必须显式持有 {@code cart:write}。</p>
 */
@Component
public class AiToolGuard {

    private static final Logger log = LoggerFactory.getLogger(AiToolGuard.class);
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_REQUESTS_PER_MIN = 180;
    private static final String DEFAULT_INTERNAL_SECRET = "lanlink-internal-dev-secret";
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\d{3})\\d{4}(\\d{4})");
    private static final Pattern ROOM_PATTERN = Pattern.compile("(\\d+[-号室幢栋楼单元层])");
    private static final Set<String> READ_PERMISSIONS = Set.of(
            "tools:read", "knowledge:read", "products:read", "orders:read", "logistics:monitor");

    private final String internalSecret;
    private final Environment environment;
    private final AiApiKeyService apiKeyService;
    private final java.util.LinkedHashMap<String, WindowCounter> rateLimiters =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, WindowCounter> eldest) {
                    return size() > 5000;
                }
            };

    @Autowired
    public AiToolGuard(Environment env, @Autowired(required = false) AiApiKeyService apiKeyService) {
        String sec = env.getProperty("ai.internal-secret", "");
        if (!StringUtils.hasText(sec)) {
            sec = env.getProperty("support.proxy-secret", "");
        }
        if (!StringUtils.hasText(sec)) {
            sec = env.getProperty("INTERNAL_GATEWAY_SECRET", DEFAULT_INTERNAL_SECRET);
        }
        this.internalSecret = sec.trim();
        this.environment = env;
        this.apiKeyService = apiKeyService;
    }

    @PostConstruct
    public void warnInsecureDefaults() {
        if (DEFAULT_INTERNAL_SECRET.equals(internalSecret) || internalSecret.contains(DEFAULT_INTERNAL_SECRET)) {
            List<String> profiles = Arrays.asList(environment.getActiveProfiles());
            if (profiles.contains("prod")) {
                throw new IllegalStateException("AI 工具内部密钥仍为默认值；生产环境必须配置 AI_INTERNAL_SECRET/INTERNAL_GATEWAY_SECRET");
            }
            log.warn("[ai-tool-guard] 使用默认内部密钥，仅限本地演示；生产请配置专用 AI_INTERNAL_SECRET");
        }
    }

    public enum AuthType {
        NONE,
        INTERNAL_SECRET,
        API_KEY
    }

    public record AuthContext(AuthType type, boolean internal, Set<String> permissions,
                              Long tenantId, String keyPrefix, String rateClientId) {
        public boolean allowed() {
            return type != AuthType.NONE;
        }

        public boolean hasPermission(String permission) {
            if (internal) return true;
            if (permissions == null || permission == null) return false;
            if (permissions.contains("*") || permissions.contains(permission)) return true;
            return READ_PERMISSIONS.contains(permission)
                    && (permissions.contains("tools:call") || permissions.contains("tools:*"));
        }
    }

    public AuthType authenticate(HttpServletRequest request) {
        return authenticateContext(request).type();
    }

    /** 一次查询得到身份、scope、租户和限流主体，避免同一请求反复访问 API Key 表。 */
    public AuthContext authenticateContext(HttpServletRequest request) {
        if (request == null) return new AuthContext(AuthType.NONE, false, Set.of(), null, "anonymous", "anonymous");

        String suppliedSecret = request.getHeader("X-Internal-Secret");
        if (!StringUtils.hasText(suppliedSecret)) {
            suppliedSecret = request.getHeader("X-Support-Proxy-Secret");
        }
        if (StringUtils.hasText(suppliedSecret) && StringUtils.hasText(internalSecret)) {
            byte[] suppBytes = suppliedSecret.trim().getBytes(StandardCharsets.UTF_8);
            for (String sec : internalSecret.split(",")) {
                if (StringUtils.hasText(sec) && MessageDigest.isEqual(
                        sec.trim().getBytes(StandardCharsets.UTF_8), suppBytes)) {
                    return new AuthContext(AuthType.INTERNAL_SECRET, true, Set.of("*"), null,
                            "internal-secret", request.getRemoteAddr());
                }
            }
        }

        String apiKey = extractApiKey(request);
        if (StringUtils.hasText(apiKey) && apiKeyService != null) {
            AiApiKeyService.Principal principal = apiKeyService.authenticate(apiKey);
            if (principal != null) {
                String clientId = StringUtils.hasText(principal.keyPrefix()) ? principal.keyPrefix() : "api-key";
                return new AuthContext(AuthType.API_KEY, false, principal.permissions(),
                        principal.tenantId(), clientId, clientId);
            }
        }

        return new AuthContext(AuthType.NONE, false, Set.of(), null,
                StringUtils.hasText(apiKey) ? maskedClientKey(request) : maskedClientKey(request),
                maskedClientKey(request));
    }

    public boolean hasPermission(HttpServletRequest request, String permission) {
        return authenticateContext(request).hasPermission(permission);
    }

    public Long tenantId(HttpServletRequest request) {
        return authenticateContext(request).tenantId();
    }

    public String keyPrefix(HttpServletRequest request) {
        return authenticateContext(request).keyPrefix();
    }

    private static String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (StringUtils.hasText(apiKey)) return apiKey.trim();
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private static String maskedClientKey(HttpServletRequest request) {
        String apiKey = extractApiKey(request);
        if (StringUtils.hasText(apiKey)) {
            return apiKey.startsWith("llk_") && apiKey.length() > 12 ? apiKey.substring(0, 12) + "****" : "external-bearer";
        }
        return request.getRemoteAddr();
    }

    /**
     * 限流校验：防止外部单 Key 或单 IP 刷爆数据库或向量检索连接池。
     */
    public boolean checkRateLimit(String clientIdentifier) {
        if (!StringUtils.hasText(clientIdentifier)) clientIdentifier = "anonymous";
        long now = System.currentTimeMillis();
        String key = clientIdentifier.trim();
        synchronized (rateLimiters) {
            WindowCounter counter = rateLimiters.get(key);
            if (counter == null || now - counter.windowStart > WINDOW_MS) {
                counter = new WindowCounter(now);
                rateLimiters.put(key, counter);
            }
            counter.requests++;
            if (counter.requests > MAX_REQUESTS_PER_MIN) {
                log.warn("[ai-tool-guard] rate limit exceeded for client: {}, count={}", key, counter.requests);
                return false;
            }
            return true;
        }
    }

    // ==================== PII 动态脱敏脱敏器 ====================

    /** 手机号脱敏：13812345678 -> 138****5678 */
    public static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) return "";
        String trimmed = phone.trim();
        if (trimmed.length() == 11) {
            return PHONE_PATTERN.matcher(trimmed).replaceAll("$1****$2");
        }
        if (trimmed.length() > 6) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 2);
        }
        return "***";
    }

    /** 姓名脱敏：张三 -> 张*；诸葛孔明 -> 诸**明 */
    public static String maskName(String name) {
        if (!StringUtils.hasText(name)) return "";
        String trimmed = name.trim();
        if (trimmed.length() <= 1) return trimmed + "*";
        if (trimmed.length() == 2) return trimmed.charAt(0) + "*";
        if (trimmed.length() == 3) return trimmed.charAt(0) + "*" + trimmed.charAt(2);
        return trimmed.charAt(0) + "**" + trimmed.charAt(trimmed.length() - 1);
    }

    /** 地址脱敏：保留省市区道路产业园，截断具体楼幢室号 */
    public static String maskAddress(String address) {
        if (!StringUtils.hasText(address)) return "";
        return ROOM_PATTERN.matcher(address).replaceAll("***");
    }

    private static class WindowCounter {
        final long windowStart;
        int requests;

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
            this.requests = 0;
        }
    }
}

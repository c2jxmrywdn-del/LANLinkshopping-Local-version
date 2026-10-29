package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SupportRequestGuard {
    private static final Logger log = LoggerFactory.getLogger(SupportRequestGuard.class);
    private static final long WINDOW_MILLIS = 60_000L;
    // 本地开发/演示环境所有请求共享 127.0.0.1，原 20/min 容易被正常连续提问触发；
    // 提高到 120/min（未知来源仍单独限制），避免用户看到"客服暂时无法响应"。
    private static final int MAX_REQUESTS_PER_WINDOW = 120;
    private static final int MAX_UNKNOWN_REQUESTS_PER_WINDOW = 300;
    private static final int MAX_TRACKED_SOURCES = 10_000;

    private final String expectedProxySecret;
    private final AiApiKeyService apiKeyService;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong rejectedAuth = new AtomicLong();
    private final AtomicLong rejectedRate = new AtomicLong();

    public SupportRequestGuard(Environment environment) {
        this(environment, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SupportRequestGuard(Environment environment,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) AiApiKeyService apiKeyService) {
        this.expectedProxySecret = environment.getProperty("support.proxy-secret", "").trim();
        this.apiKeyService = apiKeyService;
    }

    /** 启动自检：密钥未配置时立刻告警，避免“全部请求 403”类故障静默发生。 */
    @PostConstruct
    public void logConfigStatus() {
        if (StringUtils.hasText(expectedProxySecret)) {
            log.info("[support-monitor] proxy-secret configured (length={}), trusted-proxy auth enabled", expectedProxySecret.length());
        } else {
            log.warn("[support-monitor] proxy-secret is EMPTY - ALL /support/ask requests will be rejected with 403! "
                    + "Set support.proxy-secret (dev: application-dev.yml; prod: SUPPORT_PROXY_SECRET env).");
        }
    }

    public boolean isAuthorizedProxy(HttpServletRequest request) {
        // 1. 优先检查平台 API 密钥（外部系统、移动端或第三方调用）
        String apiKey = request.getHeader("X-API-Key");
        if (!StringUtils.hasText(apiKey)) {
            String authHeader = request.getHeader("Authorization");
            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                apiKey = authHeader.substring(7).trim();
            }
        }
        if (StringUtils.hasText(apiKey) && apiKeyService != null) {
            if (apiKeyService.authorize(apiKey, "support:ask") || apiKeyService.authorize(apiKey, "intent:analyze")) {
                return true;
            }
        }

        // 2. 检查内部可信反代专有密钥
        String supplied = request.getHeader("X-Support-Proxy-Secret");
        if (!StringUtils.hasText(expectedProxySecret)) {
            rejectedAuth.incrementAndGet();
            log.warn("[support-monitor] reject #{}: server proxy-secret not configured (from {})",
                    rejectedAuth.get(), request.getRemoteAddr());
            return false;
        }
        if (!StringUtils.hasText(supplied)) {
            rejectedAuth.incrementAndGet();
            log.warn("[support-monitor] reject #{}: missing X-Support-Proxy-Secret header (from {}) - "
                    + "request did not pass through the trusted proxy layer", rejectedAuth.get(), request.getRemoteAddr());
            return false;
        }
        boolean matched = MessageDigest.isEqual(expectedProxySecret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            rejectedAuth.incrementAndGet();
            log.warn("[support-monitor] reject #{}: proxy secret MISMATCH (from {}, supplied length={}) - "
                    + "check Vite proxy / Vercel proxy secret consistency with support.proxy-secret",
                    rejectedAuth.get(), request.getRemoteAddr(), supplied.length());
        }
        return matched;
    }

    public boolean allowRequest(String clientAddress) {
        if (!StringUtils.hasText(clientAddress)) {
            rejectedRate.incrementAndGet();
            log.warn("[support-monitor] rate-reject #{}: blank client IP", rejectedRate.get());
            return false;
        }
        String key = clientAddress.trim();
        boolean unknownSource = "unknown".equalsIgnoreCase(key);
        if (!unknownSource && (key.length() > 64 || !key.matches("[0-9a-fA-F:.]{2,64}"))) {
            rejectedRate.incrementAndGet();
            log.warn("[support-monitor] rate-reject #{}: invalid client IP format '{}'", rejectedRate.get(), key);
            return false;
        }
        int requestLimit = unknownSource ? MAX_UNKNOWN_REQUESTS_PER_WINDOW : MAX_REQUESTS_PER_WINDOW;

        long now = System.currentTimeMillis();
        if (windows.size() >= MAX_TRACKED_SOURCES) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        }
        if (windows.size() >= MAX_TRACKED_SOURCES && !windows.containsKey(key)) {
            rejectedRate.incrementAndGet();
            log.warn("[support-monitor] rate-reject #{}: tracked source limit reached, new source '{}'", rejectedRate.get(), key);
            return false;
        }
        boolean[] allowed = {false};
        windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt >= WINDOW_MILLIS) {
                allowed[0] = true;
                return new Window(now, 1);
            }
            if (existing.count >= requestLimit) return existing;
            existing.count++;
            allowed[0] = true;
            return existing;
        });
        if (!allowed[0]) {
            rejectedRate.incrementAndGet();
            log.warn("[support-monitor] rate-reject #{}: rate limit exceeded for '{}' ({} req / {} s)",
                    rejectedRate.get(), key, requestLimit, WINDOW_MILLIS / 1000);
        }
        return allowed[0];
    }

    /** 监控快照：供运维排查时快速读取拒绝计数。 */
    public String monitorSnapshot() {
        return "rejectedAuth=" + rejectedAuth.get() + ", rejectedRate=" + rejectedRate.get()
                + ", trackedSources=" + windows.size();
    }

    private static final class Window {
        private final long startedAt;
        private int count;
        private Window(long startedAt, int count) {
            this.startedAt = startedAt;
            this.count = count;
        }
    }
}

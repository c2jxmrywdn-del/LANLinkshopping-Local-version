package com.lanlink.shopping.support;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SupportRequestGuard {
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_REQUESTS_PER_WINDOW = 20;
    private static final int MAX_UNKNOWN_REQUESTS_PER_WINDOW = 300;
    private static final int MAX_TRACKED_SOURCES = 10_000;

    private final String expectedProxySecret;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public SupportRequestGuard(Environment environment) {
        this.expectedProxySecret = environment.getProperty("support.proxy-secret", "").trim();
    }

    public boolean isAuthorizedProxy(HttpServletRequest request) {
        String supplied = request.getHeader("X-Support-Proxy-Secret");
        if (!StringUtils.hasText(expectedProxySecret) || !StringUtils.hasText(supplied)) return false;
        return MessageDigest.isEqual(expectedProxySecret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public boolean allowRequest(String clientAddress) {
        if (!StringUtils.hasText(clientAddress)) return false;
        String key = clientAddress.trim();
        boolean unknownSource = "unknown".equalsIgnoreCase(key);
        if (!unknownSource && (key.length() > 64 || !key.matches("[0-9a-fA-F:.]{2,64}"))) return false;
        int requestLimit = unknownSource ? MAX_UNKNOWN_REQUESTS_PER_WINDOW : MAX_REQUESTS_PER_WINDOW;

        long now = System.currentTimeMillis();
        if (windows.size() >= MAX_TRACKED_SOURCES) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        }
        if (windows.size() >= MAX_TRACKED_SOURCES && !windows.containsKey(key)) return false;
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
        return allowed[0];
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

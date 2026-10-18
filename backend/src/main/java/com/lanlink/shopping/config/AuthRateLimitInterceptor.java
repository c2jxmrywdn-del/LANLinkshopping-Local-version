package com.lanlink.shopping.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Endpoint-specific throttling for public authentication routes.
 *
 * The shared MySQL counter store coordinates requests across application
 * replicas without requiring a separately provisioned Redis service. Only
 * SHA-256 keys are persisted; raw IP addresses are not stored in the table.
 * If the database-side store is unavailable, a bounded local fallback keeps
 * the application available while emitting a warning.
 */
@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthRateLimitInterceptor.class);

    private static final long WINDOW_MILLIS = 60_000L;
    private static final long IDLE_TTL_MILLIS = 15 * 60_000L;
    private static final int MAX_COUNTERS = 20_000;
    private static final long STORE_RETRY_MILLIS = 60_000L;
    private static final int CLEANUP_BATCH_SIZE = 1_000;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS t_auth_rate_limit_counter (
                rate_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                request_count INT UNSIGNED NOT NULL DEFAULT 0,
                expires_at_ms BIGINT NOT NULL,
                PRIMARY KEY (rate_key),
                KEY idx_auth_rate_limit_expires (expires_at_ms)
            ) ENGINE=InnoDB
            """;

    /*
     * LAST_INSERT_ID(expr) returns this request's own atomic counter value on
     * the same MySQL connection, avoiding a racy second SELECT of the count.
     */
    private static final String INCREMENT_COUNTER_SQL = """
            INSERT INTO t_auth_rate_limit_counter (rate_key, request_count, expires_at_ms)
            VALUES (?, LAST_INSERT_ID(1), ?)
            ON DUPLICATE KEY UPDATE request_count = LAST_INSERT_ID(request_count + 1)
            """;

    private static final String READ_LAST_INSERT_ID_SQL = "SELECT LAST_INSERT_ID()";
    private static final String CLEANUP_COUNTERS_SQL =
            "DELETE FROM t_auth_rate_limit_counter WHERE expires_at_ms < ? LIMIT " + CLEANUP_BATCH_SIZE;

    private static final Map<String, Policy> POLICIES = Map.of(
            "/auth/login", new Policy(8, 120),
            "/auth/login/2fa", new Policy(8, 120),
            "/auth/register", new Policy(4, 30),
            "/user/phone-code", new Policy(5, 30),
            "/user/email-code", new Policy(5, 30),
            "/order/checkout", new Policy(10, 60),
            "/wallet/pay", new Policy(10, 60),
            "/wallet/recharge", new Policy(10, 60)
    );

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, WindowCounter> localCounters = new ConcurrentHashMap<>();
    private final AtomicLong handledRequests = new AtomicLong();
    private final AtomicLong nextSharedRetryAt = new AtomicLong(0L);
    private final AtomicLong nextSharedCleanupAt = new AtomicLong(0L);
    private final AtomicBoolean sharedStoreWarningLogged = new AtomicBoolean();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile JdbcTemplate jdbcTemplate;
    private volatile boolean sharedStoreReady;

    public AuthRateLimitInterceptor() {
        this(System::currentTimeMillis);
    }

    AuthRateLimitInterceptor(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired(required = false)
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeSharedStore(clock.getAsLong());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = applicationPath(request);
        Policy policy = POLICIES.get(path);
        if (policy == null) {
            return true;
        }

        long now = clock.getAsLong();
        String railwayIp = normalizeIp(request.getHeader("X-Real-IP"));
        if (railwayIp == null) {
            railwayIp = normalizeIp(request.getRemoteAddr());
        }
        if (railwayIp == null) {
            railwayIp = "unknown";
        }

        // Keep an ingress-source ceiling as a backstop when callers forge the
        // optional client-IP header on direct-origin requests.
        boolean ingressAllowed = allow("ingress|" + path + "|" + railwayIp, policy.ingressLimit, now);

        String clientIp = normalizeIp(request.getHeader("CF-Connecting-IP"));
        if (clientIp == null && !isVercelForwardedRequest(request)) {
            clientIp = railwayIp.equals("unknown") ? null : railwayIp;
        }
        boolean clientAllowed = clientIp == null
                || allow("client|" + path + "|" + clientIp, policy.clientLimit, now);

        cleanupIfNeeded(now);
        if (ingressAllowed && clientAllowed) {
            return true;
        }

        response.setStatus(429);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Retry-After", "60");
        response.setHeader("Cache-Control", "no-store");
        String message = path.startsWith("/auth/") ? "认证请求过于频繁，请稍后再试" : "请求过于频繁，请稍后再试";
        response.getWriter().write(objectMapper.writeValueAsString(
                R.fail(429, message)));
        return false;
    }

    private boolean allow(String key, int limit, long now) {
        JdbcTemplate template = jdbcTemplate;
        if (template != null) {
            if (!sharedStoreReady && now >= nextSharedRetryAt.get()) {
                initializeSharedStore(now);
            }
            if (sharedStoreReady) {
                try {
                    int count = incrementSharedCounter(template, key, now);
                    cleanupSharedCountersIfDue(template, now);
                    return count <= limit;
                } catch (Exception ex) {
                    sharedStoreReady = false;
                    nextSharedRetryAt.set(now + STORE_RETRY_MILLIS);
                    logSharedStoreFailure(ex);
                }
            }
        }
        return allowLocal(key, limit, now);
    }

    private int incrementSharedCounter(JdbcTemplate template, String logicalKey, long now) {
        long windowStart = Math.floorDiv(now, WINDOW_MILLIS) * WINDOW_MILLIS;
        String hashedKey = sha256(logicalKey + "|" + windowStart);
        Long expiresAt = windowStart + 2 * WINDOW_MILLIS;

        Integer count = template.execute((ConnectionCallback<Integer>) connection -> {
            try (PreparedStatement increment = connection.prepareStatement(INCREMENT_COUNTER_SQL)) {
                increment.setString(1, hashedKey);
                increment.setLong(2, expiresAt);
                increment.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(READ_LAST_INSERT_ID_SQL)) {
                return result.next() ? result.getInt(1) : null;
            }
        });

        if (count == null) {
            throw new IllegalStateException("Shared auth rate-limit counter returned no value");
        }
        return count;
    }

    private void initializeSharedStore(long now) {
        JdbcTemplate template = jdbcTemplate;
        if (template == null) {
            return;
        }
        try {
            template.execute(CREATE_TABLE_SQL);
            sharedStoreReady = true;
            nextSharedRetryAt.set(0L);
            sharedStoreWarningLogged.set(false);
            LOGGER.info("Shared authentication rate-limit store is ready");
        } catch (Exception ex) {
            sharedStoreReady = false;
            nextSharedRetryAt.set(now + STORE_RETRY_MILLIS);
            logSharedStoreFailure(ex);
        }
    }

    private void cleanupSharedCountersIfDue(JdbcTemplate template, long now) {
        long next = nextSharedCleanupAt.get();
        if (now < next || !nextSharedCleanupAt.compareAndSet(next, now + WINDOW_MILLIS)) {
            return;
        }
        try {
            template.update(CLEANUP_COUNTERS_SQL, now);
        } catch (DataAccessException ex) {
            // Cleanup is best-effort; a failed cleanup must not disable the
            // active atomic counter path.
            LOGGER.warn("Could not clean expired authentication rate-limit counters", ex);
        }
    }

    private void logSharedStoreFailure(Exception ex) {
        if (sharedStoreWarningLogged.compareAndSet(false, true)) {
            LOGGER.warn("Shared authentication rate-limit store unavailable; using bounded process-local fallback", ex);
        }
    }

    private boolean allowLocal(String key, int limit, long now) {
        WindowCounter counter = localCounters.get(key);
        if (counter == null) {
            if (localCounters.size() >= MAX_COUNTERS) {
                cleanupStaleLocalCounters(now);
            }
            if (localCounters.size() >= MAX_COUNTERS) {
                return false;
            }
            WindowCounter candidate = new WindowCounter(now);
            WindowCounter previous = localCounters.putIfAbsent(key, candidate);
            counter = previous == null ? candidate : previous;
        }
        return counter.tryAcquire(limit, now);
    }

    private void cleanupIfNeeded(long now) {
        if ((handledRequests.incrementAndGet() & 0xffL) == 0L || localCounters.size() >= MAX_COUNTERS) {
            cleanupStaleLocalCounters(now);
        }
    }

    private void cleanupStaleLocalCounters(long now) {
        localCounters.entrySet().removeIf(entry -> entry.getValue().isIdle(now, IDLE_TTL_MILLIS));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || uri.isBlank()) {
            uri = request.getServletPath();
        }
        if (uri == null) {
            return "";
        }
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    private static boolean isVercelForwardedRequest(HttpServletRequest request) {
        return hasText(request.getHeader("x-vercel-id"))
                || hasText(request.getHeader("x-vercel-deployment-url"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Accept only IP literals. Never resolve arbitrary header values as DNS
     * names and never fall back to X-Forwarded-For's untrusted proxy chain.
     */
    private static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64 || value.indexOf(',') >= 0
                || value.indexOf('%') >= 0 || value.startsWith("[") || value.endsWith("]")) {
            return null;
        }

        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:]+")) {
                return null;
            }
            try {
                return InetAddress.getByName(value).getHostAddress().toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }

        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value;
    }

    private record Policy(int clientLimit, int ingressLimit) {
    }

    private static final class WindowCounter {
        private long windowStart;
        private long lastSeen;
        private int count;

        private WindowCounter(long now) {
            this.windowStart = now;
            this.lastSeen = now;
        }

        private synchronized boolean tryAcquire(int limit, long now) {
            lastSeen = now;
            if (now < windowStart || now - windowStart >= WINDOW_MILLIS) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        private synchronized boolean isIdle(long now, long idleTtlMillis) {
            return now >= lastSeen && now - lastSeen >= idleTtlMillis;
        }
    }
}

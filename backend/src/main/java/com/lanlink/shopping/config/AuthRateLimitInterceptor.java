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

    /**
     * 限流策略表。
     *  - clientLimit：按真实客户端 IP（CF-Connecting-IP，Vercel 部署形态）限；
     *  - ingressLimit：按入口源（X-Real-IP/remoteAddr）限，承载开发态多用户共享 127.0.0.1 桶的聚合余量；
     *  - throttleGet：GET 端点显式开启限流（缺省仅 POST，保持认证类策略原有语义）。
     * AI 推理类（/kb、/price-monitor AI）与公开写库类（/ab、/ad/track、/merchant/traffic/events）
     * 端点此前完全无限流：登录/匿名调用方可无限占用单卡串行推理队列或刷写日志表。
     * 商品浏览类端点（/product、/home、/category、/recommendations）一律不进入本表。
     */
    private static final Map<String, Policy> POLICIES = Map.ofEntries(
            Map.entry("/auth/login", new Policy(8, 120)),
            Map.entry("/auth/login/2fa", new Policy(8, 120)),
            Map.entry("/auth/register", new Policy(4, 30)),
            Map.entry("/user/phone-code", new Policy(5, 30)),
            Map.entry("/user/email-code", new Policy(5, 30)),
            Map.entry("/order/checkout", new Policy(10, 60)),
            Map.entry("/wallet/pay", new Policy(10, 60)),
            Map.entry("/wallet/recharge", new Policy(10, 60)),
            // ---- A/B 实验公开上报（写 t_ab_log；服务端另有实验/变体校验与匿名去重兜底）----
            Map.entry("/ab/expose", new Policy(60, 240)),
            Map.entry("/ab/convert", new Policy(30, 120)),
            // ---- 广告曝光/点击上报（曝光随页面渲染高频，阈值取浏览余量）----
            Map.entry("/ad/track", new Policy(60, 240)),
            // ---- 技术服务写端点（需求/咨询/评价/托管/交易/发帖/专家申请/里程碑推进）：
            // 此前完全无限流，登录配方可脚本化刷写 t_tech_* 与陪跑项目；GET 缺省豁免，公开浏览不受影响
            Map.entry("/tech/*", new Policy(10, 60)),
            // ---- 材料上传/绑定/下线（字节解码 + 对象写入 + 状态变更）----
            // 下线是 DELETE：本策略是唯一打开 throttleDelete 的条目，其它端点语义不变
            Map.entry("/attachment/*", new Policy(10, 60, false, true)),
            // ---- 商品主图上传（5MB 图片解码 + 对象写入公读桶；须与 WebConfig 路径注册同步，
            // 只加策略不注册路径等于无限流——本项目踩过。权限另由 @RequirePerm("product:publish") 把守）
            Map.entry("/product/image", new Policy(10, 60)),
            // ---- AI 标题润色（out/ai-polish-design.md §3.3）：单请求 10-30s 模型推理 + 真实计费外呼，
            // 按分钟桶约束占用。/product/ai/* 是前缀匹配键（policyFor 只对 "/*" 后缀键做 startsWith，
            // "/**" 后缀键在本表不参与前缀分支，勿照抄 /internal/ai/tools/** 那对失效条目），
            // 命中 /product/ai/polish-title；须与 WebConfig 路径注册同步。商户级公平配额另由
            // ProductTitleAiService 双层配额闸承担，本表只是 IP/入口维度
            Map.entry("/product/ai/*", new Policy(10, 120)),
            // ---- 技术服务运营台写操作（邀请/审核/状态变更/指派/推进），读不受限 ----
            Map.entry("/admin/tech-service/*", new Policy(30, 60)),
            // ---- 商户流量事件上报（登录态逐请求 INSERT，需限流防表膨胀）----
            Map.entry("/merchant/traffic/events", new Policy(60, 240)),
            // ---- AI 推理端点：单请求 10-60s 串行 GPU 推理，按分钟桶约束占用 ----
            // ingress 取 120：开发态多用户经 Vite 代理共享 127.0.0.1 入口桶时的聚合余量
            Map.entry("/kb/ask", new Policy(10, 120)),
            Map.entry("/kb/search", new Policy(30, 120, true)),
            // ---- AI 工具网关：先按 IP/入口挡 401 洪水，再进入 API Key scope 校验 ----
            Map.entry("/internal/ai/tools/*", new Policy(120, 600, true)),
            Map.entry("/internal/ai/tools/**", new Policy(120, 600, true)),
            // ---- 物流跟踪回调推送（白名单公开路径）：合法渠道重推（快递100 每单最多 3 次）
            // 远低于阈值；防的是伪造洪水。policyFor 前缀匹配同时覆盖 /{provider}/{token} 深层路径 ----
            Map.entry("/logistics/callback/*", new Policy(120, 600)),
            Map.entry("/price-monitor/ai-analysis/*", new Policy(20, 120, true)),
            Map.entry("/price-monitor/ask", new Policy(10, 120))
    );

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, WindowCounter> localCounters = new ConcurrentHashMap<>();
    private final AtomicLong handledRequests = new AtomicLong();
    private final AtomicLong nextSharedRetryAt = new AtomicLong(0L);
    private final AtomicLong nextSharedCleanupAt = new AtomicLong(0L);
    private final AtomicLong nextRiskRecordAt = new AtomicLong(0L);
    private final AtomicBoolean sharedStoreWarningLogged = new AtomicBoolean();
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 风控事件旁路（可选依赖：单元测试与无风控表环境不注入则跳过打分） */
    private volatile com.lanlink.shopping.module.risk.service.RiskEventService riskEventService;
    private static final long RISK_RECORD_GAP_MILLIS = 5_000L;

    private volatile JdbcTemplate jdbcTemplate;
    private volatile boolean sharedStoreReady;

    public AuthRateLimitInterceptor() {
        this(System::currentTimeMillis);
    }

    public AuthRateLimitInterceptor(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Autowired(required = false)
    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeSharedStore(clock.getAsLong());
    }

    @Autowired(required = false)
    public void setRiskEventService(com.lanlink.shopping.module.risk.service.RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = applicationPath(request);
        Policy policy = policyFor(path);
        if (policy == null) {
            return true;
        }

        // 认证类策略保持仅 POST 限流的原语义；AI 检索等 GET 端点由策略显式开启 GET 限流，
        // 材料下线这类"改状态 + 删对象"的 DELETE 端点由策略显式开启 DELETE 限流（默认关，不影响既有端点）
        String method = request.getMethod();
        boolean post = "POST".equalsIgnoreCase(method);
        boolean throttledMethod = post
                || (policy.throttleGet() && "GET".equalsIgnoreCase(method))
                || (policy.throttleDelete() && "DELETE".equalsIgnoreCase(method));
        if (!throttledMethod) {
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

        recordRiskOnLimit(request, path, now);

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

    /** 精确匹配优先，其次前缀策略键（以 "/*" 结尾，如 /price-monitor/ai-analysis/*）。 */
    private static Policy policyFor(String path) {
        Policy exact = POLICIES.get(path);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Policy> entry : POLICIES.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("/*") && path.startsWith(key.substring(0, key.length() - 1))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 限流命中时的风控旁路打分（仅登录用户可归因；匿名请求 UserContext 为空直接跳过）。
     * 规则码 R08_AI_ABUSE 新增于既有 R01-R07 之外，落 t_user_risk_event 供运营复核。
     * 双重节流：本方法全局 5s 一次 + RiskEventService 内部同用户同规则 24h 幂等，防刷风控表。
     */
    private void recordRiskOnLimit(HttpServletRequest request, String path, long now) {
        try {
            long prev = nextRiskRecordAt.get();
            if (now < prev || !nextRiskRecordAt.compareAndSet(prev, now + RISK_RECORD_GAP_MILLIS)) {
                return;
            }
            com.lanlink.shopping.module.risk.service.RiskEventService risk = riskEventService;
            if (risk == null) {
                return;
            }
            Long userId = UserContext.currentUserId(request);
            if (userId == null) {
                return;
            }
            risk.record(userId, "R08_AI_ABUSE", 20, "rate limited: " + path, "endpoint", path);
        } catch (Exception ignored) {
            // 风控旁路绝不影响限流主流程
        }
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

    private record Policy(int clientLimit, int ingressLimit, boolean throttleGet, boolean throttleDelete) {
        Policy(int clientLimit, int ingressLimit) {
            this(clientLimit, ingressLimit, false, false);
        }

        Policy(int clientLimit, int ingressLimit, boolean throttleGet) {
            this(clientLimit, ingressLimit, throttleGet, false);
        }
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

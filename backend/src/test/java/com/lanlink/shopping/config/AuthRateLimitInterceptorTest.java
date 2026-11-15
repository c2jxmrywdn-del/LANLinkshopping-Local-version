package com.lanlink.shopping.config;

import com.lanlink.shopping.integration.security.PermInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRateLimitInterceptorTest {

    private static final long FIXED_TIME_MILLIS = 1_800_000_000_000L;
    private AuthRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthRateLimitInterceptor(() -> FIXED_TIME_MILLIS);
    }

    @Test
    void throttlesRepeatedMaterialDeletionsForOneClient() throws Exception {
        // 材料下线是"改状态 + 删对象"的写操作，此前拦截器只统计 POST，DELETE 完全放行
        for (int i = 0; i < 10; i++) {
            assertTrue(call("DELETE", "/attachment/AT20261104134320A9", "203.0.113.9", null, null).allowed(),
                    "前 10 次下线应放行，第 " + (i + 1) + " 次被误拦");
        }

        Result blocked = call("DELETE", "/attachment/AT20261104134320A9", "203.0.113.9", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
    }

    @Test
    void leavesOtherEndpointsDeleteUnthrottled() throws Exception {
        // 新策略只作用于 /attachment/*：不得顺手改掉其它端点已有的 DELETE 语义，
        // 否则并行施工的 /tech/** 下线操作会被莫名限流
        for (int i = 0; i < 25; i++) {
            assertTrue(call("DELETE", "/tech/demands/1", "203.0.113.10", null, null).allowed(),
                    "第 " + (i + 1) + " 次 /tech DELETE 不应受限");
        }
    }

    @Test
    void limitsRepeatedLoginAttemptsPerClient() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertTrue(call("/auth/login", "198.51.100.23", null, null).allowed());
        }

        Result blocked = call("/auth/login", "198.51.100.23", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
        assertEquals("60", blocked.response().getHeader("Retry-After"));
    }

    @Test
    void registrationHasItsOwnLowerLimit() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertTrue(call("/auth/register", "198.51.100.24", null, null).allowed());
        }
        assertFalse(call("/auth/register", "198.51.100.24", null, null).allowed());

        // Login policy is independent from the registration policy.
        assertTrue(call("/auth/login", "198.51.100.24", null, null).allowed());
    }

    @Test
    void ignoresOtherRoutesAndNonPostRequests() throws Exception {
        assertTrue(call("GET", "/auth/login", "198.51.100.25", null, null).allowed());
        assertTrue(call("/auth/me", "198.51.100.25", null, null).allowed());
    }

    @Test
    void forgedCloudflareHeaderCannotBypassIngressCeiling() throws Exception {
        for (int i = 1; i <= 120; i++) {
            assertTrue(call("/auth/login", "198.51.100.26", "203.0.113." + i, null).allowed());
        }
        assertFalse(call("/auth/login", "198.51.100.26", "203.0.113.200", null).allowed());
    }

    @Test
    void sharedDatabaseStoreCoordinatesSeparateInterceptorInstances() throws Exception {
        SharedCounterJdbcTemplate sharedStore = new SharedCounterJdbcTemplate();
        AuthRateLimitInterceptor first = new AuthRateLimitInterceptor(() -> FIXED_TIME_MILLIS);
        AuthRateLimitInterceptor second = new AuthRateLimitInterceptor(() -> FIXED_TIME_MILLIS);
        first.setJdbcTemplate(sharedStore);
        second.setJdbcTemplate(sharedStore);

        for (int i = 0; i < 8; i++) {
            AuthRateLimitInterceptor target = (i % 2 == 0) ? first : second;
            assertTrue(call(target, "/auth/login", "198.51.100.27", null, null).allowed());
        }

        Result blocked = call(second, "/auth/login", "198.51.100.27", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
    }

    // ===== P1-11 限流长尾：此前完全无限流的登录态/匿名写端点 =====

    @Test
    void throttlesAnonymousLiveInteractionsPerClient() throws Exception {
        // 直播间匿名互动（点赞/弹幕）60/min：前 60 次放行，第 61 次拦 429
        for (int i = 0; i < 60; i++) {
            assertTrue(call("POST", "/live/room/1/like", "203.0.113.30", null, null).allowed(),
                    "第 " + (i + 1) + " 次点赞应放行");
        }
        Result blocked = call("POST", "/live/room/1/like", "203.0.113.30", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
    }

    @Test
    void throttlesChatMessagesAndMarketingImageTasks() throws Exception {
        // 买家 IM 发消息 60/min
        for (int i = 0; i < 60; i++) {
            assertTrue(call("POST", "/chat/1/messages", "203.0.113.31", null, null).allowed());
        }
        assertFalse(call("POST", "/chat/1/messages", "203.0.113.31", null, null).allowed());

        // 营销图 AI 生成 10/min（秒级图像推理 + 计费外呼，对齐 /product/ai/* 口径）
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/merchant/marketing-image/tasks", "203.0.113.32", null, null).allowed());
        }
        Result blocked = call("POST", "/merchant/marketing-image/tasks", "203.0.113.32", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
    }

    @Test
    void throttlesClaimLikeWriteEndpointsWithFundGradeLimits() throws Exception {
        // 任务领取/会员签到/订阅/账期：资金与账本类 10/min
        for (String path : new String[]{"/task/claim", "/membership/checkin",
                "/subscription/purchase", "/credit-term/apply",
                "/order/pay/ORD1", "/order/rma/apply", "/product/publish",
                "/merchant/apply", "/user/avatar"}) {
            for (int i = 0; i < 10; i++) {
                assertTrue(call("POST", path, "203.0.113.33", null, null).allowed(),
                        path + " 前 10 次应放行");
            }
            assertFalse(call("POST", path, "203.0.113.33", null, null).allowed(),
                    path + " 第 11 次应被限流");
        }
    }

    @Test
    void campaignPrefixPoliciesTakeLongestMatch() throws Exception {
        // 最长前缀匹配：/campaign/coupons/batch-claim 命中 /campaign/coupons/*（30/min），
        // 领券 30 次内不受 /campaign/* 策略漂移影响；第 31 次被拦
        for (int i = 0; i < 30; i++) {
            assertTrue(call("POST", "/campaign/coupons/batch-claim", "203.0.113.34", null, null).allowed());
        }
        assertFalse(call("POST", "/campaign/coupons/batch-claim", "203.0.113.34", null, null).allowed());

        // /campaign/{id}/claim 命中 /campaign/*（30/min），独立于上一条的桶
        for (int i = 0; i < 30; i++) {
            assertTrue(call("POST", "/campaign/9/claim", "203.0.113.35", null, null).allowed());
        }
        assertFalse(call("POST", "/campaign/9/claim", "203.0.113.35", null, null).allowed());
    }

    @Test
    void adCampaignExactAndPrefixEntriesBothThrottle() throws Exception {
        // POST /ad/campaigns 无子路径：精确条目生效
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/ad/campaigns", "203.0.113.36", null, null).allowed());
        }
        assertFalse(call("POST", "/ad/campaigns", "203.0.113.36", null, null).allowed());

        // /ad/campaigns/{id}/pay：前缀条目生效
        for (int i = 0; i < 10; i++) {
            assertTrue(call("POST", "/ad/campaigns/1/pay", "203.0.113.37", null, null).allowed());
        }
        assertFalse(call("POST", "/ad/campaigns/1/pay", "203.0.113.37", null, null).allowed());

        // 匿名上报 /ad/track 有自己的公开上报桶，不受商户端策略影响
        assertTrue(call("POST", "/ad/track", "203.0.113.38", null, null).allowed());
    }

    @Test
    void supportIntentAnalyzeThrottlesThirdPartyKeyChannel() throws Exception {
        // 客服意图分析（LLM 推理 + 第三方 API Key 直调）20/min
        for (int i = 0; i < 20; i++) {
            assertTrue(call("POST", "/support/intent/analyze", "203.0.113.39", null, null).allowed());
        }
        Result blocked = call("POST", "/support/intent/analyze", "203.0.113.39", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());
    }

    @Test
    void invitePoliciesExistAndThrottleGetLink() throws Exception {
        // GET /invite/link 首次访问会生成并写库：必须开启 GET 限流，60/min
        for (int i = 0; i < 60; i++) {
            assertTrue(call("GET", "/invite/link", "203.0.113.40", null, null).allowed(),
                    "第 " + (i + 1) + " 次获取邀请链接应放行");
        }
        Result blocked = call("GET", "/invite/link", "203.0.113.40", null, null);
        assertFalse(blocked.allowed());
        assertEquals(429, blocked.response().getStatus());

        // rotate 与运营发码：10/min
        for (String path : new String[]{"/invite/link/rotate", "/invite/admin/merchant-apply"}) {
            for (int i = 0; i < 10; i++) {
                assertTrue(call("POST", path, "203.0.113.41", null, null).allowed(),
                        path + " 前 10 次应放行");
            }
            assertFalse(call("POST", path, "203.0.113.41", null, null).allowed(),
                    path + " 第 11 次应被限流");
        }
    }

    /**
     * 只加 POLICIES 不注册 WebConfig 路径等于无限流（本项目踩过）：
     * 断言策略键存在，且限流拦截器确实挂在三个邀请端点上。
     */
    @Test
    void invitePoliciesAreRegisteredInWebConfigPaths() {
        Set<String> expected = Set.of("/invite/link", "/invite/link/rotate",
                "/invite/admin/merchant-apply");
        assertTrue(AuthRateLimitInterceptor.policyKeys().containsAll(expected),
                "POLICIES 缺少邀请端点策略");

        AuthRateLimitInterceptor limiter = new AuthRateLimitInterceptor(() -> FIXED_TIME_MILLIS);
        WebConfig webConfig = new WebConfig(mock(SecurityRouteGuardInterceptor.class), limiter,
                mock(AuthInterceptor.class), mock(PermInterceptor.class));
        InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
        webConfig.addInterceptors(registry);

        Set<String> registered = registry.registrations().stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() == limiter)
                .flatMap(mapped -> java.util.Arrays.stream(mapped.getPathPatterns()))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(registered.containsAll(expected),
                "限流拦截器未注册邀请路径: " + registered);
    }

    /** getInterceptors() 在 Spring 6 为 protected，用测试子类暴露只读快照。 */
    private static final class InspectableInterceptorRegistry extends InterceptorRegistry {
        List<Object> registrations() {
            return getInterceptors();
        }
    }

    private Result call(String path, String xRealIp, String cloudflareIp, String vercelId) throws Exception {
        return call(interceptor, "POST", path, xRealIp, cloudflareIp, vercelId);
    }

    private Result call(String method, String path, String xRealIp, String cloudflareIp, String vercelId) throws Exception {
        return call(interceptor, method, path, xRealIp, cloudflareIp, vercelId);
    }

    private Result call(AuthRateLimitInterceptor target, String path, String xRealIp,
                        String cloudflareIp, String vercelId) throws Exception {
        return call(target, "POST", path, xRealIp, cloudflareIp, vercelId);
    }

    private Result call(AuthRateLimitInterceptor target, String method, String path, String xRealIp,
                        String cloudflareIp, String vercelId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setContextPath("/api");
        request.setRequestURI("/api" + path);
        if (xRealIp != null) request.addHeader("X-Real-IP", xRealIp);
        if (cloudflareIp != null) request.addHeader("CF-Connecting-IP", cloudflareIp);
        if (vercelId != null) request.addHeader("x-vercel-id", vercelId);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = target.preHandle(request, response, new Object());
        return new Result(allowed, response);
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {
    }

    /**
     * Small in-memory JDBC double which executes the same connection callback
     * shape as JdbcTemplate and simulates MySQL's per-connection LAST_INSERT_ID.
     */
    private static final class SharedCounterJdbcTemplate extends JdbcTemplate {
        private final Map<String, Integer> counts = new ConcurrentHashMap<>();
        private final ThreadLocal<Integer> lastInsertId = ThreadLocal.withInitial(() -> 0);

        @Override
        public void execute(String sql) {
            // Treat CREATE TABLE IF NOT EXISTS as successful in unit tests.
        }

        @Override
        public int update(String sql, Object... args) {
            // Expired-counter cleanup is best-effort and does not change the
            // shared counter fixture's state.
            return 0;
        }

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            Connection connection = mock(Connection.class);
            try {
                when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                    PreparedStatement prepared = mock(PreparedStatement.class);
                    AtomicReference<String> key = new AtomicReference<>();
                    doAnswer(call -> {
                        key.set(call.getArgument(1));
                        return null;
                    }).when(prepared).setString(eq(1), anyString());
                    when(prepared.executeUpdate()).thenAnswer(call -> {
                        int current = counts.merge(key.get(), 1, Integer::sum);
                        lastInsertId.set(current);
                        return current == 1 ? 1 : 2;
                    });
                    return prepared;
                });

                Statement statement = mock(Statement.class);
                when(connection.createStatement()).thenReturn(statement);
                when(statement.executeQuery("SELECT LAST_INSERT_ID()")).thenAnswer(invocation -> {
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.next()).thenReturn(true);
                    when(resultSet.getInt(1)).thenAnswer(call -> lastInsertId.get());
                    return resultSet;
                });
                return action.doInConnection(connection);
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}

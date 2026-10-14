package com.lanlink.shopping.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
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

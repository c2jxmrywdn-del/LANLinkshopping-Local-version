package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.config.AuthInterceptor;
import com.lanlink.shopping.config.AuthRateLimitInterceptor;
import com.lanlink.shopping.config.SecurityRouteGuardInterceptor;
import com.lanlink.shopping.config.WebConfig;
import com.lanlink.shopping.integration.security.PermInterceptor;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 限流三处同步改动静态断言（out/logistics-tracking-plan.md G1-6 的可离线部分）：
 * ①POLICIES 含 /logistics/callback/*（Policy 120/600，POST-only）；
 * ②WebConfig 限流段实际注册 /logistics/callback/**（"只加策略不注册路径等于无限流"陷阱）。
 * 白名单第三处（AuthInterceptor.startsWith("/logistics/callback/")）由
 * TrackingCallbackWhitelistTest 以反射断言。第 121 次 POST → 429 的运行时断言
 * 依赖 MySQL 计数器表/容器环境，归入 P2 集成档（G1-6 集成侧），此处为可离线静态部分。
 */
class TrackingRateLimitRegistrationTest {

    @Test
    @DisplayName("POLICIES 含 /logistics/callback/* 且阈值为 client=120 / ingress=600")
    void callbackPolicyPresent() throws Exception {
        Field policiesField = AuthRateLimitInterceptor.class.getDeclaredField("POLICIES");
        policiesField.setAccessible(true);
        Map<?, ?> policies = (Map<?, ?>) policiesField.get(null);

        Object policy = policies.get("/logistics/callback/*");
        assertNotNull(policy, "POLICIES 缺少 /logistics/callback/* 条目（§4.1 三处同步改动之一）");
        assertEquals(120, invokeInt(policy, "clientLimit"));
        assertEquals(600, invokeInt(policy, "ingressLimit"));
        // 回调恰为 POST-only：未开 throttleGet/throttleDelete，保持渠道推送语义
        assertEquals(false, invoke(policy, "throttleGet"));
        assertEquals(false, invoke(policy, "throttleDelete"));
    }

    @Test
    @DisplayName("WebConfig 限流段注册了 /logistics/callback/** 路径")
    void callbackPathRegisteredInWebConfig() throws Exception {
        WebConfig config = new WebConfig(
                mock(SecurityRouteGuardInterceptor.class),
                new AuthRateLimitInterceptor(),
                new AuthInterceptor(mock(IdentityService.class), mock(AuditService.class)),
                mock(PermInterceptor.class));
        InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);

        Field registrationsField = InterceptorRegistry.class.getDeclaredField("registrations");
        registrationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<InterceptorRegistration> registrations =
                (List<InterceptorRegistration>) registrationsField.get(registry);

        // Spring 6 的 InterceptorRegistration 未暴露 interceptor/includePatterns 公共读取，反射取私有字段
        Field interceptorField = InterceptorRegistration.class.getDeclaredField("interceptor");
        interceptorField.setAccessible(true);
        Field pathPatternsField = InterceptorRegistration.class.getDeclaredField("includePatterns");
        pathPatternsField.setAccessible(true);

        boolean registered = registrations.stream()
                .filter(r -> {
                    try {
                        return interceptorField.get(r) instanceof AuthRateLimitInterceptor;
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                })
                .anyMatch(r -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> patterns = (List<String>) pathPatternsField.get(r);
                        return patterns != null && patterns.contains("/logistics/callback/**");
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                });
        assertTrue(registered,
                "WebConfig 限流段未注册 /logistics/callback/**（只加策略不注册路径等于无限流——本项目踩过）");
    }

    @Test
    @DisplayName("AuthInterceptor 白名单含 /logistics/callback/ 前缀")
    void callbackWhitelistedInAuthInterceptor() throws Exception {
        Method whitelist = AuthInterceptor.class.getDeclaredMethod("isWhiteList", String.class);
        whitelist.setAccessible(true);
        AuthInterceptor interceptor =
                new AuthInterceptor(mock(IdentityService.class), mock(AuditService.class));
        assertEquals(true, whitelist.invoke(interceptor, "/logistics/callback/mock"));
        assertEquals(true, whitelist.invoke(interceptor, "/logistics/callback/mock/dev-callback-token"));
        // 前缀不得外溢到同前缀异路径
        assertEquals(false, whitelist.invoke(interceptor, "/logistics/ship"));
    }

    private static int invokeInt(Object target, String getter) throws Exception {
        return (int) invoke(target, getter);
    }

    private static Object invoke(Object target, String getter) throws Exception {
        Method method = target.getClass().getMethod(getter);
        method.setAccessible(true);
        return method.invoke(target);
    }
}

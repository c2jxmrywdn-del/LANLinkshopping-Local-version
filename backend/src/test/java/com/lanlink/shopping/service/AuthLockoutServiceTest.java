package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthLockoutService 的 Redis 版逻辑测试：用一个内存假 Redis（Mockito 桩）模拟
 * INCR 计数、锁定键存在性与删除，验证阈值锁定、成功重置、IP 跨账号累计等集群语义。
 * 测试即时运行，无需推进时间触发 TTL 过期。
 */
class AuthLockoutServiceTest {

    private final Map<String, Long> counters = new HashMap<>();
    private final Set<String> locks = new HashSet<>();
    private AuthLockoutService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        counters.clear();
        locks.clear();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        // 原子自增：模拟 Lua 的 INCR，返回递增后的计数
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenAnswer(inv -> {
            java.util.List<String> keys = inv.getArgument(1);
            String key = keys.get(0);
            long c = counters.merge(key, 1L, Long::sum);
            return c;
        });

        // 锁定键存在 → 返回正的剩余毫秒；不存在 → -2
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return locks.contains(key) ? 900_000L : -2L;
        });

        // 写锁定键（带 TTL）
        org.mockito.Mockito.doAnswer(inv -> {
            locks.add(inv.getArgument(0));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(redis.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            counters.remove(key);
            return locks.remove(key);
        });

        when(redis.delete(anyCollection())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            long n = 0;
            for (String key : keys) {
                counters.remove(key);
                if (locks.remove(key)) n++;
            }
            return n;
        });

        service = new AuthLockoutService(redis);
    }

    @Test
    void normalAttemptsAllowed() {
        assertDoesNotThrow(() -> service.checkLocked("13800000001", "127.0.0.1"));
        service.recordFailure("13800000001", "127.0.0.1");
        assertDoesNotThrow(() -> service.checkLocked("13800000001", "127.0.0.1"));
    }

    @Test
    void fiveFailuresLocksAccount() {
        String phone = "13800000002";
        String ip = "192.168.1.10";

        for (int i = 0; i < 4; i++) {
            service.recordFailure(phone, ip);
            assertDoesNotThrow(() -> service.checkLocked(phone, ip));
        }

        // 第 5 次失败触发锁定
        service.recordFailure(phone, ip);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkLocked(phone, ip));
        assertTrue(ex.getMessage().contains("账号已临时锁定"));
    }

    @Test
    void successResetsFailureCount() {
        String phone = "13800000003";
        String ip = "192.168.1.11";

        for (int i = 0; i < 4; i++) {
            service.recordFailure(phone, ip);
        }

        // 登录成功清零
        service.recordSuccess(phone, ip);

        // 再次失败 4 次，依然不会锁定（因为之前被重置）
        for (int i = 0; i < 4; i++) {
            service.recordFailure(phone, ip);
            assertDoesNotThrow(() -> service.checkLocked(phone, ip));
        }
    }

    @Test
    void tenFailuresLocksIpAcrossAccounts() {
        String ip = "10.0.0.99";

        for (int i = 1; i <= 9; i++) {
            service.recordFailure("138000000" + (10 + i), ip);
            assertDoesNotThrow(() -> service.checkLocked("13800000099", ip));
        }

        // 第 10 次失败锁定 IP
        service.recordFailure("13800000020", ip);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkLocked("13800000099", ip));
        assertTrue(ex.getMessage().contains("该 IP 登录尝试过于频繁"));
    }
}

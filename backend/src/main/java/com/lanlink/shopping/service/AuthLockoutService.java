package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 账号登录防爆破与临时锁定服务（Redis 集中存储，适配多实例集群部署）：
 * 1. 同一手机号连续输错密码 5 次，锁定 15 分钟；
 * 2. 同一 IP 连续输错 10 次，该 IP 临时封禁 15 分钟；
 * 3. 登录成功自动重置失败计数；
 * <p>
 * 计数与锁定状态全部落在 Redis，跨实例共享同一份数据，杜绝"每个实例各自计数、
 * 攻击者轮询不同实例即可绕过阈值"的集群缺陷。失败计数用 Lua 脚本把 INCR 与设置
 * 窗口 TTL 合为一次原子操作，避免两命令之间进程崩溃导致计数键永不过期而误锁。
 * <p>
 * 降级策略：Redis 不可用时记告警日志并"失败开放"（放行本次登录），
 * 与全站缓存故障不冒泡成接口 500 的取向一致——安全控制短暂失效，好过让所有用户登不进。
 */
@Service
public class AuthLockoutService {

    private static final Logger log = LoggerFactory.getLogger(AuthLockoutService.class);

    private static final int MAX_ACCOUNT_FAILURES = 5;
    private static final int MAX_IP_FAILURES = 10;
    private static final long WINDOW_MS = 10 * 60 * 1000L;        // 10 分钟固定窗口
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000L; // 锁定 15 分钟

    /** 统一键前缀，与缓存命名空间隔离，便于在共享 Redis 中识别与清理。 */
    private static final String KEY_PREFIX = "lanlink::auth::lockout::";

    /**
     * 原子自增并在首次创建时设置窗口 TTL：
     * KEYS[1]=计数键，ARGV[1]=窗口毫秒。返回自增后的计数。
     */
    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c",
            Long.class);

    private final StringRedisTemplate redis;

    public AuthLockoutService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 校验账号及客户端 IP 是否处于锁定状态
     */
    public void checkLocked(String phone, String clientIp) {
        // IP 维度优先：整段 IP 被封禁时不必再查账号
        checkKey(lockKey("ip", clientIp),
                remain -> "该 IP 登录尝试过于频繁已被限制，请在 " + remain + " 分钟后再试",
                "[security-lockout] IP 锁定拦截 ip=" + clientIp);

        checkKey(lockKey("acct", phone),
                remain -> "密码错误次数过多，账号已临时锁定，请在 " + remain + " 分钟后再试",
                "[security-lockout] 账号锁定拦截 phone=" + phone);
    }

    private void checkKey(String key, java.util.function.LongFunction<String> msgFactory, String logTag) {
        if (key == null) return;
        try {
            Long remainMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
            // getExpire：正数=剩余毫秒；-1=无过期；-2=键不存在。仅正数视为锁定中。
            if (remainMs != null && remainMs > 0) {
                long remainMin = Math.max(1, (remainMs + 59999) / 60000);
                log.warn("{} remainMin={}", logTag, remainMin);
                throw new BusinessException(msgFactory.apply(remainMin));
            }
        } catch (BusinessException be) {
            throw be; // 锁定拦截正常抛出
        } catch (RuntimeException ex) {
            // Redis 故障：失败开放，放行本次校验
            log.warn("[security-lockout] Redis 读取锁定状态失败，降级放行: key={}, err={}", key, ex.getMessage());
        }
    }

    /**
     * 记录一次登录失败
     */
    public void recordFailure(String phone, String clientIp) {
        bump(failKey("acct", phone), lockKey("acct", phone), MAX_ACCOUNT_FAILURES,
                "[security-lockout] 账号连续密码错误达到上限，锁定 15 分钟: phone=" + phone);

        if (clientIp != null && !clientIp.isBlank() && !"unknown".equalsIgnoreCase(clientIp)) {
            bump(failKey("ip", clientIp), lockKey("ip", clientIp), MAX_IP_FAILURES,
                    "[security-lockout] IP 连续登录失败达到上限，封禁 15 分钟: ip=" + clientIp);
        }
    }

    private void bump(String counterKey, String lockKey, int maxFailures, String lockLog) {
        if (counterKey == null) return;
        try {
            Long count = redis.execute(INCR_WITH_TTL,
                    Collections.singletonList(counterKey), String.valueOf(WINDOW_MS));
            if (count != null && count >= maxFailures) {
                // 达到阈值：写入带 TTL 的锁定键，并清掉计数键，解锁后从干净状态重新计数
                redis.opsForValue().set(lockKey, "1", LOCK_DURATION_MS, TimeUnit.MILLISECONDS);
                redis.delete(counterKey);
                log.warn(lockLog);
            }
        } catch (RuntimeException ex) {
            log.warn("[security-lockout] Redis 记录失败计数出错，忽略: key={}, err={}", counterKey, ex.getMessage());
        }
    }

    /**
     * 登录成功，重置失败计数与锁定状态
     */
    public void recordSuccess(String phone, String clientIp) {
        try {
            clearPair("acct", phone);
            clearPair("ip", clientIp);
        } catch (RuntimeException ex) {
            log.warn("[security-lockout] Redis 清除失败计数出错: err={}", ex.getMessage());
        }
    }

    private void clearPair(String dim, String id) {
        if (id == null || id.isBlank()) return;
        redis.delete(java.util.Arrays.asList(failKey(dim, id), lockKey(dim, id)));
    }

    private static String failKey(String dim, String id) {
        return (id == null || id.isBlank()) ? null : KEY_PREFIX + "fail::" + dim + "::" + id;
    }

    private static String lockKey(String dim, String id) {
        return (id == null || id.isBlank()) ? null : KEY_PREFIX + "lock::" + dim + "::" + id;
    }
}

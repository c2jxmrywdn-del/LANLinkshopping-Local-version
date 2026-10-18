package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账号登录防爆破与临时锁定服务：
 * 1. 同一手机号连续输错密码 5 次，锁定 15 分钟；
 * 2. 同一 IP 连续输错 10 次，该 IP 临时封禁 15 分钟；
 * 3. 登录成功自动重置失败计数；
 * 4. 内存存储带过期自驱清理，防止内存膨胀。
 */
@Service
public class AuthLockoutService {

    private static final Logger log = LoggerFactory.getLogger(AuthLockoutService.class);

    private static final int MAX_ACCOUNT_FAILURES = 5;
    private static final int MAX_IP_FAILURES = 10;
    private static final long WINDOW_MS = 10 * 60 * 1000L;    // 10 分钟滑动窗口
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000L; // 锁定 15 分钟

    private static class Record {
        int failures;
        long firstFailTime;
        long lockedUntil;
    }

    private final Map<String, Record> accountRecords = new ConcurrentHashMap<>();
    private final Map<String, Record> ipRecords = new ConcurrentHashMap<>();

    /**
     * 校验账号及客户端 IP 是否处于锁定状态
     */
    public void checkLocked(String phone, String clientIp) {
        long now = System.currentTimeMillis();

        if (clientIp != null && !clientIp.isBlank()) {
            Record ipRec = ipRecords.get(clientIp);
            if (ipRec != null && ipRec.lockedUntil > now) {
                long remainMin = Math.max(1, (ipRec.lockedUntil - now + 59999) / 60000);
                log.warn("[security-lockout] IP 锁定拦截 ip={}, remainMin={}", clientIp, remainMin);
                throw new BusinessException("该 IP 登录尝试过于频繁已被限制，请在 " + remainMin + " 分钟后再试");
            }
        }

        if (phone != null && !phone.isBlank()) {
            Record acctRec = accountRecords.get(phone);
            if (acctRec != null && acctRec.lockedUntil > now) {
                long remainMin = Math.max(1, (acctRec.lockedUntil - now + 59999) / 60000);
                log.warn("[security-lockout] 账号锁定拦截 phone={}, remainMin={}", phone, remainMin);
                throw new BusinessException("密码错误次数过多，账号已临时锁定，请在 " + remainMin + " 分钟后再试");
            }
        }
    }

    /**
     * 记录一次登录失败
     */
    public void recordFailure(String phone, String clientIp) {
        long now = System.currentTimeMillis();
        cleanupExpired(now);

        if (phone != null && !phone.isBlank()) {
            accountRecords.compute(phone, (k, rec) -> {
                if (rec == null || now - rec.firstFailTime > WINDOW_MS) {
                    rec = new Record();
                    rec.failures = 1;
                    rec.firstFailTime = now;
                    return rec;
                }
                rec.failures++;
                if (rec.failures >= MAX_ACCOUNT_FAILURES) {
                    rec.lockedUntil = now + LOCK_DURATION_MS;
                    log.warn("[security-lockout] 账号连续密码错误达到上限，锁定 15 分钟: phone={}", phone);
                }
                return rec;
            });
        }

        if (clientIp != null && !clientIp.isBlank() && !"unknown".equalsIgnoreCase(clientIp)) {
            ipRecords.compute(clientIp, (k, rec) -> {
                if (rec == null || now - rec.firstFailTime > WINDOW_MS) {
                    rec = new Record();
                    rec.failures = 1;
                    rec.firstFailTime = now;
                    return rec;
                }
                rec.failures++;
                if (rec.failures >= MAX_IP_FAILURES) {
                    rec.lockedUntil = now + LOCK_DURATION_MS;
                    log.warn("[security-lockout] IP 连续登录失败达到上限，封禁 15 分钟: ip={}", clientIp);
                }
                return rec;
            });
        }
    }

    /**
     * 登录成功，重置失败计数
     */
    public void recordSuccess(String phone, String clientIp) {
        if (phone != null) accountRecords.remove(phone);
        if (clientIp != null) ipRecords.remove(clientIp);
    }

    private void cleanupExpired(long now) {
        if (accountRecords.size() > 5000) {
            accountRecords.entrySet().removeIf(e -> e.getValue().lockedUntil < now && now - e.getValue().firstFailTime > WINDOW_MS);
        }
        if (ipRecords.size() > 5000) {
            ipRecords.entrySet().removeIf(e -> e.getValue().lockedUntil < now && now - e.getValue().firstFailTime > WINDOW_MS);
        }
    }
}

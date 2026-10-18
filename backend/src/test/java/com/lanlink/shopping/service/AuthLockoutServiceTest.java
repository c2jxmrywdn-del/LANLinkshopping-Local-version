package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthLockoutServiceTest {

    private AuthLockoutService service;

    @BeforeEach
    void setUp() {
        service = new AuthLockoutService();
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

        // 5th failure triggers lockout
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

        // 10th failure locks IP
        service.recordFailure("13800000020", ip);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkLocked("13800000099", ip));
        assertTrue(ex.getMessage().contains("该 IP 登录尝试过于频繁"));
    }
}

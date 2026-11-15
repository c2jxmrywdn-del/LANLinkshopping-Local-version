package com.lanlink.shopping.module.finance.service;

import com.lanlink.shopping.module.finance.dto.FundBalance;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资金锁服务单测（P0-A 通道 B 口径唯一实现点）：锁 SQL、展示读不加锁、锁定读四元组公式、covers 边界。
 * 真实 InnoDB 行锁语义由 {@code SettlementPayWithdrawConcurrencyTest}（两线程两连接）覆盖。
 */
class MerchantFundLockServiceTest {

    private JdbcTemplate jdbc;
    private MerchantWithdrawalMapper withdrawalMapper;
    private MerchantFundLockService svc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        withdrawalMapper = mock(MerchantWithdrawalMapper.class);
        svc = new MerchantFundLockService(jdbc, withdrawalMapper);
    }

    @Test
    void lockMerchant_uses_pk_row_lock_sql() {
        svc.lockMerchant(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getValue().contains("FROM t_merchant WHERE mer_id = ? FOR UPDATE"), sql.getValue());
    }

    @Test
    void settled_is_unlocked_read_but_lockedSettled_is_current_read() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("1000.00"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        assertEquals(0, svc.settled(7L).compareTo(new BigDecimal("1000.00")));
        verify(jdbc).queryForObject(sql.capture(), eq(BigDecimal.class), any(Object[].class));
        String unlocked = sql.getValue();
        assertTrue(unlocked.contains("status IN ('SETTLED','REVERSED')"), unlocked);
        assertFalse(unlocked.contains("FOR UPDATE"), "展示读不得加 FOR UPDATE: " + unlocked);

        ArgumentCaptor<String> lockedSql = ArgumentCaptor.forClass(String.class);
        svc.lockedSettled(7L);
        verify(jdbc, org.mockito.Mockito.times(2))
                .queryForObject(lockedSql.capture(), eq(BigDecimal.class), any(Object[].class));
        String locked = lockedSql.getAllValues().get(1);
        assertTrue(locked.contains("FOR UPDATE"), "写路径必须是锁定读: " + locked);
    }

    @Test
    void lockedBalance_is_settled_minus_paid_minus_frozen() {
        when(jdbc.queryForObject(argThat(s -> s != null && s.contains("FOR UPDATE")),
                eq(BigDecimal.class), any(Object[].class))).thenReturn(new BigDecimal("1000.00"));
        when(withdrawalMapper.sumPaidForUpdate(7L)).thenReturn(new BigDecimal("100.00"));
        when(withdrawalMapper.sumFrozenForUpdate(7L)).thenReturn(new BigDecimal("250.00"));

        FundBalance fb = svc.lockedBalance(7L);

        assertEquals(0, fb.settled().compareTo(new BigDecimal("1000.00")));
        assertEquals(0, fb.paid().compareTo(new BigDecimal("100.00")));
        assertEquals(0, fb.frozen().compareTo(new BigDecimal("250.00")));
        assertEquals(0, fb.balance().compareTo(new BigDecimal("650.00")), "balance = settled - paid - frozen");
    }

    @Test
    void lockedBalance_null_aggregates_degrade_to_zero() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class))).thenReturn(null);
        when(withdrawalMapper.sumPaidForUpdate(7L)).thenReturn(null);
        when(withdrawalMapper.sumFrozenForUpdate(7L)).thenReturn(null);

        FundBalance fb = svc.lockedBalance(7L);

        assertEquals(0, fb.balance().compareTo(BigDecimal.ZERO.setScale(2)));
    }

    @Test
    void covers_uses_compareTo_and_treats_non_positive_as_covered() {
        FundBalance fb = new FundBalance(new BigDecimal("800.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("800.00"));
        assertTrue(fb.covers(null), "null 视为无需覆盖");
        assertTrue(fb.covers(BigDecimal.ZERO), "0 视为无需覆盖");
        assertTrue(fb.covers(new BigDecimal("-30.00")), "负额批次无需覆盖（冲减不产生现金流出）");
        assertTrue(fb.covers(new BigDecimal("800")), "金额相等必须放行（compareTo 而非 equals）");
        assertFalse(fb.covers(new BigDecimal("800.01")), "超出余额必须拒绝");
    }
}

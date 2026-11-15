package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.entity.MerchantDeposit;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantDepositMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantDepositServiceTest {

    private MerchantDepositMapper mapper;
    private JdbcTemplate jdbc;
    private MerchantExitProperties props;
    private MerchantDepositService svc;

    @BeforeEach
    void setup() {
        mapper = mock(MerchantDepositMapper.class);
        jdbc = mock(JdbcTemplate.class);
        props = new MerchantExitProperties();
        svc = new MerchantDepositService(mapper, jdbc, props);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of(1L));
    }

    private void balance(BigDecimal v) {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class))).thenReturn(v);
    }

    @Test
    void pay_snapshots_balance_after() {
        balance(new BigDecimal("500.00"));
        MerchantDeposit d = svc.pay(1L, new BigDecimal("2000"), "入驻保证金");
        assertEquals("PAY", d.getType());
        assertEquals(0, d.getAmount().compareTo(new BigDecimal("2000.00")));
        assertEquals(0, d.getBalanceAfter().compareTo(new BigDecimal("2500.00")));
        verify(mapper).insert(d);
    }

    @Test
    void pay_locks_merchant_before_reading_balance() {
        balance(new BigDecimal("500.00"));
        svc.pay(1L, new BigDecimal("2000"), "入驻保证金");
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(anyString(), eq(Long.class), any(Object[].class));
        order.verify(jdbc).queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class));
    }

    @Test
    void refund_reduces_balance() {
        balance(new BigDecimal("2000.00"));
        MerchantDeposit d = svc.refund(1L, new BigDecimal("500"), "闭店退还");
        assertEquals(0, d.getBalanceAfter().compareTo(new BigDecimal("1500.00")));
        verify(mapper).insert(d);
    }

    @Test
    void refund_over_balance_is_400_and_writes_nothing() {
        balance(new BigDecimal("100.00"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.refund(1L, new BigDecimal("200.00"), "超额退"));
        assertEquals(400, e.getCode());
        verify(mapper, never()).insert(any(MerchantDeposit.class));
    }

    @Test
    void non_positive_amount_is_rejected() {
        assertThrows(BusinessException.class, () -> svc.pay(1L, BigDecimal.ZERO, "零"));
        assertThrows(BusinessException.class, () -> svc.deduct(1L, new BigDecimal("-1"), "负"));
        // 分位以下正数四舍五入后为 0.00，必须在入账前拒绝
        assertThrows(BusinessException.class, () -> svc.pay(1L, new BigDecimal("0.004"), "厘"));
        verify(mapper, never()).insert(any(MerchantDeposit.class));
    }

    @Test
    void unknown_merchant_is_404() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.pay(9L, new BigDecimal("1.00"), "x"));
        assertEquals(404, e.getCode());
        verify(mapper, never()).insert(any(MerchantDeposit.class));
    }

    @Test
    void ensure_initial_deposit_locks_before_counting() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        balance(BigDecimal.ZERO);
        svc.ensureInitialDeposit(1L);
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForList(anyString(), eq(Long.class), any(Object[].class));
        order.verify(jdbc).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void ensure_initial_deposit_unknown_merchant_is_404() {
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        BusinessException e = assertThrows(BusinessException.class, () -> svc.ensureInitialDeposit(9L));
        assertEquals(404, e.getCode());
        verify(mapper, never()).insert(any(MerchantDeposit.class));
    }

    @Test
    void ensure_initial_deposit_is_idempotent() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        assertFalse(svc.ensureInitialDeposit(1L));
        verify(mapper, never()).insert(any(MerchantDeposit.class));
    }

    @Test
    void ensure_initial_deposit_pays_default_amount_when_absent() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        balance(BigDecimal.ZERO);
        ArgumentCaptor<MerchantDeposit> cap = ArgumentCaptor.forClass(MerchantDeposit.class);
        assertTrue(svc.ensureInitialDeposit(1L));
        verify(mapper).insert(cap.capture());
        assertEquals("PAY", cap.getValue().getType());
        assertEquals(0, cap.getValue().getAmount().compareTo(new BigDecimal("2000.00")));
    }
}

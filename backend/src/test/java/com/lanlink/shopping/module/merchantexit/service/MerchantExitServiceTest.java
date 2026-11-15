package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateBlocker;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateReport;
import com.lanlink.shopping.module.merchantexit.entity.MerchantExitLog;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitLogMapper;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitMapper;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantExitServiceTest {

    private MerchantService merchantService;
    private MerchantExitMapper exitMapper;
    private MerchantExitLogMapper logMapper;
    private MerchantExitGateService gate;
    private MerchantDepositService deposit;
    private MerchantExitProperties props;
    private AuditService audit;
    private MessageService message;
    private MerchantExitService svc;

    @BeforeEach
    void setup() {
        merchantService = mock(MerchantService.class);
        exitMapper = mock(MerchantExitMapper.class);
        logMapper = mock(MerchantExitLogMapper.class);
        gate = mock(MerchantExitGateService.class);
        deposit = mock(MerchantDepositService.class);
        audit = mock(AuditService.class);
        message = mock(MessageService.class);
        props = new MerchantExitProperties();
        svc = new MerchantExitService(merchantService, exitMapper, logMapper, gate, deposit,
                props, audit, message);
    }

    private Merchant merchant(Long merId, int reviewStatus, Long userId, String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(userId);
        m.setReviewStatus(reviewStatus);
        m.setExitStatus(exitStatus);
        return m;
    }

    @Test
    void request_exit_writes_state_and_log() {
        when(merchantService.getByUser(5L)).thenReturn(merchant(1L, 1, 5L, null));
        when(exitMapper.requestExit(1L, "经营调整")).thenReturn(1);
        svc.requestExit(5L, "经营调整", null);
        verify(exitMapper).requestExit(1L, "经营调整");
        verify(logMapper).insert(any(MerchantExitLog.class));
        verify(message).send(eq(5L), eq("merchant"), anyString(), anyString(), isNull());
    }

    @Test
    void request_exit_mapper_clears_stale_reject_reason() throws Exception {
        // 重新申请时必须清掉上一次的驳回原因，否则详情页在 EXIT_REQUESTED 期间仍显示旧驳回文案
        java.lang.reflect.Method m = com.lanlink.shopping.module.merchantexit.mapper.MerchantExitMapper.class
                .getMethod("requestExit", Long.class, String.class);
        String sql = String.join(" ", m.getAnnotation(org.apache.ibatis.annotations.Update.class).value())
                .replaceAll("\\s+", " ");
        org.junit.jupiter.api.Assertions.assertTrue(
                sql.contains("exit_reject_reason = NULL"),
                "requestExit 必须清空 exit_reject_reason: " + sql);
    }

    @Test
    void request_exit_rejects_double_apply() {
        when(merchantService.getByUser(5L)).thenReturn(merchant(1L, 1, 5L, null));
        when(exitMapper.requestExit(1L, "x")).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.requestExit(5L, "x", null));
        assertEquals(400, e.getCode());
        verify(logMapper, never()).insert(any(MerchantExitLog.class));
    }

    @Test
    void request_exit_requires_approved_merchant_and_reason() {
        when(merchantService.getByUser(5L)).thenReturn(merchant(1L, 2, 5L, null));
        assertThrows(BusinessException.class, () -> svc.requestExit(5L, "理由", null));
        when(merchantService.getByUser(6L)).thenReturn(merchant(2L, 1, 6L, null));
        assertThrows(BusinessException.class, () -> svc.requestExit(6L, "  ", null));
        verify(exitMapper, never()).requestExit(anyLong(), anyString());
    }

    @Test
    void approve_blocked_by_gate_writes_nothing() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "EXIT_REQUESTED"));
        when(gate.check(1L)).thenReturn(new GateReport(false,
                List.of(new GateBlocker("OPEN_ORDERS", "存在未履约订单", 1, "先履约"))));
        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(9L, 1L, null));
        assertEquals(400, e.getCode());
        verify(exitMapper, never()).approve(anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(logMapper, never()).insert(any(MerchantExitLog.class));
    }

    @Test
    void approve_moves_to_public_notice_with_configured_days() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "EXIT_REQUESTED"));
        when(gate.check(1L)).thenReturn(new GateReport(true, List.of()));
        when(exitMapper.approve(1L, 30)).thenReturn(1);
        svc.approve(9L, 1L, null);
        verify(exitMapper).approve(1L, 30);
        verify(logMapper).insert(any(MerchantExitLog.class));
        verify(message).send(eq(5L), eq("merchant"), anyString(), anyString(), isNull());
    }

    @Test
    void approve_state_race_is_400() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "EXIT_REQUESTED"));
        when(gate.check(1L)).thenReturn(new GateReport(true, List.of()));
        when(exitMapper.approve(1L, 30)).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(9L, 1L, null));
        assertEquals(400, e.getCode());
    }

    @Test
    void reject_requires_reason_and_restores_active() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "EXIT_REQUESTED"));
        when(exitMapper.reject(1L, "资料不全")).thenReturn(1);
        svc.reject(9L, 1L, "资料不全", null);
        verify(exitMapper).reject(1L, "资料不全");
        assertThrows(BusinessException.class, () -> svc.reject(9L, 1L, " ", null));
    }

    @Test
    void withdraw_only_from_public_notice() {
        when(merchantService.getByUser(5L)).thenReturn(merchant(1L, 1, 5L, "PUBLIC_NOTICE"));
        when(exitMapper.withdraw(1L)).thenReturn(1);
        svc.withdraw(5L, null);
        verify(exitMapper).withdraw(1L);
    }

    @Test
    void withdraw_logs_active_instead_of_null_status() {
        when(merchantService.getByUser(5L)).thenReturn(merchant(1L, 1, 5L, "PUBLIC_NOTICE"));
        when(exitMapper.withdraw(1L)).thenReturn(1);
        svc.withdraw(5L, null);
        ArgumentCaptor<MerchantExitLog> cap = ArgumentCaptor.forClass(MerchantExitLog.class);
        verify(logMapper).insert(cap.capture());
        // to_status 列 NOT NULL：回退到正常经营必须落 ACTIVE，不能是 null（真机曾报 500）
        assertEquals("ACTIVE", cap.getValue().getToStatus());
        assertEquals("PUBLIC_NOTICE", cap.getValue().getFromStatus());
    }

    @Test
    void reject_logs_active_instead_of_null_status() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "EXIT_REQUESTED"));
        when(exitMapper.reject(1L, "资料不全")).thenReturn(1);
        svc.reject(9L, 1L, "资料不全", null);
        ArgumentCaptor<MerchantExitLog> cap = ArgumentCaptor.forClass(MerchantExitLog.class);
        verify(logMapper).insert(cap.capture());
        assertEquals("ACTIVE", cap.getValue().getToStatus());
    }

    @Test
    void close_blocked_by_gate_writes_nothing() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "FROZEN"));
        when(gate.check(1L)).thenReturn(new GateReport(false,
                List.of(new GateBlocker("PRODUCTS_ON_SALE", "仍有商品在售", 1, "先下架"))));
        assertThrows(BusinessException.class, () -> svc.close(9L, 1L, null, null, null));
        verify(exitMapper, never()).close(anyLong());
    }

    @Test
    void close_deducts_then_refunds_remaining_and_closes() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "FROZEN"));
        when(gate.check(1L)).thenReturn(new GateReport(true, List.of()));
        when(deposit.balanceOf(1L)).thenReturn(new BigDecimal("2000.00"), new BigDecimal("1900.00"));
        when(exitMapper.close(1L)).thenReturn(1);
        svc.close(9L, 1L, new BigDecimal("100.00"), "欠费抵扣", null);
        verify(deposit).deduct(1L, new BigDecimal("100.00"), "欠费抵扣");
        verify(deposit).refund(1L, new BigDecimal("1900.00"), "闭店保证金退还（账面登记）");
        verify(exitMapper).close(1L);
        verify(logMapper).insert(any(MerchantExitLog.class));
    }

    @Test
    void close_rejects_deduction_over_balance() {
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "FROZEN"));
        when(gate.check(1L)).thenReturn(new GateReport(true, List.of()));
        when(deposit.balanceOf(1L)).thenReturn(new BigDecimal("100.00"));
        assertThrows(BusinessException.class,
                () -> svc.close(9L, 1L, new BigDecimal("200.00"), "超额抵扣", null));
        verify(deposit, never()).deduct(anyLong(), any(), anyString());
        verify(exitMapper, never()).close(anyLong());
    }

    @Test
    void freeze_due_is_idempotent() {
        when(exitMapper.freezeDue(1L)).thenReturn(1, 0);
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 1, 5L, "FROZEN"));
        assertTrue(svc.freezeDue(1L));
        assertFalse(svc.freezeDue(1L));
        ArgumentCaptor<MerchantExitLog> cap = ArgumentCaptor.forClass(MerchantExitLog.class);
        verify(logMapper).insert(cap.capture());
        assertEquals("SYSTEM", cap.getValue().getActorRole());
        assertEquals("FROZEN", cap.getValue().getToStatus());
    }
}

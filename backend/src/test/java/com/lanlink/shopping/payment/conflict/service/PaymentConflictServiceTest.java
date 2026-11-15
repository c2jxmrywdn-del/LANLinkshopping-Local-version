package com.lanlink.shopping.payment.conflict.service;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.mapper.PaymentConflictMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收款冲突挂账服务（P0-B 骨架）单测：
 * 渠道流水归一化、重复挂账幂等、状态机 CAS（抢单/收尾只认合法前置态）、退避与转人工阈值。
 *
 * <p>纯 Mockito（仓库无 H2/Testcontainers 基建）：SQL 条件更新的真实互斥由真机验证，
 * 这里的 CAS 语义用 "mapper affected=0" 模拟。
 */
class PaymentConflictServiceTest {

    private PaymentConflictMapper mapper;
    private SimpleMeterRegistry registry;
    private PaymentConflictService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PaymentConflictMapper.class);
        registry = new SimpleMeterRegistry();
        service = new PaymentConflictService(mapper, registry);
    }

    // ===== 构造工具 =====

    private static Order order(String orderNo) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setUserId(9L);
        o.setTotalAmount(new BigDecimal("88.80"));
        o.setPayStatus(0);
        o.setOrderStatus(3);
        return o;
    }

    private static PaymentConflict conflict(Long id, String status, int retryCount) {
        PaymentConflict c = new PaymentConflict();
        c.setConflictId(id);
        c.setOrderNo("ORD" + id);
        c.setChannel("wechat");
        c.setTransactionId("TXN" + id);
        c.setAmount(new BigDecimal("88.80"));
        c.setStatus(status);
        c.setRetryCount(retryCount);
        return c;
    }

    private double counter(String metric, String outcome) {
        return registry.get(metric).tag("outcome", outcome).counter().count();
    }

    // ===== 1. 挂账落库：归一化 + 快照 =====

    @Test
    void recordNormalizesMissingTxnAndSnapshotsOrder() {
        when(mapper.insertIgnore(any())).thenReturn(1);

        boolean created = service.record(order("ORD1"), "wechat", null,
                new BigDecimal("88.80"), PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, "wechat_notify");

        assertTrue(created);
        ArgumentCaptor<PaymentConflict> captor = ArgumentCaptor.forClass(PaymentConflict.class);
        verify(mapper).insertIgnore(captor.capture());
        PaymentConflict saved = captor.getValue();
        assertEquals("ORD1", saved.getOrderNo());
        assertEquals("NA:ORD1", saved.getTransactionId(), "缺失 transaction_id 必须归一为 NA:<orderNo>，否则唯一键失效");
        assertEquals("wechat", saved.getChannel());
        assertEquals(new BigDecimal("88.80"), saved.getAmount());
        assertEquals(9L, saved.getPayerUserId());
        assertEquals(3, saved.getOrderStatusAtDetect());
        assertEquals(0, saved.getPayStatusAtDetect());
        assertEquals(PaymentConflictService.STATUS_OPEN, saved.getStatus());
        assertEquals(PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, saved.getConflictType());
        assertNotNull(saved.getDetectedAt());
        assertEquals(1.0, counter(PaymentConflictService.METRIC_DETECTED, "detected"));
    }

    @Test
    void normalizeHelpersTrimAndFallback() {
        assertEquals("NA:ORD2", PaymentConflictService.normalizeTransactionId("ORD2", "   "));
        assertEquals("NA:ORD2", PaymentConflictService.normalizeTransactionId("ORD2", null));
        assertEquals("TXN-1", PaymentConflictService.normalizeTransactionId("ORD2", " TXN-1 "));
        assertEquals("unknown", PaymentConflictService.normalizeChannel(null));
        assertEquals("wechat", PaymentConflictService.normalizeChannel(" WeChat "));
    }

    @Test
    void recordDuplicateTxnIsIdempotentAndNotCountedAsNew() {
        // 唯一键命中：insertIgnore 返回 0（同一渠道流水重复回调）
        when(mapper.insertIgnore(any())).thenReturn(0);

        assertFalse(service.record(order("ORD1"), "wechat", "TXN1", new BigDecimal("88.80"),
                PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, "wechat_retry"));
        assertFalse(service.record(order("ORD1"), "wechat", "TXN1", new BigDecimal("88.80"),
                PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, "wechat_retry"));

        verify(mapper, times(2)).insertIgnore(any());
        // 两次重复上报各自计一次 duplicate（新增挂账计数保持 0）
        assertEquals(2.0, counter(PaymentConflictService.METRIC_DETECTED, "duplicate"));
    }

    @Test
    void recordRejectsMissingOrder() {
        assertThrows(IllegalArgumentException.class, () -> service.record(null, "wechat", "TXN1",
                BigDecimal.ONE, PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, "test"));
        assertThrows(IllegalArgumentException.class, () -> service.record(order("   "), "wechat", "TXN1",
                BigDecimal.ONE, PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE, "test"));
        verify(mapper, never()).insertIgnore(any());
    }

    // ===== 2. 状态机：抢单 / 收尾 CAS =====

    @Test
    void claimRejectsNonPendingStatus() {
        when(mapper.selectById(1L)).thenReturn(conflict(1L, PaymentConflictService.STATUS_REFUNDED, 0));

        assertNull(service.claimForCompensation(1L, LocalDateTime.now()));
        verify(mapper, never()).claimForRefund(any(), anyString());
    }

    @Test
    void claimOnlyOneWinnerAndPersistsStableOutRefundNo() {
        PaymentConflict open = conflict(1L, PaymentConflictService.STATUS_OPEN, 0);
        PaymentConflict claimed = conflict(1L, PaymentConflictService.STATUS_AUTO_REFUNDING, 0);
        claimed.setOutRefundNo("CR20261111120000ABCDEF12");
        when(mapper.selectById(1L)).thenReturn(open, claimed, open);
        when(mapper.claimForRefund(eq(1L), anyString())).thenReturn(1, 0);

        PaymentConflict first = service.claimForCompensation(1L, LocalDateTime.now());
        assertNotNull(first);
        assertEquals(PaymentConflictService.STATUS_AUTO_REFUNDING, first.getStatus());
        assertEquals("CR20261111120000ABCDEF12", first.getOutRefundNo());

        // 第二次抢单：条件更新 affected=0（被并发抢走/已推进），不得重复退款
        assertNull(service.claimForCompensation(1L, LocalDateTime.now()));
        verify(mapper, times(2)).claimForRefund(eq(1L), anyString());
    }

    @Test
    void finishRefundedOnlyAppliesFromAutoRefunding() {
        when(mapper.markRefunded(any(), anyString())).thenReturn(0, 1);

        assertFalse(service.finishRefunded(3L, "RF1"), "非法前置态（未抢单）不得推进为 REFUNDED");
        assertTrue(service.finishRefunded(3L, "RF1"));

        assertEquals(1.0, counter(PaymentConflictService.METRIC_COMPENSATE, "stale"));
        assertEquals(1.0, counter(PaymentConflictService.METRIC_COMPENSATE, "refunded"));
    }

    // ===== 3. 失败退避与转人工 =====

    @Test
    void finishFailedSchedulesFirstRetryAfterOneMinute() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
        PaymentConflict row = conflict(2L, PaymentConflictService.STATUS_AUTO_REFUNDING, 0);
        when(mapper.selectById(2L)).thenReturn(row);
        when(mapper.bumpRetry(eq(2L), eq(PaymentConflictService.STATUS_FAILED), anyString(), any()))
                .thenReturn(1);

        assertFalse(service.finishFailed(2L, "channel timeout", now));

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).bumpRetry(eq(2L), eq(PaymentConflictService.STATUS_FAILED), eq("channel timeout"),
                next.capture());
        assertEquals(now.plusSeconds(60), next.getValue());
        assertEquals(1.0, counter(PaymentConflictService.METRIC_COMPENSATE, "failed"));
    }

    @Test
    void eightFailuresMoveToManual() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 12, 0);
        // 已失败 7 次，第 8 次失败达到 maxRetry → MANUAL
        PaymentConflict row = conflict(2L, PaymentConflictService.STATUS_AUTO_REFUNDING, 7);
        when(mapper.selectById(2L)).thenReturn(row);
        when(mapper.bumpRetry(eq(2L), eq(PaymentConflictService.STATUS_MANUAL), anyString(), isNull()))
                .thenReturn(1);

        assertTrue(service.finishFailed(2L, "insufficient balance", now));

        verify(mapper).bumpRetry(eq(2L), eq(PaymentConflictService.STATUS_MANUAL),
                eq("insufficient balance"), isNull());
        assertEquals(1.0, counter(PaymentConflictService.METRIC_COMPENSATE, "to_manual"));
    }

    @Test
    void finishFailedIgnoresStaleClaim() {
        LocalDateTime now = LocalDateTime.now();
        when(mapper.selectById(2L)).thenReturn(conflict(2L, PaymentConflictService.STATUS_AUTO_REFUNDING, 0));
        when(mapper.bumpRetry(any(), anyString(), anyString(), any())).thenReturn(0);

        assertFalse(service.finishFailed(2L, "lost race", now));
        assertEquals(1.0, counter(PaymentConflictService.METRIC_COMPENSATE, "stale"));
    }

    @Test
    void backoffGrowsFromOneMinuteToTwentyFourHours() {
        long[] expected = {60L, 300L, 900L, 3600L, 21600L, 86400L, 86400L, 86400L};
        for (int retry = 1; retry <= expected.length; retry++) {
            assertEquals(expected[retry - 1], PaymentConflictService.backoffSeconds(retry),
                    "retry=" + retry);
        }
        assertEquals(60L, PaymentConflictService.backoffSeconds(0));
    }

    // ===== 4. 退款网关缺失与人工处置 =====

    @Test
    void attemptRefundWithoutGatewayFailsGracefully() {
        PaymentConflictService.RefundAttempt attempt =
                service.attemptRefund(conflict(1L, PaymentConflictService.STATUS_AUTO_REFUNDING, 0));

        assertFalse(attempt.ok());
        assertTrue(attempt.message().contains("not wired"));
        assertFalse(service.canAutoRefund());
    }

    @Test
    void manualStateMachineRequiresClaimAndCountsOutcome() {
        when(mapper.claimManual(1L, 77L)).thenReturn(0, 1);
        assertFalse(service.claimManual(1L, 77L), "非 OPEN/FAILED 不得被人工抢单");
        assertTrue(service.claimManual(1L, 77L));

        when(mapper.markManualRefunded(eq(1L), eq("WXRFD1"), anyString())).thenReturn(0, 1);
        assertFalse(service.markManualRefunded(1L, "WXRFD1", "运营线下已退"));
        assertTrue(service.markManualRefunded(1L, "WXRFD1", "运营线下已退"));

        when(mapper.markManualClosed(1L, "渠道确认未扣款")).thenReturn(1);
        assertTrue(service.markManualClosed(1L, "渠道确认未扣款"));

        assertEquals(1.0, counter(PaymentConflictService.METRIC_MANUAL, "claimed"));
        assertEquals(1.0, counter(PaymentConflictService.METRIC_MANUAL, "refunded"));
        assertEquals(1.0, counter(PaymentConflictService.METRIC_MANUAL, "closed"));
    }
}

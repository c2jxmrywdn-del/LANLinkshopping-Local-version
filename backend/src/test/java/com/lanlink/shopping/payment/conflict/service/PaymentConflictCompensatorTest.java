package com.lanlink.shopping.payment.conflict.service;

import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.mapper.PaymentConflictMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收款冲突补偿 job（P0-B 骨架）单测：
 * 批量上限/扫描窗口、逐条异常隔离、失败退避（1m→24h）、8 次失败转 MANUAL、僵死抢单回收。
 *
 * <p>与 {@link PaymentConflictService} 用真实实例 + mock Mapper 组装，
 * 覆盖"抢单→调渠道→收尾"三段编排；调度线程本身不启动（直接调 {@code drain(now)}）。
 */
class PaymentConflictCompensatorTest {

    private PaymentConflictMapper mapper;
    private SimpleMeterRegistry registry;
    private PaymentConflictService conflictService;
    private PaymentConflictCompensator compensator;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(PaymentConflictMapper.class);
        registry = new SimpleMeterRegistry();
        conflictService = new PaymentConflictService(mapper, registry);
        compensator = new PaymentConflictCompensator(mapper, conflictService, registry);
        set("enabled", true);
        set("batchSize", 50);
        set("scanWindowMinutes", 1440L);
        set("staleClaimMinutes", 10);
        conflictService.setRefundGateway(null);
    }

    // ===== 工具 =====

    private void set(String field, Object value) throws Exception {
        Field f = PaymentConflictCompensator.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(compensator, value);
    }

    private void setGateway(PaymentConflictService.ConflictRefundGateway gateway) {
        conflictService.setRefundGateway(gateway);
    }

    private static PaymentConflict conflict(long id) {
        PaymentConflict c = new PaymentConflict();
        c.setConflictId(id);
        c.setOrderNo("ORD" + id);
        c.setChannel("wechat");
        c.setTransactionId("TXN" + id);
        c.setAmount(new BigDecimal("88.80"));
        c.setStatus(PaymentConflictService.STATUS_OPEN);
        c.setRetryCount(0);
        return c;
    }

    /** 抢单后的快照（AUTO_REFUNDING + 已落库 out_refund_no） */
    private static PaymentConflict claimed(long id) {
        PaymentConflict c = conflict(id);
        c.setStatus(PaymentConflictService.STATUS_AUTO_REFUNDING);
        c.setOutRefundNo("CR-" + id);
        return c;
    }

    private double counter(String outcome) {
        return registry.get(PaymentConflictService.METRIC_COMPENSATE)
                .tag("outcome", outcome).counter().count();
    }

    // ===== 1. 正常批量补偿 =====

    @Test
    void refundsEveryCandidateWithinBatchAndCountsThem() {
        PaymentConflict a = conflict(1L);
        PaymentConflict b = conflict(2L);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(NOW, NOW.minusMinutes(1440), 50)).thenReturn(List.of(a, b));
        when(mapper.selectById(1L)).thenReturn(a, claimed(1L));
        when(mapper.selectById(2L)).thenReturn(b, claimed(2L));
        when(mapper.claimForRefund(eq(1L), anyString())).thenReturn(1);
        when(mapper.claimForRefund(eq(2L), anyString())).thenReturn(1);
        when(mapper.markRefunded(eq(1L), eq("RF-1"))).thenReturn(1);
        when(mapper.markRefunded(eq(2L), eq("RF-2"))).thenReturn(1);
        setGateway(c -> PaymentConflictService.RefundAttempt.success("RF-" + c.getConflictId()));

        assertEquals(2, compensator.drain(NOW));
        assertEquals(2.0, counter("refunded"));
        verify(mapper).selectOpenBatch(NOW, NOW.minusMinutes(1440), 50);
    }

    // ===== 2. 失败退避（1 分钟）与批次隔离 =====

    @Test
    void oneFailureDoesNotStopBatchAndSchedulesBackoff() {
        PaymentConflict a = conflict(1L);
        PaymentConflict b = conflict(2L);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(any(), any(), anyInt())).thenReturn(List.of(a, b));
        // id=1：抢单读两次 + finishFailed 再读一次（retryCount 仍为 0）
        when(mapper.selectById(1L)).thenReturn(a, claimed(1L), a);
        when(mapper.selectById(2L)).thenReturn(b, claimed(2L));
        when(mapper.claimForRefund(eq(1L), anyString())).thenReturn(1);
        when(mapper.claimForRefund(eq(2L), anyString())).thenReturn(1);
        when(mapper.bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_FAILED), anyString(), any()))
                .thenReturn(1);
        when(mapper.markRefunded(eq(2L), eq("RF-2"))).thenReturn(1);
        setGateway(c -> c.getConflictId() == 1L
                ? PaymentConflictService.RefundAttempt.failure("balance insufficient")
                : PaymentConflictService.RefundAttempt.success("RF-" + c.getConflictId()));

        assertEquals(1, compensator.drain(NOW), "A 失败不应拖垮 B 的退款");
        assertEquals(1.0, counter("failed"));

        ArgumentCaptor<LocalDateTime> nextRetry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_FAILED),
                contains("balance"), nextRetry.capture());
        assertEquals(NOW.plusSeconds(60), nextRetry.getValue(), "第 1 次失败应在 1 分钟后重试");
    }

    // ===== 3. 单条异常隔离 =====

    @Test
    void unexpectedExceptionOnOneItemDoesNotStopBatch() {
        PaymentConflict b = conflict(2L);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(any(), any(), anyInt())).thenReturn(List.of(conflict(1L), b));
        when(mapper.selectById(1L)).thenThrow(new RuntimeException("db down"));
        when(mapper.selectById(2L)).thenReturn(b, claimed(2L));
        when(mapper.claimForRefund(eq(2L), anyString())).thenReturn(1);
        when(mapper.markRefunded(eq(2L), eq("RF-2"))).thenReturn(1);
        setGateway(c -> PaymentConflictService.RefundAttempt.success("RF-" + c.getConflictId()));

        assertEquals(1, compensator.drain(NOW));
        assertEquals(1.0, counter("exception"));
        assertEquals(1.0, counter("refunded"));
    }

    // ===== 4. 8 次失败转 MANUAL =====

    @Test
    void eighthFailureMovesConflictToManual() {
        PaymentConflict row = conflict(1L);
        row.setRetryCount(7);
        PaymentConflict claimedRow = claimed(1L);
        claimedRow.setRetryCount(7);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(any(), any(), anyInt())).thenReturn(List.of(row));
        when(mapper.selectById(1L)).thenReturn(row, claimedRow, row);
        when(mapper.claimForRefund(eq(1L), anyString())).thenReturn(1);
        when(mapper.bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_MANUAL), anyString(), isNull()))
                .thenReturn(1);
        setGateway(c -> PaymentConflictService.RefundAttempt.failure("channel unavailable"));

        assertEquals(0, compensator.drain(NOW), "转人工不应计入自动退款成功数");
        assertEquals(1.0, counter("to_manual"));
        verify(mapper).bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_MANUAL),
                contains("channel unavailable"), isNull());
    }

    // ===== 5. 退避上限 24h =====

    @Test
    void retryBackoffIsCappedAtTwentyFourHours() {
        PaymentConflict row = conflict(1L);
        row.setRetryCount(5); // 第 6 次失败 → 24h
        PaymentConflict claimedRow = claimed(1L);
        claimedRow.setRetryCount(5);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(any(), any(), anyInt())).thenReturn(List.of(row));
        when(mapper.selectById(1L)).thenReturn(row, claimedRow, row);
        when(mapper.claimForRefund(eq(1L), anyString())).thenReturn(1);
        when(mapper.bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_FAILED), anyString(), any()))
                .thenReturn(1);
        setGateway(c -> PaymentConflictService.RefundAttempt.failure("network"));

        assertEquals(0, compensator.drain(NOW));

        ArgumentCaptor<LocalDateTime> nextRetry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).bumpRetry(eq(1L), eq(PaymentConflictService.STATUS_FAILED), anyString(),
                nextRetry.capture());
        assertEquals(NOW.plusSeconds(86400), nextRetry.getValue());
    }

    // ===== 6. 配置与开关 =====

    @Test
    void batchLimitAndScanWindowAreApplied() throws Exception {
        set("batchSize", 2);
        set("scanWindowMinutes", 60L);
        when(mapper.recoverStaleClaims(10)).thenReturn(0);
        when(mapper.selectOpenBatch(NOW, NOW.minusMinutes(60), 2)).thenReturn(List.of());
        setGateway(c -> PaymentConflictService.RefundAttempt.failure("unused"));

        assertEquals(0, compensator.drain(NOW));
        verify(mapper).selectOpenBatch(NOW, NOW.minusMinutes(60), 2);
    }

    @Test
    void disabledCompensatorTouchesNothing() throws Exception {
        set("enabled", false);

        assertEquals(0, compensator.drain(NOW));
        verifyNoInteractions(mapper);
    }

    @Test
    void skipsWholeRoundWhenRefundGatewayNotWired() throws Exception {
        PaymentConflictService bareService = new PaymentConflictService(mapper, registry);
        PaymentConflictCompensator bareCompensator =
                new PaymentConflictCompensator(mapper, bareService, registry);
        Field enabled = PaymentConflictCompensator.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.set(bareCompensator, true);

        assertEquals(0, bareCompensator.drain(NOW));
        verify(mapper, never()).recoverStaleClaims(anyInt());
        verify(mapper, never()).selectOpenBatch(any(), any(), anyInt());
    }

    @Test
    void staleAutoRefundingClaimsAreRecoveredAndCounted() {
        when(mapper.recoverStaleClaims(10)).thenReturn(2);
        when(mapper.selectOpenBatch(any(), any(), anyInt())).thenReturn(List.of());
        setGateway(c -> PaymentConflictService.RefundAttempt.failure("unused"));

        assertEquals(0, compensator.drain(NOW));
        assertEquals(2.0, counter("stale_recovered"));
    }
}

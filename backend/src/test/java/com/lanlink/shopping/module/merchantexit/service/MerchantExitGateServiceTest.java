package com.lanlink.shopping.module.merchantexit.service;

import com.lanlink.shopping.mapper.MerchantCenterMapper;
import com.lanlink.shopping.module.merchantexit.config.MerchantExitProperties;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateBlocker;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateReport;
import com.lanlink.shopping.module.merchantexit.mapper.MerchantExitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantExitGateServiceTest {

    private MerchantCenterMapper center;
    private MerchantExitMapper exitMapper;
    private MerchantDepositService deposit;
    private MerchantExitProperties props;
    private MerchantExitGateService svc;

    @BeforeEach
    void setup() {
        center = mock(MerchantCenterMapper.class);
        exitMapper = mock(MerchantExitMapper.class);
        deposit = mock(MerchantDepositService.class);
        props = new MerchantExitProperties();
        svc = new MerchantExitGateService(center, exitMapper, deposit, props);
        when(deposit.balanceOf(1L)).thenReturn(BigDecimal.ZERO);
    }

    private GateReport report(long pending, long shipping, long refunds, long batches,
                              long available, long onSale) {
        when(center.countPendingShip(1L)).thenReturn(pending);
        when(center.countShipping(1L)).thenReturn(shipping);
        when(center.countRefundPending(1L)).thenReturn(refunds);
        when(exitMapper.countOpenBatches(1L)).thenReturn(batches);
        when(exitMapper.countAvailableEarnings(1L)).thenReturn(available);
        when(exitMapper.countOnSale(1L)).thenReturn(onSale);
        return svc.check(1L);
    }

    private List<String> codes(GateReport r) {
        return r.blockers().stream().map(GateBlocker::code).toList();
    }

    @Test
    void ready_when_all_clear() {
        GateReport r = report(0, 0, 0, 0, 0, 0);
        assertTrue(r.ready());
        assertTrue(r.blockers().isEmpty());
    }

    @Test
    void blocks_open_orders() {
        GateReport r = report(2, 1, 0, 0, 0, 0);
        assertFalse(r.ready());
        assertEquals(List.of("OPEN_ORDERS"), codes(r));
        assertEquals(3, r.blockers().get(0).count());
    }

    @Test
    void blocks_pending_refund() {
        GateReport r = report(0, 0, 1, 0, 0, 0);
        assertEquals(List.of("REFUND_PENDING"), codes(r));
    }

    @Test
    void blocks_open_settlement_when_batch_or_available_earning() {
        assertEquals(List.of("SETTLEMENT_OPEN"), codes(report(0, 0, 0, 1, 0, 0)));
        assertEquals(List.of("SETTLEMENT_OPEN"), codes(report(0, 0, 0, 0, 2, 0)));
    }

    @Test
    void blocks_products_still_on_sale() {
        assertEquals(List.of("PRODUCTS_ON_SALE"), codes(report(0, 0, 0, 0, 0, 5)));
    }

    @Test
    void blocks_negative_deposit() {
        when(deposit.balanceOf(1L)).thenReturn(new BigDecimal("-1.00"));
        GateReport r = report(0, 0, 0, 0, 0, 0);
        assertEquals(List.of("DEPOSIT_ARREARS"), codes(r));
    }

    @Test
    void time_gate_passes_when_no_completed_order() {
        props.setMinCompleteDays(3);
        when(exitMapper.lastFinishTime(1L)).thenReturn(null);
        assertTrue(report(0, 0, 0, 0, 0, 0).ready());
    }

    @Test
    void time_gate_blocks_recent_completion() {
        props.setMinCompleteDays(7);
        when(exitMapper.lastFinishTime(1L)).thenReturn(LocalDateTime.now().minusDays(2));
        assertEquals(List.of("TIME_GATE"), codes(report(0, 0, 0, 0, 0, 0)));
    }

    @Test
    void time_gate_passes_after_window() {
        props.setMinCompleteDays(7);
        when(exitMapper.lastFinishTime(1L)).thenReturn(LocalDateTime.now().minusDays(8));
        assertTrue(report(0, 0, 0, 0, 0, 0).ready());
    }

    @Test
    void collects_multiple_blockers_in_fixed_order() {
        when(exitMapper.lastFinishTime(1L)).thenReturn(LocalDateTime.now().minusDays(1));
        props.setMinCompleteDays(3);
        when(deposit.balanceOf(1L)).thenReturn(new BigDecimal("-5.00"));
        GateReport r = report(1, 0, 1, 0, 0, 1);
        assertEquals(List.of("OPEN_ORDERS", "REFUND_PENDING", "PRODUCTS_ON_SALE",
                "DEPOSIT_ARREARS", "TIME_GATE"), codes(r));
    }
}

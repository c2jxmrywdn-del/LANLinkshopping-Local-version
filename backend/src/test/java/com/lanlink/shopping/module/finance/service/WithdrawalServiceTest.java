package com.lanlink.shopping.module.finance.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.finance.entity.FinanceLedger;
import com.lanlink.shopping.module.finance.entity.MerchantWithdrawal;
import com.lanlink.shopping.module.finance.mapper.FinanceLedgerMapper;
import com.lanlink.shopping.module.finance.mapper.MerchantWithdrawalMapper;
import com.lanlink.shopping.service.MerchantService;
import com.lanlink.shopping.service.SequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提现核心纪律单测（mock JdbcTemplate/Mapper，不起 Spring 上下文）：
 * 1. 并发冻结二选一：第二次申请的锁定读聚合已计入第一笔冻结 → 余额不足 400，只落一单；
 * 2. 驳回解冻：台账 withdraw_reject_unrefreeze 正入，解冻后余额回到 settled-paid；
 * 3. 台账平衡：每一笔变动后 sum(amount) == 最新 balance_after（余额口径顺序推算）；
 * 4. 打款守卫与状态抢占：凭证必填、非待审态 400、打款不再写台账（余额不变）；
 * 5. 锁定读次序：申请先取 t_merchant 商户行锁（资金互斥点），余额判定全部用 FOR UPDATE 变体。
 */
class WithdrawalServiceTest {

    private MerchantWithdrawalMapper withdrawalMapper;
    private FinanceLedgerMapper ledgerMapper;
    private MerchantService merchantService;
    private SequenceService sequenceService;
    private com.lanlink.shopping.module.attachment.service.AttachmentService attachmentService;
    private JdbcTemplate jdbc;
    private WithdrawalService svc;

    private Merchant merchant;

    @BeforeEach
    void setup() {
        withdrawalMapper = mock(MerchantWithdrawalMapper.class);
        ledgerMapper = mock(FinanceLedgerMapper.class);
        merchantService = mock(MerchantService.class);
        sequenceService = mock(SequenceService.class);
        attachmentService = mock(com.lanlink.shopping.module.attachment.service.AttachmentService.class);
        jdbc = mock(JdbcTemplate.class);
        // 真实资金锁服务（其 JdbcTemplate/Mapper 仍是本类的 mock）：保证「改实现点不换行为」被本类覆盖
        svc = new WithdrawalService(withdrawalMapper, ledgerMapper, merchantService,
                sequenceService, attachmentService, new MerchantFundLockService(jdbc, withdrawalMapper));

        merchant = new Merchant();
        merchant.setMerId(7L);
        merchant.setUserId(100L);
        when(merchantService.getByUser(100L)).thenReturn(merchant);
        when(sequenceService.nextNo(anyString(), eq(4))).thenReturn("0001");
    }

    private void settledNet(BigDecimal v, boolean locked) {
        // 锁定读（申请路径）与非锁定读（balanceOf/reject/cancel/guard）分开打桩
        when(jdbc.queryForObject(contains("FOR UPDATE"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(locked ? v : null);
        when(jdbc.queryForObject(contains("status IN ('SETTLED','REVERSED')"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(v);
    }

    private void pools(BigDecimal paid, BigDecimal frozen) {
        // 余额判定（apply/打款守卫）走 ForUpdate 变体；展示（balanceOf）与台账注解（驳回/撤回）走普通读
        when(withdrawalMapper.sumPaid(anyLong())).thenReturn(paid);
        when(withdrawalMapper.sumFrozen(anyLong())).thenReturn(frozen);
        when(withdrawalMapper.sumPaidForUpdate(anyLong())).thenReturn(paid);
        when(withdrawalMapper.sumFrozenForUpdate(anyLong())).thenReturn(frozen);
    }

    @Test
    void apply_freezes_and_writes_ledger_negative() {
        // settled=1000, paid=100, frozen=0 → available=900；申请 300 成功
        settledNet(new BigDecimal("1000.00"), true);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));

        MerchantWithdrawal w = svc.apply(100L, new BigDecimal("300"), "测试公司", "6222 0000 1111 2222",
                "工商银行深圳分行", null);

        assertEquals(0, w.getAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, w.getBalanceSnapshot().compareTo(new BigDecimal("900.00")));
        assertTrue(w.getBankAccountNoMasked().contains("*"), "落库必须是掩码");
        // 16 位卡号：前4后4 + 中间 8 个星（DesensitizeUtil.maskBankCard 口径）
        assertEquals("6222********2222", w.getBankAccountNoMasked());
        assertEquals("WD" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "0001",
                w.getWithdrawNo());

        ArgumentCaptor<FinanceLedger> cap = ArgumentCaptor.forClass(FinanceLedger.class);
        verify(ledgerMapper).insert(cap.capture());
        assertEquals("withdraw_freeze", cap.getValue().getBizType());
        assertEquals(0, cap.getValue().getAmount().compareTo(new BigDecimal("-300.00")));
        assertEquals(0, cap.getValue().getBalanceAfter().compareTo(new BigDecimal("600.00")));
    }

    @Test
    void concurrent_apply_second_request_fails_on_frozen_balance() {
        // 余额 600：两笔并发申请 500。第二笔锁定读聚合时 frozen 已含第一笔 500 → available=100 < 500
        settledNet(new BigDecimal("600.00"), true);
        pools(new BigDecimal("0.00"), new BigDecimal("500.00"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.apply(100L, new BigDecimal("500"), "测试公司", "6222000011112222", "工商银行", null));
        assertEquals(400, e.getCode());
        verify(ledgerMapper, never()).insert(any(FinanceLedger.class));
    }

    /**
     * 锁定读先生效（测的是服务层编排契约，mock 层）：申请必须先取 t_merchant 商户行锁
     * （资金互斥点，当前读），再执行余额判定的全部聚合，且聚合用 FOR UPDATE 变体——
     * RR 快照读不允许参与余额判定。真实锁语义由 WithdrawalConcurrencyTest（两线程两连接）覆盖。
     */
    @Test
    void apply_takes_merchant_row_lock_before_locked_aggregation() {
        settledNet(new BigDecimal("800.00"), true);
        pools(BigDecimal.ZERO, BigDecimal.ZERO);

        svc.apply(100L, new BigDecimal("500"), "测试公司", "6222000011112222", "工商银行", null);

        InOrder order = inOrder(merchantService, jdbc, withdrawalMapper);
        order.verify(merchantService).getByUser(100L);
        order.verify(jdbc).queryForObject(contains("FROM t_merchant WHERE mer_id = ? FOR UPDATE"),
                eq(Long.class), any(Object[].class));
        order.verify(jdbc).queryForObject(contains("FROM t_merchant_earning"), eq(BigDecimal.class),
                any(Object[].class));
        order.verify(withdrawalMapper).sumPaidForUpdate(7L);
        order.verify(withdrawalMapper).sumFrozenForUpdate(7L);
        // 判定口径不再使用快照读变体（双花根因即快照早建于对手提交）
        verify(withdrawalMapper, never()).sumPaid(7L);
        verify(withdrawalMapper, never()).sumFrozen(7L);
    }

    @Test
    void apply_over_balance_is_400_and_writes_nothing() {
        settledNet(new BigDecimal("100.00"), true);
        pools(BigDecimal.ZERO, BigDecimal.ZERO);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.apply(100L, new BigDecimal("100.01"), "测试公司", "6222000011112222", "工商银行", null));
        assertEquals(400, e.getCode());
        verify(withdrawalMapper, never()).insert(any(MerchantWithdrawal.class));
        verify(ledgerMapper, never()).insert(any(FinanceLedger.class));
    }

    @Test
    void apply_below_minimum_is_rejected() {
        assertThrows(BusinessException.class,
                () -> svc.apply(100L, new BigDecimal("0.99"), "测试公司", "6222000011112222", "工商银行", null));
        verify(withdrawalMapper, never()).insert(any(MerchantWithdrawal.class));
    }

    @Test
    void reject_unfreezes_with_positive_ledger_row() {
        MerchantWithdrawal w = withdrawal(0);
        MerchantWithdrawal rejected = withdrawal(2);
        when(withdrawalMapper.selectById(9L)).thenReturn(w, rejected);
        when(withdrawalMapper.markRejected(eq(9L), anyString(), eq(555L), any(LocalDateTime.class))).thenReturn(1);
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));

        MerchantWithdrawal out = svc.reject(555L, 9L, "信息不符");
        assertEquals(2, out.getStatus());

        ArgumentCaptor<FinanceLedger> cap = ArgumentCaptor.forClass(FinanceLedger.class);
        verify(ledgerMapper).insert(cap.capture());
        assertEquals("withdraw_reject_unrefreeze", cap.getValue().getBizType());
        // 正入解冻：amount=+300，解冻后余额 = settled(1000) - paid(100) = 900
        assertEquals(0, cap.getValue().getAmount().compareTo(new BigDecimal("300.00")));
        assertEquals(0, cap.getValue().getBalanceAfter().compareTo(new BigDecimal("900.00")));
    }

    @Test
    void reject_loses_race_is_400() {
        MerchantWithdrawal w = withdrawal(0);
        when(withdrawalMapper.selectById(9L)).thenReturn(w);
        when(withdrawalMapper.markRejected(eq(9L), anyString(), eq(555L), any(LocalDateTime.class))).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.reject(555L, 9L, "信息不符"));
        assertEquals(400, e.getCode());
        verify(ledgerMapper, never()).insert(any(FinanceLedger.class));
    }

    @Test
    void reject_requires_reason() {
        assertThrows(BusinessException.class, () -> svc.reject(555L, 9L, "  "));
        verify(withdrawalMapper, never()).markRejected(anyLong(), anyString(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void cancel_unfreezes_only_for_pending_and_owner() {
        MerchantWithdrawal w = withdrawal(0);
        MerchantWithdrawal cancelled = withdrawal(3);
        when(withdrawalMapper.selectById(9L)).thenReturn(w, cancelled);
        when(withdrawalMapper.markWithdrawn(eq(9L), eq(7L), any(LocalDateTime.class))).thenReturn(1);
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));

        MerchantWithdrawal out = svc.cancel(100L, 9L);
        assertEquals(3, out.getStatus());
        ArgumentCaptor<FinanceLedger> cap = ArgumentCaptor.forClass(FinanceLedger.class);
        verify(ledgerMapper).insert(cap.capture());
        assertEquals("withdraw_reject_unrefreeze", cap.getValue().getBizType());
        assertEquals(0, cap.getValue().getAmount().compareTo(new BigDecimal("300.00")));
    }

    @Test
    void cancel_other_merchant_is_403() {
        MerchantWithdrawal w = withdrawal(0);
        w.setMerId(8L);
        when(withdrawalMapper.selectById(9L)).thenReturn(w);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.cancel(100L, 9L));
        assertEquals(403, e.getCode());
        verify(withdrawalMapper, never()).markWithdrawn(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void approve_requires_voucher_and_marks_paid_without_ledger_row() {
        // 打款不改余额：冻结扣减在申请时已发生，打款不写台账；凭证附件 bind 到提现单
        MerchantWithdrawal w = withdrawal(0);
        MerchantWithdrawal paid = withdrawal(1);
        when(withdrawalMapper.selectById(9L)).thenReturn(w, paid, paid);
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));

        BusinessException e = assertThrows(BusinessException.class, () -> svc.approve(555L, 9L, " "));
        assertEquals(400, e.getCode());

        when(withdrawalMapper.markPaid(eq(9L), anyString(), eq(555L), any(LocalDateTime.class))).thenReturn(1);
        MerchantWithdrawal out = svc.approve(555L, 9L, "/api/attachment/WDV123/content");
        assertEquals(1, out.getStatus());
        verify(ledgerMapper, never()).insert(any(FinanceLedger.class));
        verify(attachmentService).bind(eq("WDV123"),
                eq(com.lanlink.shopping.module.attachment.service.AttachmentBizType.WITHDRAWAL_VOUCHER),
                eq(9L), eq(555L), eq(true));
    }

    @Test
    void approve_with_non_relay_voucher_url_still_marks_paid() {
        // 非 /api/attachment/{no}/content 形态的凭证：bind 失败只记日志，不挡资金状态机
        MerchantWithdrawal w = withdrawal(0);
        MerchantWithdrawal paid = withdrawal(1);
        when(withdrawalMapper.selectById(9L)).thenReturn(w, paid, paid);
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));
        when(withdrawalMapper.markPaid(eq(9L), anyString(), eq(555L), any(LocalDateTime.class))).thenReturn(1);

        MerchantWithdrawal out = svc.approve(555L, 9L, "BANK-RECEIPT-20261110-001");
        assertEquals(1, out.getStatus());
        verify(attachmentService, never()).bind(anyString(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void approve_loses_race_is_400() {
        MerchantWithdrawal w = withdrawal(0);
        when(withdrawalMapper.selectById(9L)).thenReturn(w);
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));
        when(withdrawalMapper.markPaid(eq(9L), anyString(), eq(555L), any(LocalDateTime.class))).thenReturn(0);
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.approve(555L, 9L, "/api/attachment/WDV123/content"));
        assertEquals(400, e.getCode());
        verify(attachmentService, never()).bind(anyString(), any(), anyLong(), anyLong(), eq(true));
    }

    @Test
    void approve_blocked_when_settled_net_cannot_cover() {
        // 打款守卫：settled(100) - paid(50) - self(300) < 0 → 拦截（结算台账被人工冲账的兜底）
        MerchantWithdrawal w = withdrawal(0);
        when(withdrawalMapper.selectById(9L)).thenReturn(w);
        settledNet(new BigDecimal("100.00"), false);
        pools(new BigDecimal("50.00"), new BigDecimal("0.00"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.approve(555L, 9L, "/api/attachment/WDV123/content"));
        assertEquals(400, e.getCode());
        verify(withdrawalMapper, never()).markPaid(anyLong(), anyString(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void voucher_proxy_rejects_other_merchant() {
        MerchantWithdrawal w = withdrawal(1);
        w.setMerId(8L);
        when(withdrawalMapper.selectById(9L)).thenReturn(w);
        BusinessException e = assertThrows(BusinessException.class, () -> svc.loadVoucher(100L, 9L, false));
        assertEquals(403, e.getCode());
    }

    @Test
    void ledger_balances_sum_equals_last_balance_after() {
        // 余额口径顺序推算的闭环：settled=1000/paid=100，申请 300（冻结→600）
        // 平衡式：期初余额(900=settled-paid) + Σ台账amount == 最新 balance_after
        settledNet(new BigDecimal("1000.00"), true);
        pools(new BigDecimal("100.00"), new BigDecimal("0.00"));
        svc.apply(100L, new BigDecimal("300"), "测试公司", "6222000011112222", "工商银行", null);

        ArgumentCaptor<FinanceLedger> cap = ArgumentCaptor.forClass(FinanceLedger.class);
        verify(ledgerMapper).insert(cap.capture());
        FinanceLedger row = cap.getValue();
        BigDecimal opening = new BigDecimal("900.00");
        assertEquals(0, opening.add(row.getAmount()).compareTo(row.getBalanceAfter()));
    }

    @Test
    void balanceOf_reports_available_and_pools() {
        settledNet(new BigDecimal("1000.00"), false);
        pools(new BigDecimal("100.00"), new BigDecimal("250.00"));
        when(withdrawalMapper.countPending(7L)).thenReturn(1L);
        var out = svc.balanceOf(100L);
        // available = 1000 - 100 - 250 = 650
        assertEquals(0, ((BigDecimal) out.get("available")).compareTo(new BigDecimal("650.00")));
        assertEquals(1L, ((Number) out.get("pendingCount")).longValue());
        assertEquals(0, ((BigDecimal) out.get("frozenTotal")).compareTo(new BigDecimal("250.00")));
    }

    private MerchantWithdrawal withdrawal(int status) {
        MerchantWithdrawal w = new MerchantWithdrawal();
        w.setId(9L);
        w.setMerId(7L);
        w.setWithdrawNo("WD202611100001");
        w.setAmount(new BigDecimal("300.00"));
        w.setBalanceSnapshot(new BigDecimal("900.00"));
        w.setStatus(status);
        w.setCreateTime(LocalDateTime.now());
        return w;
    }
}

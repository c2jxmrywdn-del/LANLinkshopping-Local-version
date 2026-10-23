package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditAdjustRequest;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.CreditRepayment;
import com.lanlink.shopping.mapper.CreditAccountMapper;
import com.lanlink.shopping.mapper.CreditAdjustRequestMapper;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.CreditRepaymentMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.vo.RepayResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 企业账期：调额申请、对公转账待确认核销与账单还款的核心分支单测。
 */
class CreditTermServiceTest {

    private CreditAccountMapper accountMapper;
    private CreditBillMapper billMapper;
    private CreditRepaymentMapper repaymentMapper;
    private CreditAdjustRequestMapper adjustMapper;
    private MessageService messageService;
    private ApplicationEventPublisher eventPublisher;
    private WalletService walletService;
    private CreditTermService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(CreditAccountMapper.class);
        billMapper = mock(CreditBillMapper.class);
        repaymentMapper = mock(CreditRepaymentMapper.class);
        adjustMapper = mock(CreditAdjustRequestMapper.class);
        messageService = mock(MessageService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        walletService = mock(WalletService.class);
        service = new CreditTermService(accountMapper, billMapper, repaymentMapper, adjustMapper,
                mock(UserMapper.class), messageService, eventPublisher, walletService);
    }

    private CreditAccount account(String status, String limit, String used, int termDays) {
        CreditAccount a = new CreditAccount();
        a.setId(1L);
        a.setUserId(7L);
        a.setStatus(status);
        a.setCreditLimit(new BigDecimal(limit));
        a.setAvailableLimit(new BigDecimal(limit).subtract(new BigDecimal(used)));
        a.setUsedLimit(new BigDecimal(used));
        a.setTermDays(termDays);
        return a;
    }

    private CreditBill bill(String amount, String outstanding, String status) {
        CreditBill b = new CreditBill();
        b.setId(5L);
        b.setAccountId(1L);
        b.setUserId(7L);
        b.setOrderNo("ORD-1");
        b.setAmount(new BigDecimal(amount));
        b.setPaidAmount(new BigDecimal(amount).subtract(new BigDecimal(outstanding)));
        b.setOutstandingAmount(new BigDecimal(outstanding));
        b.setDueDate(LocalDate.now().plusDays(10));
        b.setStatus(status);
        return b;
    }

    private CreditAdjustRequest adjustRequest(String status) {
        CreditAdjustRequest r = new CreditAdjustRequest();
        r.setId(9L);
        r.setAccountId(1L);
        r.setUserId(7L);
        r.setRequestedLimit(new BigDecimal("200000"));
        r.setTermDays(60);
        r.setStatus(status);
        return r;
    }

    // ==================== 调额申请 ====================

    @Test
    void applyAdjustmentRejectedWhenAccountNotActive() {
        when(accountMapper.selectByUserForUpdate(7L)).thenReturn(account("PENDING", "0", "0", 30));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAdjustment(7L, new BigDecimal("100000"), 60, "备货"));
        assertTrue(ex.getMessage().contains("生效"));
        verify(adjustMapper, never()).insert(any(CreditAdjustRequest.class));
    }

    @Test
    void applyAdjustmentRejectedWhenPendingExists() {
        when(accountMapper.selectByUserForUpdate(7L)).thenReturn(account("ACTIVE", "100000", "20000", 30));
        when(adjustMapper.selectPendingByAccount(1L)).thenReturn(adjustRequest("PENDING"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.applyAdjustment(7L, new BigDecimal("150000"), 60, "备货"));
        assertTrue(ex.getMessage().contains("待审核"));
    }

    @Test
    void applyAdjustmentInsertsAndNotifies() {
        when(accountMapper.selectByUserForUpdate(7L)).thenReturn(account("ACTIVE", "100000", "20000", 30));
        when(adjustMapper.selectPendingByAccount(1L)).thenReturn(null);
        CreditAdjustRequest result = service.applyAdjustment(7L, new BigDecimal("150000"), 60, "备货");
        assertEquals("PENDING", result.getStatus());
        assertEquals(new BigDecimal("150000"), result.getRequestedLimit());
        verify(adjustMapper).insert(result);
        verify(messageService).send(eq(7L), eq("credit"), anyString(), anyString(), anyString());
    }

    @Test
    void withdrawAdjustmentMarksWithdrawn() {
        CreditAdjustRequest r = adjustRequest("PENDING");
        when(adjustMapper.selectByIdForUpdate(9L)).thenReturn(r);
        when(adjustMapper.updateById(r)).thenReturn(1);
        CreditAdjustRequest result = service.withdrawAdjustment(7L, 9L);
        assertEquals("WITHDRAWN", result.getStatus());
        verify(adjustMapper).updateById(r);
    }

    @Test
    void reviewAdjustmentApprovedRecalculatesAvailableWithUsedLimit() {
        CreditAdjustRequest preview = adjustRequest("PENDING");
        CreditAccount a = account("ACTIVE", "100000", "30000", 30);
        when(adjustMapper.selectById(9L)).thenReturn(preview);
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(adjustMapper.selectByIdForUpdate(9L)).thenReturn(preview);
        when(adjustMapper.updateById(preview)).thenReturn(1);
        when(accountMapper.updateById(a)).thenReturn(1);

        CreditAdjustRequest result = service.reviewAdjustment(9L, "APPROVED", new BigDecimal("250000"), 90, "同意");

        assertEquals("APPROVED", result.getStatus());
        assertEquals(new BigDecimal("250000"), a.getCreditLimit());
        assertEquals(new BigDecimal("220000"), a.getAvailableLimit());
        assertEquals(90, a.getTermDays());
        assertEquals(new BigDecimal("250000"), preview.getApprovedLimit());
        verify(accountMapper).updateById(a);
    }

    @Test
    void reviewAdjustmentRejectedLeavesCreditUnchanged() {
        CreditAdjustRequest preview = adjustRequest("PENDING");
        CreditAccount a = account("ACTIVE", "100000", "30000", 30);
        when(adjustMapper.selectById(9L)).thenReturn(preview);
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(adjustMapper.selectByIdForUpdate(9L)).thenReturn(preview);
        when(adjustMapper.updateById(preview)).thenReturn(1);

        service.reviewAdjustment(9L, "REJECTED", null, null, "资料不足");

        assertEquals("REJECTED", preview.getStatus());
        assertEquals(new BigDecimal("100000"), a.getCreditLimit());
        verify(accountMapper, never()).updateById(any(CreditAccount.class));
    }

    @Test
    void reviewAccountApprovedWithdrawsPendingAdjustment() {
        CreditAccount a = account("PENDING", "0", "0", 30);
        a.setRequestedLimit(new BigDecimal("100000"));
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(adjustMapper.withdrawPendingByAccount(eq(1L), anyString())).thenReturn(1);
        when(accountMapper.updateById(a)).thenReturn(1);

        CreditAccount result = service.review(1L, "ACTIVE", new BigDecimal("100000"), 30, "通过");

        assertEquals("ACTIVE", result.getStatus());
        assertEquals(new BigDecimal("100000"), result.getAvailableLimit());
        verify(adjustMapper).withdrawPendingByAccount(eq(1L), anyString());
    }

    // ==================== 还款 / 转账待确认 ====================

    @Test
    void transferRepayCreatesPendingWithoutTouchingBill() {
        CreditBill b = bill("1000", "1000", "UNPAID");
        when(billMapper.selectForUpdate(5L, 7L)).thenReturn(b);
        when(repaymentMapper.sumPendingAmount(5L)).thenReturn(new BigDecimal("0.00"));
        when(repaymentMapper.insert(any(CreditRepayment.class))).thenReturn(1);

        RepayResultVO result = service.repay(7L, 5L, new BigDecimal("300"), "transfer", "BANK-1");

        assertTrue(result.isPending());
        CreditRepayment r = result.getRepayment();
        assertEquals("PENDING", r.getStatus());
        assertEquals("BANK-1", r.getReferenceNo());
        verify(walletService, never()).payFromWallet(anyLong(), any(), anyString());
        verify(billMapper, never()).updateById(any(CreditBill.class));
        assertEquals(0, new BigDecimal("1000.00").compareTo(b.getOutstandingAmount()));
    }

    @Test
    void repayRejectedWhenAmountExceedsOutstandingMinusPending() {
        CreditBill b = bill("1000", "1000", "UNPAID");
        when(billMapper.selectForUpdate(5L, 7L)).thenReturn(b);
        when(repaymentMapper.sumPendingAmount(5L)).thenReturn(new BigDecimal("800.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.repay(7L, 5L, new BigDecimal("300"), "wallet", null));
        assertTrue(ex.getMessage().contains("200.00"));
        verify(walletService, never()).payFromWallet(anyLong(), any(), anyString());
    }

    @Test
    void repayRejectedWhenNoPayableAmount() {
        CreditBill b = bill("1000", "1000", "UNPAID");
        when(billMapper.selectForUpdate(5L, 7L)).thenReturn(b);
        when(repaymentMapper.sumPendingAmount(5L)).thenReturn(new BigDecimal("1000.00"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.repay(7L, 5L, new BigDecimal("100"), "wallet", null));
        assertTrue(ex.getMessage().contains("待确认"));
    }

    @Test
    void walletRepaySettlesBillAndReleasesCredit() {
        CreditBill b = bill("1000", "1000", "UNPAID");
        CreditAccount a = account("ACTIVE", "100000", "1000", 30);
        when(billMapper.selectForUpdate(5L, 7L)).thenReturn(b);
        when(repaymentMapper.sumPendingAmount(5L)).thenReturn(new BigDecimal("0.00"));
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(repaymentMapper.insert(any(CreditRepayment.class))).thenReturn(1);
        when(billMapper.updateById(b)).thenReturn(1);
        when(accountMapper.release(1L, new BigDecimal("1000"))).thenReturn(1);

        RepayResultVO result = service.repay(7L, 5L, new BigDecimal("1000"), "wallet", null);

        assertFalse(result.isPending());
        assertEquals("PAID", b.getStatus());
        assertEquals(0, new BigDecimal("0.00").compareTo(b.getOutstandingAmount()));
        assertEquals("CONFIRMED", result.getRepayment().getStatus());
        verify(walletService).payFromWallet(7L, new BigDecimal("1000"), "CREDIT-5");
        verify(accountMapper).release(1L, new BigDecimal("1000"));
        verify(eventPublisher).publishEvent(any(org.springframework.context.ApplicationEvent.class));
    }

    @Test
    void confirmTransferApprovedSettlesBill() {
        CreditRepayment r = new CreditRepayment();
        r.setId(9L);
        r.setBillId(5L);
        r.setUserId(7L);
        r.setAmount(new BigDecimal("300"));
        r.setStatus("PENDING");
        CreditBill b = bill("1000", "1000", "UNPAID");
        CreditAccount a = account("ACTIVE", "100000", "1000", 30);
        when(repaymentMapper.selectById(9L)).thenReturn(r);
        when(billMapper.selectByIdForUpdate(5L)).thenReturn(b);
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(repaymentMapper.selectByIdForUpdate(9L)).thenReturn(r);
        when(repaymentMapper.confirmPending(eq(9L), anyString())).thenReturn(1);
        when(billMapper.updateById(b)).thenReturn(1);
        when(accountMapper.release(1L, new BigDecimal("300"))).thenReturn(1);

        CreditRepayment result = service.confirmTransferRepayment(9L, true, "已到账");

        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(0, new BigDecimal("700.00").compareTo(b.getOutstandingAmount()));
        assertEquals("UNPAID", b.getStatus());
        verify(accountMapper).release(1L, new BigDecimal("300"));
    }

    @Test
    void confirmTransferRejectedDoesNotTouchBill() {
        CreditRepayment r = new CreditRepayment();
        r.setId(9L);
        r.setBillId(5L);
        r.setUserId(7L);
        r.setAmount(new BigDecimal("300"));
        r.setStatus("PENDING");
        CreditBill b = bill("1000", "1000", "UNPAID");
        CreditAccount a = account("ACTIVE", "100000", "1000", 30);
        when(repaymentMapper.selectById(9L)).thenReturn(r);
        when(billMapper.selectByIdForUpdate(5L)).thenReturn(b);
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(repaymentMapper.selectByIdForUpdate(9L)).thenReturn(r);
        when(repaymentMapper.rejectPending(eq(9L), anyString())).thenReturn(1);

        CreditRepayment result = service.confirmTransferRepayment(9L, false, "凭证不符");

        assertEquals("REJECTED", result.getStatus());
        assertEquals(0, new BigDecimal("1000.00").compareTo(b.getOutstandingAmount()));
        verify(billMapper, never()).updateById(any(CreditBill.class));
        verify(accountMapper, never()).release(anyLong(), any());
    }

    @Test
    void confirmTransferRejectedWhenAlreadyHandled() {
        CreditRepayment r = new CreditRepayment();
        r.setId(9L);
        r.setBillId(5L);
        r.setAmount(new BigDecimal("300"));
        r.setStatus("CONFIRMED");
        when(repaymentMapper.selectById(9L)).thenReturn(r);
        when(billMapper.selectByIdForUpdate(5L)).thenReturn(bill("1000", "700", "UNPAID"));
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(account("ACTIVE", "100000", "300", 30));
        when(repaymentMapper.selectByIdForUpdate(9L)).thenReturn(r);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmTransferRepayment(9L, true, null));
        assertTrue(ex.getMessage().contains("已处理"));
    }

    @Test
    void cancelOrderBillRejectsPendingTransfersAndClearsOutstanding() {
        CreditBill b = bill("1000", "1000", "UNPAID");
        CreditAccount a = account("ACTIVE", "100000", "1000", 30);
        when(billMapper.selectOne(any())).thenReturn(b);
        when(billMapper.selectByIdForUpdate(5L)).thenReturn(b);
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(a);
        when(repaymentMapper.rejectPendingByBill(eq(5L), anyString())).thenReturn(1);
        when(billMapper.updateById(b)).thenReturn(1);
        when(accountMapper.release(1L, new BigDecimal("1000"))).thenReturn(1);

        service.cancelOrderBill(7L, "ORD-1");

        assertEquals("CANCELLED", b.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(b.getOutstandingAmount()));
        verify(repaymentMapper).rejectPendingByBill(eq(5L), anyString());
        verify(accountMapper).release(1L, new BigDecimal("1000"));
    }
}

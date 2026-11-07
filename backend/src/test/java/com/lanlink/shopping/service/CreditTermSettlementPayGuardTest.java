package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.entity.LogisticsSettlementItem;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.CreditAccountMapper;
import com.lanlink.shopping.mapper.CreditAdjustRequestMapper;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.CreditRepaymentMapper;
import com.lanlink.shopping.mapper.LogisticsSettlementItemMapper;
import com.lanlink.shopping.mapper.LogisticsSettlementMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P0-1 结算单账期支付守卫单测：
 * 1. 非本商户（运单 created_by 不一致）的 approved 结算单拒绝支付（fail-closed）；
 * 2. 支付走条件 UPDATE 抢占（affected=0 即拒绝，账单不落库），禁全实体写回；
 * 3. 管理员身份保留平台运营台代结算流程。
 */
class CreditTermSettlementPayGuardTest {

    private CreditAccountMapper accountMapper;
    private CreditBillMapper billMapper;
    private CreditRepaymentMapper repaymentMapper;
    private CreditAdjustRequestMapper adjustMapper;
    private UserMapper userMapper;
    private MessageService messageService;
    private ApplicationEventPublisher eventPublisher;
    private WalletService walletService;
    private LogisticsSettlementMapper settlementMapper;
    private LogisticsSettlementItemMapper itemMapper;
    private LogisticsWaybillMapper waybillMapper;
    private CreditTermService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(CreditAccountMapper.class);
        billMapper = mock(CreditBillMapper.class);
        repaymentMapper = mock(CreditRepaymentMapper.class);
        adjustMapper = mock(CreditAdjustRequestMapper.class);
        userMapper = mock(UserMapper.class);
        messageService = mock(MessageService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        walletService = mock(WalletService.class);
        settlementMapper = mock(LogisticsSettlementMapper.class);
        itemMapper = mock(LogisticsSettlementItemMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);

        service = new CreditTermService(accountMapper, billMapper, repaymentMapper, adjustMapper,
                userMapper, messageService, eventPublisher, walletService);
        ReflectionTestUtils.setField(service, "settlementMapper", settlementMapper);
        ReflectionTestUtils.setField(service, "settlementItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "waybillMapper", waybillMapper);
    }

    private LogisticsSettlement approvedSettlement() {
        LogisticsSettlement s = new LogisticsSettlement();
        s.setSettlementId(501L);
        s.setSettlementNo("JS2026110001");
        s.setStatus("approved");
        s.setPayableAmount(new BigDecimal("3500.00"));
        return s;
    }

    private void mockOwnedBy(long createdByUserId) {
        LogisticsSettlementItem item = new LogisticsSettlementItem();
        item.setSettlementId(501L);
        item.setWaybillId(1L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setCreatedBy(createdByUserId);
        when(waybillMapper.selectBatchIds(any())).thenReturn(List.of(wb));
    }

    private void mockAccountReady(long userId) {
        CreditAccount account = new CreditAccount();
        account.setId(10L);
        account.setUserId(userId);
        account.setStatus("ACTIVE");
        account.setTermDays(10);
        account.setCreditLimit(new BigDecimal("50000.00"));
        account.setAvailableLimit(new BigDecimal("50000.00"));
        account.setUsedLimit(BigDecimal.ZERO);
        when(accountMapper.selectByUserForUpdate(userId)).thenReturn(account);
        when(accountMapper.reserve(eq(10L), any(BigDecimal.class))).thenReturn(1);
        when(billMapper.insert(any(CreditBill.class))).thenAnswer(inv -> {
            CreditBill b = inv.getArgument(0);
            b.setId(200L);
            return 1;
        });
    }

    @Test
    @DisplayName("非本商户 approved 结算单拒绝账期支付：不抢占、不建账单")
    void nonOwnerMerchantRejected() {
        when(settlementMapper.selectById(501L)).thenReturn(approvedSettlement());
        mockOwnedBy(999L); // 运单由其他商户（999）发货，付款人为 888

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.paySettlementWithCredit(501L, 888L, null));

        assertTrue(ex.getMessage().contains("归属商户"));
        verify(settlementMapper, never()).markPaidWithCredit(anyLong());
        verify(settlementMapper, never()).markPaidCreditBill(anyLong(), anyLong());
        verify(billMapper, never()).insert(any(CreditBill.class));
        verify(accountMapper, never()).reserve(anyLong(), any());
    }

    @Test
    @DisplayName("结算单无明细或运单缺失时 fail-closed 拒绝")
    void missingItemsOrWaybillsRejected() {
        when(settlementMapper.selectById(501L)).thenReturn(approvedSettlement());

        // 无明细
        when(itemMapper.selectList(any())).thenReturn(List.of());
        BusinessException exEmpty = assertThrows(BusinessException.class,
                () -> service.paySettlementWithCredit(501L, 888L, null));
        assertTrue(exEmpty.getMessage().contains("结算明细"));

        // 明细在但运单行缺失（防脏数据）
        mockOwnedBy(888L);
        when(waybillMapper.selectBatchIds(any())).thenReturn(List.of());
        BusinessException exMissing = assertThrows(BusinessException.class,
                () -> service.paySettlementWithCredit(501L, 888L, null));
        assertTrue(exMissing.getMessage().contains("运单数据缺失"));
        verify(settlementMapper, never()).markPaidWithCredit(anyLong());
    }

    @Test
    @DisplayName("归属商户支付成功：条件 UPDATE 抢占 + 回填账单关联，禁全实体写回")
    void ownerPaysThroughConditionalClaim() {
        when(settlementMapper.selectById(501L)).thenReturn(approvedSettlement());
        mockOwnedBy(888L);
        mockAccountReady(888L);
        when(settlementMapper.markPaidWithCredit(501L)).thenReturn(1);
        when(settlementMapper.markPaidCreditBill(501L, 200L)).thenReturn(1);

        CreditBill bill = service.paySettlementWithCredit(501L, 888L, null);

        assertNotNull(bill);
        assertEquals(200L, bill.getId());
        verify(settlementMapper).markPaidWithCredit(501L);
        verify(settlementMapper).markPaidCreditBill(501L, 200L);
        verify(settlementMapper, never()).updateById(any(LogisticsSettlement.class));
    }

    @Test
    @DisplayName("并发抢占失败（affected=0）即拒绝，账单不落库")
    void claimConflictRejectedWithoutBill() {
        when(settlementMapper.selectById(501L)).thenReturn(approvedSettlement());
        mockOwnedBy(888L);
        mockAccountReady(888L);
        when(settlementMapper.markPaidWithCredit(501L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.paySettlementWithCredit(501L, 888L, null));

        assertTrue(ex.getMessage().contains("状态已变化"));
        verify(billMapper, never()).insert(any(CreditBill.class));
        verify(settlementMapper, never()).markPaidCreditBill(anyLong(), anyLong());
    }

    @Test
    @DisplayName("管理员身份保留运营台代结算流程，不受商户归属约束")
    void adminMayPayAnySettlement() {
        when(settlementMapper.selectById(501L)).thenReturn(approvedSettlement());
        mockOwnedBy(999L); // 明细运单属于商户 999，操作人为管理员
        mockAccountReady(888L);
        when(settlementMapper.markPaidWithCredit(501L)).thenReturn(1);
        when(settlementMapper.markPaidCreditBill(501L, 200L)).thenReturn(1);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.IDENTITY_KEY, UserIdentity.ADMIN);

        CreditBill bill = assertDoesNotThrow(() -> service.paySettlementWithCredit(501L, 888L, req));

        assertNotNull(bill);
        verify(settlementMapper).markPaidWithCredit(501L);
    }
}

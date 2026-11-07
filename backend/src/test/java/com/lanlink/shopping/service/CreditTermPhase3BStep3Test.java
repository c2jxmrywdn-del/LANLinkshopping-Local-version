package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.LogisticsSettlement;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Phase 3B Step 3 自动化测试套件：
 * 1. 7~15 天极速周转模型硬边界防御；
 * 2. 基于第三方信用评级 (AAA/AA/A/B) 动态分级利率与单利年化 APR 真实披露；
 * 3. 物流履约良好优惠折扣；
 * 4. 业财与物流联动：物流运费月结账单使用账期担保支付。
 */
class CreditTermPhase3BStep3Test {

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

    @Test
    @DisplayName("7~15天期限硬边界：小于7天或大于15天必须拦截抛出 BusinessException")
    void testTermDaysBoundaryDefense() {
        // 6天 -> 抛异常
        assertThrows(BusinessException.class, () ->
                service.calculateInterestTrial(new BigDecimal("10000"), 6, null, 3, 0));

        // 16天 -> 抛异常
        assertThrows(BusinessException.class, () ->
                service.calculateInterestTrial(new BigDecimal("10000"), 16, null, 3, 0));

        // 7天与15天合规边界 -> 成功
        assertDoesNotThrow(() ->
                service.calculateInterestTrial(new BigDecimal("10000"), 7, null, 3, 0));
        assertDoesNotThrow(() ->
                service.calculateInterestTrial(new BigDecimal("10000"), 15, null, 3, 0));
    }

    @Test
    @DisplayName("基于第三方脱敏评级 (AAA/AA/A/B) 的分级利率与 APR 精算")
    void testTieredInterestRateByRiskTier() {
        // AAA 级：日息 0.0150%/天, 单利年化 APR 5.40%, 免息 3 天
        Map<String, Object> trialAaa = service.calculateInterestTrial(
                new BigDecimal("10000.00"), 10, null, null, 0, "AAA", false);
        assertEquals("AAA", trialAaa.get("riskTier"));
        assertEquals(new BigDecimal("0.000150"), trialAaa.get("dailyRate"));
        assertEquals(new BigDecimal("0.0540"), trialAaa.get("apr"));
        assertEquals(7, trialAaa.get("interestBearingDays")); // 10 - 3
        assertEquals(new BigDecimal("10.50"), trialAaa.get("normalInterest")); // 10000 * 0.000150 * 7 = 10.50

        // AA 级：日息 0.0200%/天, APR 7.20%
        Map<String, Object> trialAa = service.calculateInterestTrial(
                new BigDecimal("10000.00"), 10, null, null, 0, "AA", false);
        assertEquals(new BigDecimal("0.000200"), trialAa.get("dailyRate"));
        assertEquals(new BigDecimal("0.0720"), trialAa.get("apr"));

        // A 级：日息 0.0250%/天, APR 9.00%
        Map<String, Object> trialA = service.calculateInterestTrial(
                new BigDecimal("10000.00"), 10, null, null, 0, "A", false);
        assertEquals(new BigDecimal("0.000250"), trialA.get("dailyRate"));
        assertEquals(new BigDecimal("0.0900"), trialA.get("apr"));

        // B 级：日息 0.0300%/天, APR 10.80%, 免息 2 天
        Map<String, Object> trialB = service.calculateInterestTrial(
                new BigDecimal("10000.00"), 10, null, null, 0, "B", false);
        assertEquals(new BigDecimal("0.000300"), trialB.get("dailyRate"));
        assertEquals(new BigDecimal("0.1080"), trialB.get("apr"));
        assertEquals(2, trialB.get("graceDays"));
    }

    @Test
    @DisplayName("物流信誉折扣：履约优秀商户享受利率下浮")
    void testLogisticsReputationDiscount() {
        // AAA 级享受 15% 利率折扣：0.000150 * 0.85 = 0.000128
        Map<String, Object> trial = service.calculateInterestTrial(
                new BigDecimal("10000.00"), 10, null, null, 0, "AAA", true);
        assertEquals(new BigDecimal("0.1500"), trial.get("discountRate"));
        assertEquals(new BigDecimal("0.000128"), trial.get("dailyRate"));
    }

    @Test
    @DisplayName("业财联动：物流运费月结结算单通过企业账期担保完成支付")
    void testPaySettlementWithCreditGuarantee() {
        CreditAccount account = new CreditAccount();
        account.setId(10L);
        account.setUserId(888L);
        account.setStatus("ACTIVE");
        account.setTermDays(10);
        account.setCreditLimit(new BigDecimal("50000.00"));
        account.setAvailableLimit(new BigDecimal("50000.00"));
        account.setUsedLimit(BigDecimal.ZERO);
        when(accountMapper.selectByUserForUpdate(888L)).thenReturn(account);
        when(accountMapper.reserve(eq(10L), any(BigDecimal.class))).thenReturn(1);
        when(billMapper.insert(any(CreditBill.class))).thenAnswer(inv -> {
            CreditBill b = inv.getArgument(0);
            b.setId(200L);
            return 1;
        });

        LogisticsSettlement settlement = new LogisticsSettlement();
        settlement.setSettlementId(501L);
        settlement.setSettlementNo("JS2026110001");
        settlement.setStatus("approved");
        settlement.setPayableAmount(new BigDecimal("3500.00"));
        when(settlementMapper.selectById(501L)).thenReturn(settlement);
        // 属主校验：结算明细运单均为付款人（888）发货
        com.lanlink.shopping.entity.LogisticsSettlementItem item = new com.lanlink.shopping.entity.LogisticsSettlementItem();
        item.setSettlementId(501L);
        item.setWaybillId(1L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        com.lanlink.shopping.entity.LogisticsWaybill wb = new com.lanlink.shopping.entity.LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setCreatedBy(888L);
        when(waybillMapper.selectBatchIds(any())).thenReturn(List.of(wb));
        // 条件更新抢占成功且回填账单关联
        when(settlementMapper.markPaidWithCredit(501L)).thenReturn(1);
        when(settlementMapper.markPaidCreditBill(501L, 200L)).thenReturn(1);

        CreditBill bill = service.paySettlementWithCredit(501L, 888L, null);

        assertNotNull(bill);
        assertEquals("LOGISTICS_FREIGHT", bill.getBillType());
        assertEquals("JS2026110001", bill.getSettlementNo());
        assertEquals(new BigDecimal("3500.00"), bill.getAmount());
        assertEquals(LocalDate.now().plusDays(10), bill.getDueDate());

        // 验证结算单已被置为 paid，支付方式为 credit_guarantee（条件 UPDATE 落库 + 实体内存同步）
        assertEquals("paid", settlement.getStatus());
        assertEquals("credit_guarantee", settlement.getPayMethod());
        assertEquals(200L, settlement.getCreditBillId());
        verify(settlementMapper).markPaidWithCredit(501L);
        verify(settlementMapper).markPaidCreditBill(501L, 200L);
        // 禁全实体写回：不允许再出现无条件 updateById
        verify(settlementMapper, never()).updateById(any(LogisticsSettlement.class));
    }
}

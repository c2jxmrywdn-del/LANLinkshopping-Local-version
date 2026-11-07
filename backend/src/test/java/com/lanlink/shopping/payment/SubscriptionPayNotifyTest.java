package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.service.SubscriptionOrderNos;
import com.lanlink.shopping.payment.service.SubscriptionPayHandler;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.vo.PayStatusVO;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订阅单支付闭环单测（P1-2/P1-3）：SUB 命名空间分流、金额校验反查订阅单、
 * 幂等 = 订阅单 pay_status 状态机（回调重放不再重复结算/重复驱动）、query/mockConfirm/wallet 分支。
 * 金额口径：amount_cents 整数元（8888 = ¥8,888）→ 渠道「分」= 元×100，与订单侧 total_amount 同源换算。
 */
class SubscriptionPayNotifyTest {

    private static final String SUB_NO = "SUB2026110600000001";

    private PaymentProperties props;
    private WechatPayClient wechat;
    private AlipayPayClient alipay;
    private StripePayClient stripe;
    private OrderService orderService;
    private OrderMapper orderMapper;
    private WalletService walletService;
    private SubscriptionOrderMapper subOrderMapper;
    private SubscriptionPayHandler handler;
    private PaymentService svc;

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        props.setMock(true);
        wechat = mock(WechatPayClient.class);
        alipay = mock(AlipayPayClient.class);
        stripe = mock(StripePayClient.class);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        PaymentLogService payLog = mock(PaymentLogService.class);
        walletService = mock(WalletService.class);
        svc = new PaymentService(props, wechat, alipay, stripe, orderService, orderMapper, payLog, walletService);
        subOrderMapper = mock(SubscriptionOrderMapper.class);
        svc.setSubscriptionOrderMapper(subOrderMapper);
        handler = mock(SubscriptionPayHandler.class);
        svc.setSubscriptionPayHandler(handler);
    }

    /** 待支付订阅单：amount_cents = 8888（整数元语义，¥8,888） */
    private SubscriptionOrder pendingSubOrder() {
        SubscriptionOrder so = new SubscriptionOrder();
        so.setOrderNo(SUB_NO);
        so.setUserId(7L);
        so.setPlanId(4L);
        so.setPlanName("年度订阅");
        so.setAmountCents(8888);
        so.setDurationDays(365);
        so.setAutoRenew(0);
        so.setPayStatus(0);
        so.setApplyStatus(0);
        return so;
    }

    private void stubPending() {
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(pendingSubOrder());
    }

    // ===== 单据命名空间 =====

    @Test
    void subscriptionOrderNoPrefixRoutesAsSubscription() {
        assertTrue(SubscriptionOrderNos.isSubscriptionOrderNo(SUB_NO));
        assertFalse(SubscriptionOrderNos.isSubscriptionOrderNo("20261106000000x"));
        assertFalse(SubscriptionOrderNos.isSubscriptionOrderNo(null));
    }

    // ===== P1-3 微信回调：金额校验反查订阅单 + 状态机幂等 =====

    @Test
    void wechatNotifySubscriptionSettlesOnceAndIsIdempotentOnReplay() {
        stubPending();
        // amount_cents=8888 整数元 → 应收分 = 888800（元×100 同源换算）
        when(subOrderMapper.markPaidAtomic(eq(SUB_NO), eq("wechat"), eq("WX-TXN-1"), any()))
                .thenReturn(1)   // 首次回调：0→1 成功
                .thenReturn(0);  // 渠道重放：状态机拒绝重复结算
        // 金额校验/补偿检查反查订阅单：反查一律返回已支付单（amountCents 不变，金额校验仍通过）
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(paidSubOrder());

        String reply = notifyWechat("888800", "WX-TXN-1");
        assertTrue(reply.contains("SUCCESS"));
        String reply2 = notifyWechat("888800", "WX-TXN-1"); // 渠道重放
        assertTrue(reply2.contains("SUCCESS"));
        verify(handler, times(2)).onSubscriptionOrderPaid(SUB_NO);
        // 商品订单路径完全未被触碰
        verify(orderService, never()).settlePaid(anyString(), anyString(), anyString());
    }

    @Test
    void wechatNotifySubscriptionRejectsAmountMismatch() {
        stubPending();
        String reply = notifyWechat("88880", "WX-TXN-1"); // 少一个 0：88880 分 ≠ 888800 分
        assertEquals(WechatPayClient.notifyReply(false, "金额校验失败"), reply);
        verify(subOrderMapper, never()).markPaidAtomic(anyString(), anyString(), anyString(), any());
        verify(handler, never()).onSubscriptionOrderPaid(anyString());
    }

    @Test
    void wechatNotifyUnknownSubscriptionOrderFailsAmountCheck() {
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(null);
        String reply = notifyWechat("888800", "WX-TXN-1");
        assertEquals(WechatPayClient.notifyReply(false, "金额校验失败"), reply);
        verify(handler, never()).onSubscriptionOrderPaid(anyString());
    }

    // ===== P1-3 支付宝回调：订阅单分支 =====

    @Test
    void alipayNotifySubscriptionSettlesAndBlocksReplay() {
        stubPending();
        when(subOrderMapper.markPaidAtomic(eq(SUB_NO), eq("alipay"), eq("ALI-TXN-1"), any())).thenReturn(1).thenReturn(0);
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(paidSubOrder());

        assertEquals("success", notifyAlipay("8888.00", "ALI-TXN-1"));
        assertEquals("success", notifyAlipay("8888.00", "ALI-TXN-1")); // 重放：不重复结算，仍应答 success 停止重试
        verify(handler, times(2)).onSubscriptionOrderPaid(SUB_NO);
        verify(orderService, never()).settlePaid(anyString(), anyString(), anyString());
    }

    @Test
    void alipayNotifySubscriptionRejectsAmountMismatch() {
        stubPending();
        assertEquals("failure", notifyAlipay("888.00", "ALI-TXN-1"));
        verify(handler, never()).onSubscriptionOrderPaid(anyString());
    }

    // ===== P1-3 Stripe 回调：metadata orderNo + 分金额校验走订阅单状态机 =====

    @Test
    void stripeNotifySubscriptionSettlesByIdempotentStateMachine() throws Exception {
        stubPending();
        when(subOrderMapper.markPaidAtomic(eq(SUB_NO), eq("stripe"), eq("pi_1"), any())).thenReturn(1).thenReturn(0);
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(paidSubOrder());

        assertEquals("{\"received\": true}", notifyStripe("pi_1", 888800L));
        assertEquals("{\"received\": true}", notifyStripe("pi_1", 888800L)); // 重放安全
        verify(handler, times(2)).onSubscriptionOrderPaid(SUB_NO);
        verify(orderService, never()).settlePaid(anyString(), anyString(), anyString());
    }

    @Test
    void stripeNotifySubscriptionWrongAmountDoesNotSettle() throws Exception {
        stubPending();
        assertEquals("{\"received\": true}", notifyStripe("pi_1", 88880L));
        verify(subOrderMapper, never()).markPaidAtomic(anyString(), anyString(), anyString(), any());
        verify(handler, never()).onSubscriptionOrderPaid(anyString());
    }

    // ===== P1-2 发起支付：wallet 同步结算 / mock 确认 / query 分流 =====

    @Test
    void createSubscriptionWalletSettlesSynchronously() {
        stubPending();
        when(subOrderMapper.markPaidAtomic(eq(SUB_NO), eq("wallet"), eq("W99"), any())).thenReturn(1);
        WalletLog wl = new WalletLog();
        wl.setId(99L);
        when(walletService.payFromWallet(eq(7L), eq(new BigDecimal("8888")), eq(SUB_NO))).thenReturn(wl);
        when(walletService.balanceOf(7L)).thenReturn(new BigDecimal("1112"));

        PayCreateVO vo = svc.create(SUB_NO, 7L, "wallet");
        assertEquals(SUB_NO, vo.getOrderNo());
        assertEquals("wallet", vo.getChannel());
        assertEquals(true, vo.getPayInfo().get("paid"));
        verify(handler).onSubscriptionOrderPaid(SUB_NO);
    }

    @Test
    void createSubscriptionRejectsNonOwner() {
        stubPending();
        assertThrows(BusinessException.class, () -> svc.create(SUB_NO, 8L, "wallet"));
        verify(walletService, never()).payFromWallet(any(), any(), anyString());
    }

    @Test
    void createSubscriptionRejectsAlreadyPaid() {
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(paidSubOrder());
        assertThrows(BusinessException.class, () -> svc.create(SUB_NO, 7L, "wallet"));
    }

    @Test
    void mockConfirmSubscriptionSettlesThenRejectsReplay() {
        stubPending();
        SubscriptionOrder so = pendingSubOrder();
        so.setChannel("mock");
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(so, paidSubOrder());
        when(subOrderMapper.markPaidAtomic(eq(SUB_NO), eq("mock"), anyString(), any())).thenReturn(1);

        assertTrue(svc.mockConfirm(SUB_NO, 7L));
        assertThrows(BusinessException.class, () -> svc.mockConfirm(SUB_NO, 7L)); // 状态机：已支付拒绝重复确认
    }

    @Test
    void mockConfirmSubscriptionRequiresMockChannel() {
        SubscriptionOrder so = pendingSubOrder();
        so.setChannel("wechat");
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(so);
        assertThrows(BusinessException.class, () -> svc.mockConfirm(SUB_NO, 7L));
    }

    @Test
    void querySubscriptionMapsStatusMachineAndYuanAmount() {
        when(subOrderMapper.selectById(SUB_NO)).thenReturn(paidSubOrder());
        PayStatusVO vo = svc.query(SUB_NO, 7L, false);
        assertEquals(SUB_NO, vo.getOrderNo());
        assertEquals(1, vo.getPayStatus());
        assertEquals(0, BigDecimal.valueOf(8888).compareTo(vo.getTotalAmount())); // 整数元出参
        assertEquals("wechat", vo.getPayChannel());
        assertThrows(BusinessException.class, () -> svc.query(SUB_NO, 8L, false)); // 非本人
    }

    // ===== 辅助 =====

    private SubscriptionOrder paidSubOrder() {
        SubscriptionOrder so = pendingSubOrder();
        so.setPayStatus(1);
        so.setApplyStatus(1);
        so.setChannel("wechat");
        so.setTransactionId("WX-TXN-1");
        return so;
    }

    private String notifyWechat(String totalFeeFen, String txnId) {
        Map<String, String> data = new HashMap<>();
        data.put("return_code", "SUCCESS");
        data.put("result_code", "SUCCESS");
        data.put("out_trade_no", SUB_NO);
        data.put("total_fee", totalFeeFen);
        data.put("transaction_id", txnId);
        when(wechat.parseAndVerifyNotify(anyString())).thenReturn(data);
        return svc.handleWechatNotify("<xml/>");
    }

    private String notifyAlipay(String totalAmount, String tradeNo) {
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", SUB_NO);
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", totalAmount);
        params.put("trade_no", tradeNo);
        when(alipay.verifyNotify(anyMap())).thenReturn(true);
        return svc.handleAlipayNotify(params);
    }

    private String notifyStripe(String piId, long amountCents) throws Exception {
        com.stripe.model.Event event = mock(com.stripe.model.Event.class);
        com.stripe.model.EventDataObjectDeserializer deser = mock(com.stripe.model.EventDataObjectDeserializer.class);
        com.stripe.model.PaymentIntent intent = mock(com.stripe.model.PaymentIntent.class);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("orderNo", SUB_NO);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(metadata);
        when(intent.getId()).thenReturn(piId);
        when(intent.getAmount()).thenReturn(amountCents);
        when(stripe.verifyAndConstructEvent(anyString(), anyString())).thenReturn(event);
        return svc.handleStripeNotify("{}", "sig");
    }
}

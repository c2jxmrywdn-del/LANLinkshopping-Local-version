package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.conflict.dto.SettlePaidResult;
import com.lanlink.shopping.payment.conflict.entity.PaymentConflict;
import com.lanlink.shopping.payment.conflict.service.PaymentConflictService;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B 接线回归：结算三态收口、支付入口门禁、冲突退款通道、渠道单过期。
 *
 * <p>「改前失败」对照：旧实现中 {@code settlePaid} 返回 false 时各 notify 分支固定记
 * {@code success=true} 并向渠道 ACK，且无任何挂账写入；本测试锁定修复后行为：
 * 已关单回调 → 仍 ACK（渠道不重试风暴），但必须先写出 {@code late_pay_after_close} 挂账；
 * 挂账写失败 → 返回 FAIL（渠道重试），绝不吞掉资金事件。
 */
class PaymentConflictWiringTest {

    private PaymentProperties props;
    private WechatPayClient wechat;
    private AlipayPayClient alipay;
    private StripePayClient stripe;
    private OrderService orderService;
    private OrderMapper orderMapper;
    private PaymentLogService payLog;
    private WalletService walletService;
    private PaymentConflictService conflictService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        props.setMock(true); // 支付流程单测显式开启模拟支付，不依赖生产默认值
        wechat = mock(WechatPayClient.class);
        alipay = mock(AlipayPayClient.class);
        stripe = mock(StripePayClient.class);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        payLog = mock(PaymentLogService.class);
        walletService = mock(WalletService.class);
        conflictService = mock(PaymentConflictService.class);
        service = new PaymentService(props, wechat, alipay, stripe, orderService, orderMapper, payLog, walletService);
        service.setPaymentConflictService(conflictService);
    }

    private static Order order(int payStatus, int orderStatus) {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(1L);
        o.setPayStatus(payStatus);
        o.setOrderStatus(orderStatus);
        o.setTotalAmount(new BigDecimal("88.80"));
        return o;
    }

    private static SettlePaidResult conflict(Order o, String txn) {
        return new SettlePaidResult(SettlePaidResult.Outcome.ORDER_CONFLICT, o, txn, false);
    }

    private Map<String, String> wechatNotifyData() {
        Map<String, String> data = new HashMap<>();
        data.put("return_code", "SUCCESS");
        data.put("result_code", "SUCCESS");
        data.put("out_trade_no", "ORD1");
        data.put("transaction_id", "TXN1");
        data.put("total_fee", "8880");
        return data;
    }

    // ===== 1. 关单后补回调：ACK + 写挂账（改前：静默 ACK、无挂账） =====

    @Test
    void wechatNotifyOnClosedOrder_isAckedButConflictRecorded() {
        Order closed = order(0, 3);
        when(orderMapper.selectById("ORD1")).thenReturn(closed);
        when(wechat.parseAndVerifyNotify(anyString())).thenReturn(wechatNotifyData());
        when(orderService.settlePaid("ORD1", "wechat", "TXN1")).thenReturn(conflict(closed, "TXN1"));
        when(conflictService.record(any(), eq("wechat"), eq("TXN1"), any(),
                eq(PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE), anyString())).thenReturn(true);

        String reply = service.handleWechatNotify("<xml/>");

        assertTrue(reply.contains("<return_code><![CDATA[SUCCESS]]></return_code>"),
                "三态都必须 ACK 渠道，否则渠道无限重试");
        verify(conflictService).record(any(), eq("wechat"), eq("TXN1"), any(),
                eq(PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE), eq("wechat_notify"));
        verify(payLog).warn(eq("ORD1"), eq("wechat"), eq("CONFLICT"), eq("IN"), eq(false), anyString());
    }

    @Test
    void conflictRecordFailure_makesAlipayNotifyReturnFailureSoChannelRetries() {
        Order closed = order(0, 3);
        when(orderMapper.selectById("ORD1")).thenReturn(closed);
        when(alipay.verifyNotify(anyMap())).thenReturn(true);
        when(orderService.settlePaid("ORD1", "alipay", "T1")).thenReturn(conflict(closed, "T1"));
        when(conflictService.record(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "ORD1");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "88.80");
        params.put("trade_no", "T1");

        assertEquals("failure", service.handleAlipayNotify(params),
                "挂账写失败必须返回 FAIL 让渠道重试，绝不能吞掉资金事件");
    }

    @Test
    void duplicateCallback_isAckedWithoutWritingConflict() {
        Order paid = order(1, 0);
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        when(wechat.parseAndVerifyNotify(anyString())).thenReturn(wechatNotifyData());
        when(orderService.settlePaid("ORD1", "wechat", "TXN1")).thenReturn(
                new SettlePaidResult(SettlePaidResult.Outcome.ALREADY_PAID, paid, "TXN1", false));

        String reply = service.handleWechatNotify("<xml/>");

        assertTrue(reply.contains("<return_code><![CDATA[SUCCESS]]></return_code>"));
        verify(conflictService, never()).record(any(), anyString(), anyString(), any(),
                anyString(), anyString());
    }

    @Test
    void secondChargeOnPaidOrder_writesDuplicateTxnConflict() {
        Order paid = order(1, 0);
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        when(wechat.parseAndVerifyNotify(anyString())).thenReturn(wechatNotifyData());
        when(orderService.settlePaid("ORD1", "wechat", "TXN1")).thenReturn(
                new SettlePaidResult(SettlePaidResult.Outcome.ALREADY_PAID, paid, "TXN1", true));
        when(conflictService.record(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        service.handleWechatNotify("<xml/>");

        verify(conflictService).record(any(), eq("wechat"), eq("TXN1"), any(),
                eq(PaymentConflictService.TYPE_DUPLICATE_TXN), eq("wechat_notify"));
    }

    @Test
    void queryPathAlsoRecordsConflictWhenChannelReportsPaid() {
        props.setMock(false);
        Order open = order(0, 0);
        open.setPayChannel("wechat");
        when(orderMapper.selectById("ORD1")).thenReturn(open);
        when(wechat.query("ORD1")).thenReturn(Map.of("trade_state", "SUCCESS", "transaction_id", "TXN1"));
        when(orderService.settlePaid("ORD1", "wechat", "TXN1")).thenReturn(conflict(open, "TXN1"));
        when(conflictService.record(any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        service.query("ORD1", 1L, false);

        verify(conflictService).record(any(), eq("wechat"), eq("TXN1"), any(),
                eq(PaymentConflictService.TYPE_LATE_PAY_AFTER_CLOSE), eq("wechat_query"));
    }

    // ===== 2. 支付入口门禁 =====

    @Test
    void createRejectsClosedOrderWithGenericMessageAndNoSideEffect() {
        Order closed = order(0, 3);
        when(orderMapper.selectById("ORD1")).thenReturn(closed);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("ORD1", 1L, "mock"));

        assertTrue(ex.getMessage().contains("不可支付"), "门禁文案应通用: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("关闭") || ex.getMessage().contains("取消")
                || ex.getMessage().contains("退款"), "不得泄露订单状态机细节: " + ex.getMessage());
        verify(orderService, never()).setPrepay(anyString(), anyString(), anyString());
    }

    @Test
    void createRejectsNearlyOverdueOrderBeforeCreatingChannelOrder() {
        Order aging = order(0, 0);
        aging.setCreateTime(LocalDateTime.now().minusMinutes(28)); // 30 分钟关单 - 5 分钟安全边界
        when(orderMapper.selectById("ORD1")).thenReturn(aging);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create("ORD1", 1L, "mock"));

        assertTrue(ex.getMessage().contains("支付窗口"));
        verify(orderService, never()).setPrepay(anyString(), anyString(), anyString());
    }

    @Test
    void createDoesNotMisjudgeTermOrderCreditOccupiedStatus() {
        // 账期单 pay_status=0 语义是"占用授信"，不是未支付；门禁只读 order_status，不得误伤
        Order term = order(0, 0);
        term.setPayType("term");
        term.setCreateTime(LocalDateTime.now().minusHours(2)); // 账期单不参与超时关单/时间闸
        when(orderMapper.selectById("ORD1")).thenReturn(term);

        PayCreateVO vo = service.create("ORD1", 1L, "mock");

        assertEquals("mock", vo.getChannel());
        verify(orderService).setPrepay("ORD1", "mock", null);
    }

    // ===== 3. 渠道单过期时间 =====

    @Test
    void wechatCreatePassesTimeExpireAlignedWithLocalCloseDeadline() {
        props.setMock(false);
        when(wechat.configured()).thenReturn(true);
        Order o = order(0, 0);
        o.setCreateTime(LocalDateTime.now().minusMinutes(10));
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(wechat.nativeOrder(eq("ORD1"), any(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn("weixin://wxpay/bizpayurl?pr=test");

        service.create("ORD1", 1L, "wechat");

        ArgumentCaptor<LocalDateTime> expire = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(wechat).nativeOrder(eq("ORD1"), any(), anyString(), anyString(), expire.capture());
        assertEquals(o.getCreateTime().plusMinutes(30), expire.getValue(),
                "渠道过期时间必须与本地关单 deadline 同源（createTime + auto-close-minutes）");
    }

    @Test
    void alipayCreatePassesRemainingTimeoutExpressMinutes() {
        props.setMock(false);
        when(alipay.configured()).thenReturn(true);
        Order o = order(0, 0);
        o.setCreateTime(LocalDateTime.now().minusMinutes(10));
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(alipay.pagePayForm(eq("ORD1"), any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("<form/>");

        service.create("ORD1", 1L, "alipay");

        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(alipay).pagePayForm(eq("ORD1"), any(), anyString(), anyString(), anyString(), timeout.capture());
        assertTrue(timeout.getValue() >= 18 && timeout.getValue() <= 20,
                "剩余时间约 20 分钟，实际: " + timeout.getValue());
    }

    // ===== 4. 冲突退款通道：只退渠道、不动订单 =====

    @Test
    void refundForConflictMockChannelRefundsWithoutTouchingOrder() {
        PaymentConflict c = new PaymentConflict();
        c.setConflictId(1L);
        c.setOrderNo("ORD1");
        c.setChannel("mock");
        c.setTransactionId("MOCKTXN1");
        c.setAmount(new BigDecimal("88.80"));
        c.setOutRefundNo("CR1");

        PaymentConflictService.RefundAttempt attempt = service.refundForConflict(c);

        assertTrue(attempt.ok());
        // 冲突退款绝不能走订单退款回写/全额退款结算（会二次回补库存、积分双退）
        verify(orderMapper, never()).updateById(any(Order.class));
        verify(orderService, never()).applyRefundResult(anyString(), any(), any(), anyBoolean());
        verify(orderService, never()).fullRefundSettle(any());
    }

    @Test
    void refundForConflictWechatReusesChannelTotalsAndRefundNo() {
        PaymentConflict c = new PaymentConflict();
        c.setOrderNo("ORD1");
        c.setChannel("wechat");
        c.setTransactionId("TXN1");
        c.setAmount(new BigDecimal("88.80"));
        c.setOutRefundNo("CR1");
        when(wechat.refund(eq("ORD1"), eq("CR1"), any(), any(), anyString()))
                .thenReturn(Map.of("result_code", "SUCCESS", "refund_id", "R1"));

        PaymentConflictService.RefundAttempt attempt = service.refundForConflict(c);

        assertTrue(attempt.ok());
        assertEquals("R1", attempt.refundId());
        verify(wechat).refund(eq("ORD1"), eq("CR1"), any(), any(), anyString());
        verify(orderService, never()).fullRefundSettle(any());
    }
}

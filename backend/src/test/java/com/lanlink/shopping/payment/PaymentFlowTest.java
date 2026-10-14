package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.PaymentProperties;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.vo.PayStatusVO;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 支付流程单测：钱包渠道支付/并发幂等回滚、mock 渠道、退款（全额回退/部分退款/状态校验）、交易记录
 */
class PaymentFlowTest {

    private PaymentProperties props;
    private WechatPayClient wechat;
    private AlipayPayClient alipay;
    private OrderService orderService;
    private OrderMapper orderMapper;
    private PaymentLogService payLog;
    private WalletService walletService;
    private PaymentService service;

    private Order order(int payStatus, String channel, BigDecimal total, BigDecimal refunded) {
        Order o = new Order();
        o.setOrderNo("NO1");
        o.setUserId(2L);
        o.setPayStatus(payStatus);
        o.setPayChannel(channel);
        o.setTotalAmount(total);
        o.setRefundAmount(refunded);
        o.setOrderStatus(0);
        o.setPayType("corporate");
        return o;
    }

    private WalletLog wl(long id) {
        WalletLog l = new WalletLog();
        l.setId(id);
        l.setUserId(2L);
        return l;
    }

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        props.setMock(true); // 支付流程单测显式开启模拟支付，不依赖生产默认值
        wechat = mock(WechatPayClient.class);
        alipay = mock(AlipayPayClient.class);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        payLog = mock(PaymentLogService.class);
        walletService = mock(WalletService.class);
        service = new PaymentService(props, wechat, alipay, orderService, orderMapper, payLog, walletService);
    }

    // ===== 发起支付：wallet 渠道 =====

    @Test
    void walletChannelPaysAndSettles() {
        when(orderMapper.selectById("NO1")).thenReturn(order(0, null, new BigDecimal("88.00"), null));
        when(walletService.payFromWallet(eq(2L), eq(new BigDecimal("88.00")), eq("NO1"))).thenReturn(wl(9L));
        when(orderService.settlePaid("NO1", "wallet", "W9")).thenReturn(true);
        when(walletService.balanceOf(2L)).thenReturn(new BigDecimal("12.00"));

        PayCreateVO vo = service.create("NO1", 2L, "wallet");
        assertEquals("wallet", vo.getChannel());
        assertEquals(Boolean.TRUE, vo.getPayInfo().get("paid"));
        assertEquals(0, new BigDecimal("12.00").compareTo((BigDecimal) vo.getPayInfo().get("balance")));
        verify(orderService).settlePaid("NO1", "wallet", "W9");
    }

    @Test
    void walletChannelRollsBackWhenAlreadySettled() {
        when(orderMapper.selectById("NO1")).thenReturn(order(0, null, new BigDecimal("88.00"), null));
        when(walletService.payFromWallet(any(), any(), anyString())).thenReturn(wl(9L));
        // 并发下订单已被其他请求结算 → 本次扣款必须回滚（事务由 @Transactional 保证，此处验证抛出）
        when(orderService.settlePaid(anyString(), anyString(), anyString())).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.create("NO1", 2L, "wallet"));
    }

    @Test
    void createRejectsAlreadyPaidOrder() {
        when(orderMapper.selectById("NO1")).thenReturn(order(1, "wallet", new BigDecimal("88.00"), null));
        assertThrows(BusinessException.class, () -> service.create("NO1", 2L, "wallet"));
        verifyNoInteractions(walletService);
    }

    @Test
    void paymentMockIsDisabledByDefault() {
        assertFalse(new PaymentProperties().isMock());
    }

    @Test
    void mockModeUsesMockChannelWhenEnabled() {
        when(orderMapper.selectById("NO1")).thenReturn(order(0, null, new BigDecimal("10.00"), null));
        PayCreateVO vo = service.create("NO1", 2L, null);
        assertEquals("mock", vo.getChannel());
        assertTrue(vo.isMock());
        verify(orderService).setPrepay("NO1", "mock", null);
    }

    // ===== 退款 =====

    @Test
    void fullRefundWalletChannelSettlesEverything() {
        when(orderMapper.selectById("NO1")).thenReturn(order(1, "wallet", new BigDecimal("100.00"), null));
        when(walletService.refundToWallet(eq(2L), eq(new BigDecimal("100.00")), eq("NO1"), anyString()))
                .thenReturn(wl(20L));

        RefundRequest req = new RefundRequest();
        req.setOrderNo("NO1");
        req.setReason("测试全额退款");
        boolean ok = service.refund(req, 3L);

        assertTrue(ok);
        verify(walletService).refundToWallet(eq(2L), eq(new BigDecimal("100.00")), eq("NO1"), anyString());
        verify(orderService).applyRefundResult(eq("NO1"), eq("W20"), eq(new BigDecimal("100.00")), eq(true));
        // 全额退款触发回退结算（库存/积分/订单状态）
        verify(orderService).fullRefundSettle(any(Order.class));
    }

    @Test
    void partialRefundSkipsFullSettle() {
        when(orderMapper.selectById("NO1")).thenReturn(order(1, "wallet", new BigDecimal("100.00"), null));
        when(walletService.refundToWallet(any(), any(), anyString(), anyString())).thenReturn(wl(21L));

        RefundRequest req = new RefundRequest();
        req.setOrderNo("NO1");
        req.setAmount(new BigDecimal("30.00"));
        assertTrue(service.refund(req, 3L));
        verify(orderService).applyRefundResult(eq("NO1"), eq("W21"), eq(new BigDecimal("30.00")), eq(true));
        // 部分退款不恢复库存/不回退积分
        verify(orderService, never()).fullRefundSettle(any());
    }

    @Test
    void refundRejectsUnpaidOrder() {
        when(orderMapper.selectById("NO1")).thenReturn(order(0, null, new BigDecimal("10.00"), null));
        RefundRequest req = new RefundRequest();
        req.setOrderNo("NO1");
        assertThrows(BusinessException.class, () -> service.refund(req, 3L));
        verifyNoInteractions(walletService);
    }

    @Test
    void refundRejectsTwiceFullRefund() {
        // 已全额退款（refund_status=success 由 applyRefundResult 写入，这里模拟 refundAmount 已达总额）
        when(orderMapper.selectById("NO1")).thenReturn(order(1, "wallet", new BigDecimal("100.00"), new BigDecimal("100.00")));
        RefundRequest req = new RefundRequest();
        req.setOrderNo("NO1");
        assertThrows(BusinessException.class, () -> service.refund(req, 3L));
    }

    // ===== 交易记录 =====

    @Test
    void myPaymentsMapsOrdersToVO() {
        Order a = order(1, "wallet", new BigDecimal("50.00"), null);
        a.setTransactionId("W9");
        a.setRefundAmount(new BigDecimal("20.00"));
        when(orderMapper.selectList(any())).thenReturn(List.of(a));

        List<PayStatusVO> list = service.myPayments(2L);
        assertEquals(1, list.size());
        PayStatusVO vo = list.get(0);
        assertEquals("NO1", vo.getOrderNo());
        assertEquals("wallet", vo.getPayChannel());
        assertEquals("W9", vo.getTransactionId());
        assertEquals(0, new BigDecimal("20.00").compareTo(vo.getRefundAmount()));
    }

    @Test
    void mockConfirmSettlesOnce() {
        when(orderMapper.selectById("NO1")).thenReturn(order(0, "mock", new BigDecimal("10.00"), null));
        when(orderService.settlePaid(eq("NO1"), eq("mock"), anyString())).thenReturn(true);
        assertTrue(service.mockConfirm("NO1", 2L));
        verify(orderService, times(1)).settlePaid(eq("NO1"), eq("mock"), anyString());
    }
}

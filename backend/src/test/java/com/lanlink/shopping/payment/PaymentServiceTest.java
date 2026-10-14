package com.lanlink.shopping.payment;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 支付门面在 mock 模式下的路由与幂等结算（不触网、不连库） */
class PaymentServiceTest {

    private PaymentProperties props;
    private WechatPayClient wechat;
    private AlipayPayClient alipay;
    private OrderService orderService;
    private OrderMapper orderMapper;
    private PaymentLogService payLog;
    private WalletService walletService;
    private PaymentService svc;

    @BeforeEach
    void setUp() {
        props = new PaymentProperties();
        props.setMock(true);
        wechat = mock(WechatPayClient.class);
        alipay = mock(AlipayPayClient.class);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        payLog = mock(PaymentLogService.class);
        walletService = mock(WalletService.class);
        svc = new PaymentService(props, wechat, alipay, orderService, orderMapper, payLog, walletService);
    }

    private Order unpaidOrder() {
        Order o = new Order();
        o.setOrderNo("20261004123456");
        o.setUserId(1L);
        o.setTotalAmount(new BigDecimal("88.80"));
        o.setPayStatus(0);
        o.setOrderStatus(0);
        return o;
    }

    @Test
    void createReturnsMockChannelWhenMockOn() {
        when(orderMapper.selectById("20261004123456")).thenReturn(unpaidOrder());

        PayCreateVO vo = svc.create("20261004123456", 1L, "wechat"); // 即便请求 wechat，mock 开也走 mock
        assertTrue(vo.isMock());
        assertEquals("mock", vo.getChannel());
        assertNotNull(vo.getPayInfo().get("token"));
        verify(orderService).setPrepay("20261004123456", "mock", null);
    }

    @Test
    void alipayNotifyWithBadSignReturnsFailure() {
        when(alipay.verifyNotify(anyMap())).thenReturn(false);
        Map<String, String> params = new HashMap<>();
        params.put("out_trade_no", "X1");
        params.put("trade_status", "TRADE_SUCCESS");
        assertEquals("failure", svc.handleAlipayNotify(params));
        verify(orderService, never()).settlePaid(anyString(), anyString(), any());
    }

    @Test
    void refundInMockMarksSuccess() {
        Order paid = unpaidOrder();
        paid.setPayStatus(1);
        paid.setPayChannel("mock");
        when(orderMapper.selectById("20261004123456")).thenReturn(paid);

        RefundRequest req = new RefundRequest();
        req.setOrderNo("20261004123456");
        req.setReason("测试退款");

        boolean ok = svc.refund(req, 9L);
        assertTrue(ok);
        verify(orderService).applyRefundResult(eq("20261004123456"), anyString(),
                eq(new BigDecimal("88.80")), eq(true));
    }

    @Test
    void createRejectsAlreadyPaid() {
        Order paid = unpaidOrder();
        paid.setPayStatus(1);
        when(orderMapper.selectById("20261004123456")).thenReturn(paid);
        assertThrows(RuntimeException.class, () -> svc.create("20261004123456", 1L, "mock"));
    }

    @Test
    void queryRejectsNonOwnerWhenNotAdmin() {
        when(orderMapper.selectById("20261004123456")).thenReturn(unpaidOrder());
        assertThrows(com.lanlink.shopping.common.BusinessException.class,
                () -> svc.query("20261004123456", 999L, false));
    }

    @Test
    void queryAllowsAdminAccess() {
        when(orderMapper.selectById("20261004123456")).thenReturn(unpaidOrder());
        var vo = svc.query("20261004123456", 999L, true);
        assertNotNull(vo);
        assertEquals("20261004123456", vo.getOrderNo());
    }
}

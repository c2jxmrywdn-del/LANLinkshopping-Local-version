package com.lanlink.shopping.payment;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 微信支付回调金额匹配回归测试。
 * 结论性验证：total_fee(分) 与订单 total_amount(元) 的比较已在 amountMatches 中
 * 通过"元→分"换算后按整数分比对，单位一致、无精度丢失。以下用例覆盖整数/两位小数/边界/不匹配。
 */
class PaymentAmountMatchTest {

    private PaymentService svc;
    private WechatPayClient wechat;
    private OrderService orderService;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        PaymentProperties props = new PaymentProperties();
        props.setMock(false);
        wechat = mock(WechatPayClient.class);
        AlipayPayClient alipay = mock(AlipayPayClient.class);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        PaymentLogService payLog = mock(PaymentLogService.class);
        WalletService wallet = mock(WalletService.class);
        svc = new PaymentService(props, wechat, alipay, orderService, orderMapper, payLog, wallet);
    }

    private void stub(String orderYuan, String totalFeeFen) {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(1L);
        o.setTotalAmount(new BigDecimal(orderYuan));
        o.setPayStatus(0);
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        Map<String, String> data = new HashMap<>();
        data.put("return_code", "SUCCESS");
        data.put("result_code", "SUCCESS");
        data.put("out_trade_no", "ORD1");
        data.put("total_fee", totalFeeFen);
        data.put("transaction_id", "TXN1");
        when(wechat.parseAndVerifyNotify(anyString())).thenReturn(data);
    }

    private void assertMatched(String yuan, String fen) {
        stub(yuan, fen);
        when(orderService.settlePaid("ORD1", "wechat", "TXN1")).thenReturn(true);
        String reply = svc.handleWechatNotify("<xml/>");
        assertTrue(reply.contains("SUCCESS"), "金额应匹配: 订单" + yuan + "元 vs total_fee=" + fen + "分");
        verify(orderService).settlePaid("ORD1", "wechat", "TXN1");
    }

    @Test void integerYuan() { assertMatched("100.00", "10000"); }
    @Test void twoDecimalYuan() { assertMatched("88.80", "8880"); }
    @Test void oneCentBoundary() { assertMatched("0.01", "1"); }
    @Test void largeAmountBoundary() { assertMatched("99999.99", "9999999"); }

    @Test void mismatchRejectsSettlement() {
        stub("88.80", "8879"); // 少 1 分
        String reply = svc.handleWechatNotify("<xml/>");
        assertTrue(reply.contains("FAIL"), "金额不匹配应回 FAIL");
        verify(orderService, never()).settlePaid(anyString(), anyString(), any());
    }

    @Test void wrongUnitMagnitudeRejects() {
        stub("88.80", "88800"); // 误当作 元×1000
        String reply = svc.handleWechatNotify("<xml/>");
        assertTrue(reply.contains("FAIL"));
        verify(orderService, never()).settlePaid(anyString(), anyString(), any());
    }
}

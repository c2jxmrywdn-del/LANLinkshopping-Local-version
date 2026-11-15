package com.lanlink.shopping.payment;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.payment.alipay.AlipayPayClient;
import com.lanlink.shopping.payment.service.PaymentLogService;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.stripe.StripePayClient;
import com.lanlink.shopping.payment.wechat.WechatPayClient;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import com.lanlink.shopping.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 退款看门狗金额口径回归（R16）。
 *
 * <p>缺陷形态：{@code syncRefundStatus} 对 wallet/mock/term/corporate 渠道无条件
 * {@code confirmed = true}，然后用 {@code o.getTotalAmount()} 回写并总是调用
 * {@code fullRefundSettle}。一笔 100 元订单只退 30 元时，看门狗会把它改写成
 * 「已退 100 元 + 全额退款」，连带全量回补库存、扣回支付积分、退回优惠券。
 *
 * <p>修复后：金额取订单上已记录的累计退款额，且只有 {@code applyRefundResult}
 * 判为 success（累计达可退上限）时才调用 {@code fullRefundSettle}。
 */
class RefundSyncAmountTest {

    private PaymentService svc;
    private OrderService orderService;
    private OrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        PaymentProperties props = new PaymentProperties();
        props.setMock(false);
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        svc = new PaymentService(props, mock(WechatPayClient.class), mock(AlipayPayClient.class),
                mock(StripePayClient.class), orderService, orderMapper,
                mock(PaymentLogService.class), mock(WalletService.class));
    }

    private Order order(String total, String goods, String alreadyRefunded) {
        Order o = new Order();
        o.setOrderNo("ORD-1");
        o.setUserId(1L);
        o.setTotalAmount(new BigDecimal(total));
        o.setGoodsAmount(new BigDecimal(goods));
        o.setFreightAmount(BigDecimal.ZERO);
        o.setRefundAmount(alreadyRefunded == null ? null : new BigDecimal(alreadyRefunded));
        o.setRefundStatus("processing");
        o.setPayStatus(1);
        o.setPayChannel("wallet");
        o.setOrderStatus(0);
        return o;
    }

    @Test
    @DisplayName("部分退款补偿：不得升级为全额退款，不触发库存/积分回滚")
    void partialRefundIsNotEscalatedToFull() {
        Order o = order("100.00", "100.00", "30.00");
        when(orderMapper.selectById("ORD-1")).thenReturn(o);
        // applyRefundResult 是真实口径的替身：部分退款保持 partial
        Order after = order("100.00", "100.00", "30.00");
        after.setRefundStatus("partial");
        when(orderMapper.selectById("ORD-1")).thenReturn(o, after);

        boolean ok = svc.syncRefundStatus("ORD-1");

        // 回写金额必须是已退的 30 元，而不是订单总额 100 元
        verify(orderService).applyRefundResult(eq("ORD-1"), anyString(), eq(new BigDecimal("30.00")), eq(true));
        verify(orderService, never()).fullRefundSettle(any());
        org.junit.jupiter.api.Assertions.assertTrue(ok);
    }

    @Test
    @DisplayName("全额退款补偿：累计已达可退总额时才执行全额结算")
    void fullRefundTriggersSettle() {
        Order o = order("100.00", "100.00", "100.00");
        Order after = order("100.00", "100.00", "100.00");
        after.setRefundStatus("success");
        when(orderMapper.selectById("ORD-1")).thenReturn(o, after);

        svc.syncRefundStatus("ORD-1");

        verify(orderService).fullRefundSettle(any());
    }

    @Test
    @DisplayName("未进入 processing 的订单不参与补偿")
    void skipsNonProcessingOrder() {
        Order o = order("100.00", "100.00", "0");
        o.setRefundStatus("merchant_approved");
        when(orderMapper.selectById("ORD-1")).thenReturn(o);

        org.junit.jupiter.api.Assertions.assertFalse(svc.syncRefundStatus("ORD-1"));
        verifyNoInteractions(orderService);
    }
}

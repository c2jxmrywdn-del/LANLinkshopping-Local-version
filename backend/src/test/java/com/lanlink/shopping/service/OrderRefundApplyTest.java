package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 退款申请闭环单测：用户申请（归属/支付状态/重复申请/原因必填）与平台驳回。
 */
class OrderRefundApplyTest {

    private OrderMapper orderMapper;
    private MessageService messageService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        messageService = mock(MessageService.class);
        orderService = new OrderService(
                mock(CartMapper.class), mock(ProductMapper.class), orderMapper, mock(OrderItemMapper.class),
                mock(UserMapper.class), messageService, mock(IdentityService.class), mock(AuditService.class),
                mock(PricingFacade.class), mock(ApplicationEventPublisher.class),
                mock(MembershipService.class), mock(CreditTermService.class),
                mock(IdempotentService.class), mock(TamperGuardService.class));
    }

    private Order paidOrder() {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(7L);
        o.setTotalAmount(new BigDecimal("2000.00"));
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setPayType("corporate");
        o.setPayChannel("mock");
        o.setRefundStatus("none");
        return o;
    }

    @Test
    void applyRefundMarksAppliedAndNotifies() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        when(orderMapper.applyRefundAtomic("ORD1", "商品缺货")).thenReturn(1);
        orderService.applyRefund(7L, "ORD1", "商品缺货");
        verify(orderMapper).applyRefundAtomic("ORD1", "商品缺货");
        verify(messageService).send(eq(7L), eq("order"), any(), any(), eq("ORD1"));
    }

    @Test
    void applyRefundConcurrentDuplicateRejected() {
        // 需求 2 幂等/并发：原子流转返回 0（并发抢先提交/状态已被并发变更）→ 拒绝并报错
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        when(orderMapper.applyRefundAtomic("ORD1", "并发请求")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "并发请求"));
        assertTrue(ex.getMessage().contains("重复"));
    }

    @Test
    void applyRefundRejectedForNonOwner() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        assertThrows(BusinessException.class, () -> orderService.applyRefund(99L, "ORD1", "不想要了"));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void applyRefundRejectedWhenUnpaid() {
        Order o = paidOrder();
        o.setPayStatus(0);
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "不想要了"));
        assertTrue(ex.getMessage().contains("已支付"));
    }

    @Test
    void applyRefundRejectedWhenAlreadyApplied() {
        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "重复申请"));
        assertTrue(ex.getMessage().contains("等待平台处理"));
    }

    @Test
    void applyRefundRejectedWhenReasonBlank() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "   "));
        assertTrue(ex.getMessage().contains("退款原因"));
    }

    @Test
    void applyRefundRejectedForTermOrder() {
        Order o = paidOrder();
        o.setPayType("term");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(7L, "ORD1", "不想要了"));
        assertTrue(ex.getMessage().contains("账期"));
    }

    @Test
    void rejectRefundMarksRejectedWithReason() {
        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.rejectRefundAtomic("ORD1", "已发货不支持退款")).thenReturn(1);
        orderService.rejectRefund("ORD1", "已发货不支持退款");
        // 需求 2：驳回走原子条件更新（仅 applied 可置 rejected），不再读-改-写覆盖
        verify(orderMapper).rejectRefundAtomic("ORD1", "已发货不支持退款");
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void rejectRefundConcurrentRaceRejected() {
        // 并发竞态：另一个并行操作已抢先变更状态（原子更新返回 0）→ 拒绝且不覆盖其结果
        Order o = paidOrder();
        o.setRefundStatus("applied");
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.rejectRefundAtomic("ORD1", "并发驳回")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund("ORD1", "并发驳回"));
        assertTrue(ex.getMessage().contains("状态已变更"));
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void rejectRefundRequiresPendingApplication() {
        when(orderMapper.selectById("ORD1")).thenReturn(paidOrder()); // refundStatus=none
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.rejectRefund("ORD1", null));
        assertTrue(ex.getMessage().contains("没有待处理"));
    }

    @Test
    void payAtomicSuccessAndConcurrentRaceRejected() {
        Order o = paidOrder();
        o.setPayStatus(0);
        when(orderMapper.selectById("ORD1")).thenReturn(o);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(1);
        Order paid = orderService.pay(7L, "ORD1");
        assertNotNull(paid);
        verify(orderMapper).markPaidAtomic("ORD1");

        // 并发竞态：已被抢先支付或状态不允许（原子返回 0）
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(7L, "ORD1"));
        assertTrue(ex.getMessage().contains("已支付或当前状态不允许支付"));
    }
}

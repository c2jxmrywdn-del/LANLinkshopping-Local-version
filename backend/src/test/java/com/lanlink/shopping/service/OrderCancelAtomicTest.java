package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 取消订单原子流转单测（审计 B1）：cancel 加 @Transactional + cancelAtomic 条件更新
 * （口径同 markPaidAtomic 等既有原子先例），消除"先读后改"竞态：
 *  - 失败路径（影响行数=0）抛"订单状态已变化"，无任何副作用（恢复库存/返积分/退券/发消息）；
 *  - 成功路径副作用齐全且不再整实体写回（updateById 移除）；
 *  - 已取消订单幂等直达返回。
 */
class OrderCancelAtomicTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private MessageService messageService;
    private MembershipService membershipService;
    private com.lanlink.shopping.module.campaign.service.CampaignService campaignService;
    private StockService stockService;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        messageService = mock(MessageService.class);
        membershipService = mock(MembershipService.class);
        campaignService = mock(com.lanlink.shopping.module.campaign.service.CampaignService.class);
        stockService = mock(StockService.class);
        orderService = new OrderService(
                mock(CartMapper.class), productMapper, orderMapper, orderItemMapper,
                mock(UserMapper.class), messageService, mock(IdentityService.class), mock(AuditService.class),
                mock(PricingFacade.class), mock(ApplicationEventPublisher.class),
                membershipService, mock(CreditTermService.class),
                mock(IdempotentService.class), mock(TamperGuardService.class),
                campaignService,
                mock(com.lanlink.shopping.module.risk.service.RiskEventService.class));
        // P1-3：库存回补统一走 StockService（幂等），单测注入 mock 以断言委托参数
        orderService.setStockService(stockService);
    }

    private Order pendingOrder() {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(7L);
        o.setTotalAmount(new BigDecimal("100.00"));
        o.setPayStatus(0);
        o.setOrderStatus(0);
        o.setPayType("corporate");
        o.setCouponDiscount(new BigDecimal("10.00"));
        return o;
    }

    @Test
    void cancelFailsWhenAtomicTransitionLosesRace() {
        // B1 失败路径：读到未取消，但原子流转时已被并发取消/状态已变化（影响行数=0）→ 拒绝
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.cancelAtomic("ORD1")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.cancel(7L, "ORD1"));
        assertTrue(ex.getMessage().contains("订单状态已变化"));
        // 事务回滚后等价于未发生：不得有任何副作用
        verify(orderMapper).cancelAtomic("ORD1");
        verify(orderItemMapper, never()).selectList(any());
        verify(productMapper, never()).restoreStockAtomic(any(), any());
        verify(stockService, never()).restoreByOrder(any(), any(), any(), any(), any());
        verify(membershipService, never()).refundPoints(any(), any());
        verifyNoInteractions(campaignService);
        verifyNoInteractions(messageService);
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void cancelRunsSideEffectsInTransactionOnSuccess() {
        // B1 成功路径：原子流转 1 行 → 恢复库存/返积分回退/优惠券回退/消息，同事务完成
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.cancelAtomic("ORD1")).thenReturn(1);
        OrderItem item = new OrderItem();
        item.setOrderNo("ORD1");
        item.setProdId(101L);
        item.setQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));
        when(membershipService.refundPoints(7L, "ORD1")).thenReturn(0);

        assertDoesNotThrow(() -> orderService.cancel(7L, "ORD1"));

        verify(orderMapper).cancelAtomic("ORD1");
        // P1-3：库存回补委托 StockService（幂等），bizType=ORDER_CANCEL / bizNo=orderNo / orderNo 同源
        verify(stockService).restoreByOrder(
                eq(StockService.BIZ_ORDER_CANCEL), eq("ORD1"), eq("ORD1"), eq(7L), any());
        verify(productMapper, never()).restoreStockAtomic(any(), any());
        verify(campaignService).releaseCoupon("ORD1");
        // 不再整实体写回：取消状态由原子 UPDATE 完成
        verify(orderMapper, never()).updateById(any(Order.class));
        verify(messageService).send(eq(7L), eq("order"), eq("订单已取消"), any(), eq("ORD1"));
        verify(messageService).send(eq(7L), eq("order"), eq("优惠券已退回"), any(), eq("ORD1"));
    }

    @Test
    void cancelIsIdempotentForAlreadyCancelledOrder() {
        Order o = pendingOrder();
        o.setOrderStatus(3);
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        assertDoesNotThrow(() -> orderService.cancel(7L, "ORD1"));
        verify(orderMapper, never()).cancelAtomic(any());
        verifyNoInteractions(productMapper, campaignService, stockService);
    }

    @Test
    void cancelRejectsFullyRefundedOrder() {
        // P1-3 漏洞修复：order_status=5（已全额退款）不得再取消，否则 cancelAtomic 条件会放过它 → 二次回补库存
        Order refunded = pendingOrder();
        refunded.setOrderStatus(5);
        refunded.setPayStatus(2);
        when(orderMapper.selectById("ORD1")).thenReturn(refunded);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.cancel(7L, "ORD1"));
        assertTrue(ex.getMessage().contains("全额退款") || ex.getMessage().contains("已退款"));
        // 不得触达任何取消/回补副作用（库存/积分/券/消息）；P1-3：也不得调用幂等回补入口
        verify(orderMapper, never()).cancelAtomic(any());
        verify(stockService, never()).restoreByOrder(any(), any(), any(), any(), any());
        verifyNoInteractions(productMapper, campaignService, membershipService, messageService);
    }

    @Test
    void cancelRejectsPaidNonTermOrderAndForeignOrder() {
        Order paid = pendingOrder();
        paid.setPayStatus(1);
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        BusinessException ex1 = assertThrows(BusinessException.class, () -> orderService.cancel(7L, "ORD1"));
        assertTrue(ex1.getMessage().contains("已支付订单不可取消"));

        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        assertThrows(BusinessException.class, () -> orderService.cancel(99L, "ORD1"));
        verify(orderMapper, never()).cancelAtomic(any());
    }
}

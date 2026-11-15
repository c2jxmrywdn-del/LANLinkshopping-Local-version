package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.OrderRma;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.OrderRmaMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.StockFlowMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-3 库存回补接线（OrderService 侧）：四个释放路径必须统一委托 {@link StockService}，
 * 且「重复触发不叠加」由 t_stock_flow 唯一键兜底。
 *
 * <p>本类同时覆盖三类「改前会二次回补、改后不会」的守卫：
 * <ol>
 *   <li>已全额退款(order_status=5)的订单再取消：前置拒绝 + 不触达回补（修复库存虚增）；</li>
 *   <li>同一订单重复取消：第二次走幂等短路，库存只补一次（真实 StockService + 唯一键 mock）；</li>
 *   <li>RMA 验货已回补后全额退款结算：is_restocked=1 时跳过，避免跨路径双补。</li>
 * </ol>
 */
class OrderStockRestockWiringTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private StockFlowMapper stockFlowMapper;
    private MessageService messageService;
    private MembershipService membershipService;
    private com.lanlink.shopping.module.campaign.service.CampaignService campaignService;
    private OrderRmaMapper rmaMapper;
    private OrderService orderService;
    private StockService stockServiceMock;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        stockFlowMapper = mock(StockFlowMapper.class);
        messageService = mock(MessageService.class);
        membershipService = mock(MembershipService.class);
        campaignService = mock(com.lanlink.shopping.module.campaign.service.CampaignService.class);
        rmaMapper = mock(OrderRmaMapper.class);

        orderService = new OrderService(
                mock(CartMapper.class), productMapper, orderMapper, orderItemMapper,
                mock(UserMapper.class), messageService, mock(IdentityService.class), mock(AuditService.class),
                mock(PricingFacade.class), mock(ApplicationEventPublisher.class),
                membershipService, mock(CreditTermService.class),
                mock(IdempotentService.class), mock(TamperGuardService.class),
                campaignService,
                mock(com.lanlink.shopping.module.risk.service.RiskEventService.class));
        stockServiceMock = mock(StockService.class);
        orderService.setStockService(stockServiceMock);
        orderService.setRmaMapper(rmaMapper);

        when(membershipService.refundPoints(any(), any())).thenReturn(0);
        when(membershipService.reverseEarn(any(), any())).thenReturn(0);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
    }

    private Order pendingOrder() {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(7L);
        o.setPayType("corporate");
        o.setPayStatus(0);
        o.setOrderStatus(0);
        o.setTotalAmount(new BigDecimal("100.00"));
        return o;
    }

    private OrderItem item() {
        OrderItem it = new OrderItem();
        it.setItemId(501L);
        it.setOrderNo("ORD1");
        it.setProdId(101L);
        it.setQuantity(2);
        return it;
    }

    // ==================== 四个释放路径的委托口径 ====================

    @Test
    void cancelDelegatesToIdempotentStockService() {
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.cancelAtomic("ORD1")).thenReturn(1);

        assertDoesNotThrow(() -> orderService.cancel(7L, "ORD1"));

        verify(stockServiceMock).restoreByOrder(
                eq(StockService.BIZ_ORDER_CANCEL), eq("ORD1"), eq("ORD1"), eq(7L), any());
    }

    @Test
    void closeUnpaidDelegatesToIdempotentStockService() {
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.closeUnpaidAtomic("ORD1")).thenReturn(1);

        assertDoesNotThrow(() -> orderService.closeUnpaid("ORD1"));

        verify(stockServiceMock).restoreByOrder(
                eq(StockService.BIZ_ORDER_CLOSE), eq("ORD1"), eq("ORD1"), isNull(), any());
    }

    @Test
    void closeUnpaidRaceLossNeverRestoresStock() {
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.closeUnpaidAtomic("ORD1")).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertFalse(orderService.closeUnpaid("ORD1"));
        verify(stockServiceMock, never()).restoreByOrder(any(), any(), any(), any(), any());
    }

    @Test
    void fullRefundSettleDelegatesToIdempotentStockService() {
        Order o = pendingOrder();
        o.setPayStatus(1);
        o.setOrderStatus(2);
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        assertDoesNotThrow(() -> orderService.fullRefundSettle(o));

        verify(stockServiceMock).restoreByOrder(
                eq(StockService.BIZ_FULL_REFUND), eq("ORD1"), eq("ORD1"), isNull(), any());
    }

    // ==================== 改前会二次回补 / 改后不会 ====================

    /** 已全额退款(order_status=5)再取消：拒绝且绝不触达回补（旧实现会二次回补）。 */
    @Test
    void cancelOnFullyRefundedOrderNeverRestoresStockAgain() {
        Order refunded = pendingOrder();
        refunded.setOrderStatus(5);
        refunded.setPayStatus(2);
        when(orderMapper.selectById("ORD1")).thenReturn(refunded);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.cancel(7L, "ORD1"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("全额退款") || ex.getMessage().contains("已退款"));
        verify(orderMapper, never()).cancelAtomic(any());
        verify(stockServiceMock, never()).restoreByOrder(any(), any(), any(), any(), any());
    }

    /** 同一订单取消两次：第二次数值幂等短路，库存只回补一次（真实 StockService + INSERT IGNORE mock）。 */
    @Test
    void cancelTwiceRestoresStockOnlyOnceThroughStockFlowUniqueKey() {
        // 真实 StockService：IO 层 mock（StockFlowMapper 唯一键语义由 thenReturn(1,0) 模拟）
        StockService realStockService = new StockService(stockFlowMapper, productMapper);
        realStockService.setOrderItemMapper(orderItemMapper);
        orderService.setStockService(realStockService);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        when(stockFlowMapper.insertIgnore(any())).thenReturn(1, 0);
        when(productMapper.restoreStockAtomic(101L, 2)).thenReturn(1);

        // 第一次：正常取消并回补
        when(orderMapper.selectById("ORD1")).thenReturn(pendingOrder());
        when(orderMapper.cancelAtomic("ORD1")).thenReturn(1);
        assertDoesNotThrow(() -> orderService.cancel(7L, "ORD1"));

        // 第二次：同一订单再取消（若状态已被置 3，则幂等直达；这里用 CAS 失败模拟并发重放路径）
        Order cancelled = pendingOrder();
        cancelled.setOrderStatus(3);
        when(orderMapper.selectById("ORD1")).thenReturn(cancelled);
        assertDoesNotThrow(() -> orderService.cancel(7L, "ORD1"));

        // 库存只加一次；流水只插一次（第二次根本没走到回补入口）
        verify(productMapper, times(1)).restoreStockAtomic(101L, 2);
        verify(stockFlowMapper, times(1)).insertIgnore(any());
        verify(orderMapper, times(1)).cancelAtomic(anyString());
    }

    /** RMA 验货已回补过（is_restocked=1）时，全额退款结算不再二次回补。 */
    @Test
    void fullRefundSettleSkipsWhenRmaAlreadyRestocked() {
        Order o = pendingOrder();
        o.setPayStatus(1);
        o.setOrderStatus(2);
        OrderRma rma = new OrderRma();
        rma.setRmaNo("RMA1");
        rma.setOrderNo("ORD1");
        rma.setIsRestocked(1);
        when(rmaMapper.findLatestByOrderNo("ORD1")).thenReturn(rma);
        when(orderMapper.selectById("ORD1")).thenReturn(o);

        assertDoesNotThrow(() -> orderService.fullRefundSettle(o));

        verify(stockServiceMock, never()).restoreByOrder(any(), any(), any(), any(), any());
        verify(stockServiceMock, never()).restore(any(), any(), anyLong(), anyLong(), anyInt(),
                any(), any(), any());
    }
}

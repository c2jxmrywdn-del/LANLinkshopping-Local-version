package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.LogisticsWaybillItem;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillItemMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 工业品多包裹拆单发货与全景 GIS 路线测试
 */
class LogisticsSplitShipTest {

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsTraceMapper traceMapper;
    private LogisticsAlertMapper alertMapper;
    private LogisticsProviderMapper providerMapper;
    private LogisticsTemplateMapper templateMapper;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private LogisticsWaybillItemMapper waybillItemMapper;
    private MessageService messageService;
    private AuditService auditService;
    private SequenceService sequenceService;
    private LogisticsWaybillService service;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        traceMapper = mock(LogisticsTraceMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        providerMapper = mock(LogisticsProviderMapper.class);
        templateMapper = mock(LogisticsTemplateMapper.class);
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        waybillItemMapper = mock(LogisticsWaybillItemMapper.class);
        messageService = mock(MessageService.class);
        auditService = mock(AuditService.class);
        sequenceService = mock(SequenceService.class);

        when(sequenceService.nextNo(anyString(), anyInt())).thenReturn("0001");

        service = new LogisticsWaybillService(
                waybillMapper, traceMapper, alertMapper, providerMapper, templateMapper,
                orderMapper, new LogisticsFreightCalculator(), new LogisticsTraceParser(),
                messageService, auditService, sequenceService,
                orderItemMapper, waybillItemMapper
        );
    }

    private Order createPaidOrder(String orderNo) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setUserId(88L);
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setTotalAmount(new BigDecimal("12800"));
        o.setReceiver("李工");
        o.setPhone("13900001111");
        o.setAddress("江苏省苏州市工业园区星湖街328号");
        return o;
    }

    private LogisticsProvider createProvider(Long id) {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(id);
        p.setProviderName("德邦精准快运");
        p.setProviderCode("DEPPON");
        p.setStatus(1);
        return p;
    }

    @Test
    @DisplayName("测试拆单发货：第一包裹部分发货流转至状态 4，第二包裹全部发完流转至状态 1")
    void testSplitShipPartialAndFull() {
        String orderNo = "ORD20261105001";
        Order order = createPaidOrder(orderNo);
        when(orderMapper.selectById(orderNo)).thenReturn(order);
        when(providerMapper.selectById(10L)).thenReturn(createProvider(10L));

        // 订单有两款工业品：SKU1 数量 10 件，SKU2 数量 5 件
        OrderItem oi1 = new OrderItem();
        oi1.setItemId(101L);
        oi1.setOrderNo(orderNo);
        oi1.setProdId(1001L);
        oi1.setProdName("高压伺服电机 7.5kW");
        oi1.setQuantity(10);
        oi1.setShippedQuantity(0);

        OrderItem oi2 = new OrderItem();
        oi2.setItemId(102L);
        oi2.setOrderNo(orderNo);
        oi2.setProdId(1002L);
        oi2.setProdName("工业级行星减速机 1:10");
        oi2.setQuantity(5);
        oi2.setShippedQuantity(0);

        when(orderItemMapper.selectById(101L)).thenReturn(oi1);
        when(orderItemMapper.selectById(102L)).thenReturn(oi2);

        // 第一次发货：发 SKU1 6 件 (未全发完)
        when(orderItemMapper.increaseShippedQuantityAtomic(101L, 6)).thenReturn(1);
        // 模拟已发 6 件后查询列表
        OrderItem oi1AfterPart = new OrderItem();
        oi1AfterPart.setItemId(101L);
        oi1AfterPart.setQuantity(10);
        oi1AfterPart.setShippedQuantity(6);

        when(orderItemMapper.selectList(any())).thenReturn(List.of(oi1AfterPart, oi2));

        var req1 = new LogisticsWaybillService.SplitShipRequest(
                orderNo, 10L, null, "DP001",
                new BigDecimal("120.5"), new BigDecimal("0.8"), false,
                "上海发货中心", "13811112222", "上海市西北综合物流园",
                "ltl", "PKG-01", 1, 2,
                List.of(new LogisticsWaybillService.SplitItem(101L, 6))
        );

        LogisticsWaybill wb1 = service.shipSplit(req1, 999L, null);

        assertNotNull(wb1);
        assertEquals("PKG-01", wb1.getPackageNo());
        assertEquals("ltl", wb1.getTransportMode());
        assertEquals(1, wb1.getPackageIndex());
        assertEquals(2, wb1.getPackageTotal());
        // 状态流转为 4 部分发货
        verify(orderMapper).markPartialShippedAtomic(orderNo);
        verify(waybillItemMapper).insert(any(LogisticsWaybillItem.class));

        // 第二次发货：发 SKU1 剩余 4 件 + SKU2 全部 5 件
        order.setOrderStatus(4); // 当前为部分发货
        when(orderItemMapper.increaseShippedQuantityAtomic(101L, 4)).thenReturn(1);
        when(orderItemMapper.increaseShippedQuantityAtomic(102L, 5)).thenReturn(1);

        OrderItem oi1Done = new OrderItem();
        oi1Done.setQuantity(10);
        oi1Done.setShippedQuantity(10);
        OrderItem oi2Done = new OrderItem();
        oi2Done.setQuantity(5);
        oi2Done.setShippedQuantity(5);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(oi1Done, oi2Done));

        var req2 = new LogisticsWaybillService.SplitShipRequest(
                orderNo, 10L, null, "DP002",
                new BigDecimal("85.0"), new BigDecimal("0.6"), false,
                "上海发货中心", "13811112222", "上海市西北综合物流园",
                "express", "PKG-02", 2, 2,
                List.of(
                        new LogisticsWaybillService.SplitItem(101L, 4),
                        new LogisticsWaybillService.SplitItem(102L, 5)
                )
        );

        LogisticsWaybill wb2 = service.shipSplit(req2, 999L, null);
        assertNotNull(wb2);
        assertEquals("PKG-02", wb2.getPackageNo());
        // 全部发完流转为 1 已发货
        verify(orderMapper).markFullyShippedAtomic(orderNo);
    }

    @Test
    @DisplayName("测试拆单发货防超发：CAS 行锁更新失败时抛出 BusinessException 并阻断发货")
    void testSplitShipOvershipPrevention() {
        String orderNo = "ORD20261105002";
        Order order = createPaidOrder(orderNo);
        when(orderMapper.selectById(orderNo)).thenReturn(order);
        when(providerMapper.selectById(10L)).thenReturn(createProvider(10L));

        OrderItem oi = new OrderItem();
        oi.setItemId(201L);
        oi.setOrderNo(orderNo);
        oi.setProdName("耐高温耐酸碱密封圈");
        oi.setQuantity(5);
        oi.setShippedQuantity(4);
        when(orderItemMapper.selectById(201L)).thenReturn(oi);

        // 尝试发 3 件，但数据库行锁 CAS 返回 0（超发或并发冲突）
        when(orderItemMapper.increaseShippedQuantityAtomic(201L, 3)).thenReturn(0);

        var req = new LogisticsWaybillService.SplitShipRequest(
                orderNo, 10L, null, "SF999",
                BigDecimal.ONE, BigDecimal.ONE, false,
                "苏州仓", "13800000000", "苏州市相城区",
                "express", "PKG-02", 2, 2,
                List.of(new LogisticsWaybillService.SplitItem(201L, 3))
        );

        BusinessException ex = assertThrows(BusinessException.class, () -> service.shipSplit(req, 999L, null));
        assertTrue(ex.getMessage().contains("可发货数量不足或已被并发拆发"));
        verify(waybillMapper, never()).insert(any(LogisticsWaybill.class));
    }

    @Test
    @DisplayName("测试签收闭环聚合：仅单包裹签收不结束订单，全包裹签收后联动完成订单")
    void testAggregationCompleteOrderOnlyWhenAllDelivered() {
        String orderNo = "ORD20261105003";

        LogisticsWaybill wb1 = new LogisticsWaybill();
        wb1.setWaybillId(501L);
        wb1.setWaybillNo("LL202611050001");
        wb1.setOrderNo(orderNo);
        wb1.setStatus(LogisticsStateMachine.OUT_FOR_DELIVERY);

        LogisticsWaybill wb2 = new LogisticsWaybill();
        wb2.setWaybillId(502L);
        wb2.setWaybillNo("LL202611050002");
        wb2.setOrderNo(orderNo);
        wb2.setStatus(LogisticsStateMachine.OUT_FOR_DELIVERY);

        when(waybillMapper.selectById(501L)).thenReturn(wb1);
        when(waybillMapper.selectById(502L)).thenReturn(wb2);

        // 第一次：仅 wb1 签收，wb2 仍为 in_transit
        when(waybillMapper.selectList(any())).thenReturn(List.of(wb1, wb2));
        service.transit(501L, LogisticsStateMachine.DELIVERED, "已送达一号厂区", 1L, null);

        // 验证订单未被完成！
        verify(orderMapper, never()).markCompletedAtomic(orderNo);

        // 第二次：wb2 也签收，此时全部运单均为 DELIVERED
        wb1.setStatus(LogisticsStateMachine.DELIVERED);
        when(waybillMapper.selectList(any())).thenReturn(List.of(wb1, wb2));
        OrderItem doneItem = new OrderItem();
        doneItem.setQuantity(2);
        doneItem.setShippedQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(doneItem));
        when(orderMapper.markCompletedAtomic(orderNo)).thenReturn(1);

        service.transit(502L, LogisticsStateMachine.DELIVERED, "二号厂区附件已收齐", 1L, null);

        // 验证全包裹签收后联动完成订单
        verify(orderMapper).markCompletedAtomic(orderNo);
    }

    @Test
    @DisplayName("测试 orderWaybills 全景聚合：正确返回包裹明细与 GIS 动态路线数据")
    void testOrderWaybillsAggregationAndMapRoute() {
        String orderNo = "ORD20261105004";
        Order order = createPaidOrder(orderNo);
        order.setOrderStatus(1);
        when(orderMapper.selectById(orderNo)).thenReturn(order);

        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(601L);
        wb.setWaybillNo("LL202611050009");
        wb.setOrderNo(orderNo);
        wb.setPackageNo("PKG-01");
        wb.setPackageIndex(1);
        wb.setPackageTotal(1);
        wb.setStatus(LogisticsStateMachine.IN_TRANSIT);
        wb.setSenderAddress("上海西北物流园");
        wb.setReceiverAddress("苏州市工业园区");
        wb.setProviderId(10L);

        when(waybillMapper.selectList(any())).thenReturn(List.of(wb));
        when(providerMapper.selectById(10L)).thenReturn(createProvider(10L));

        LogisticsWaybillItem item = new LogisticsWaybillItem();
        item.setProdName("数控导轨滑块 35mm");
        item.setShippedQty(4);
        when(waybillItemMapper.selectList(any())).thenReturn(List.of(item));

        LogisticsTrace trace = new LogisticsTrace();
        trace.setDescription("快件已到达【苏州工业园区中转中心】");
        trace.setCity("苏州");
        trace.setStatus(LogisticsStateMachine.IN_TRANSIT);
        when(traceMapper.selectList(any())).thenReturn(List.of(trace));

        // 买家本人 (userId=88) 访问
        var agg = service.orderWaybills(orderNo, 88L, false);

        assertNotNull(agg);
        assertEquals(orderNo, agg.orderNo());
        assertEquals("已发货", agg.orderStatusDesc());
        assertEquals(1, agg.totalWaybills());
        assertEquals(1, agg.waybills().size());

        var detail = agg.waybills().get(0);
        assertEquals("PKG-01", detail.waybill().getPackageNo());
        assertEquals("德邦精准快运", detail.providerName());
        assertEquals(1, detail.items().size());
        assertEquals("数控导轨滑块 35mm", detail.items().get(0).getProdName());

        // 验证 GIS 路线与卡车位置
        var route = detail.mapRoute();
        assertNotNull(route);
        assertNotNull(route.origin());
        assertNotNull(route.destination());
        assertNotNull(route.truckPosition());
        assertTrue(route.distanceKm() > 0);
        assertNotNull(route.truckPosition().headingAngle());
    }
}

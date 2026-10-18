package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运单服务集成测试（Mockito 模拟 Mapper）：
 * 发货校验 / 状态流转 / 异常告警幂等 / 轨迹同步。
 */
class LogisticsWaybillServiceTest {

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsTraceMapper traceMapper;
    private LogisticsAlertMapper alertMapper;
    private LogisticsProviderMapper providerMapper;
    private LogisticsTemplateMapper templateMapper;
    private OrderMapper orderMapper;
    private MessageService messageService;
    private AuditService auditService;
    private LogisticsWaybillService service;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        traceMapper = mock(LogisticsTraceMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        providerMapper = mock(LogisticsProviderMapper.class);
        templateMapper = mock(LogisticsTemplateMapper.class);
        orderMapper = mock(OrderMapper.class);
        when(orderMapper.markShippedAtomic(any())).thenReturn(1);
        messageService = mock(MessageService.class);
        auditService = mock(AuditService.class);
        service = new LogisticsWaybillService(waybillMapper, traceMapper, alertMapper,
                providerMapper, templateMapper, orderMapper,
                new LogisticsFreightCalculator(), new LogisticsTraceParser(),
                messageService, auditService, mock(SequenceService.class));
    }

    private Order paidOrder() {
        Order o = new Order();
        o.setOrderNo("LL20261016001");
        o.setUserId(1L);
        o.setPayStatus(1);
        o.setOrderStatus(0);
        o.setTotalAmount(new BigDecimal("500"));
        o.setReceiver("张三");
        o.setPhone("13800000000");
        o.setAddress("广东省广州市天河区");
        return o;
    }

    private LogisticsProvider activeProvider() {
        LogisticsProvider p = new LogisticsProvider();
        p.setProviderId(1L);
        p.setProviderName("顺丰速运");
        p.setProviderCode("SF");
        p.setStatus(1);
        return p;
    }

    private LogisticsTemplate weightTemplate() {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setTemplateId(1L);
        t.setTemplateName("顺丰标快-全国");
        t.setProviderId(1L);
        t.setPricingType("weight");
        t.setFirstWeight(new BigDecimal("1"));
        t.setFirstPrice(new BigDecimal("12"));
        t.setContinuedWeight(new BigDecimal("1"));
        t.setContinuedPrice(new BigDecimal("5"));
        t.setVolumeFactor(new BigDecimal("6000"));
        t.setFreeThreshold(BigDecimal.ZERO);
        t.setRemoteSurcharge(BigDecimal.ZERO);
        t.setStatus(1);
        return t;
    }

    @Test
    void shipRejectsUnpaidOrder() {
        Order o = paidOrder();
        o.setPayStatus(0);
        when(orderMapper.selectById("LL20261016001")).thenReturn(o);
        var req = new LogisticsWaybillService.ShipRequest("LL20261016001", 1L, 1L, "SF123",
                new BigDecimal("1"), null, false, 1, "张三", "138", "地址");
        assertThrows(BusinessException.class, () -> service.ship(req, 1L, null));
    }

    @Test
    void shipRejectsDuplicateShip() {
        Order o = paidOrder();
        o.setOrderStatus(1); // 已发货
        when(orderMapper.selectById("LL20261016001")).thenReturn(o);
        var req = new LogisticsWaybillService.ShipRequest("LL20261016001", 1L, 1L, "SF123",
                new BigDecimal("1"), null, false, 1, "张三", "138", "地址");
        assertThrows(BusinessException.class, () -> service.ship(req, 1L, null));
    }

    @Test
    void shipConcurrentDuplicateShipRejected() {
        Order o = paidOrder();
        when(orderMapper.selectById("LL20261016001")).thenReturn(o);
        when(orderMapper.markShippedAtomic("LL20261016001")).thenReturn(0); // 模拟并发已被其它线程抢先更新
        var req = new LogisticsWaybillService.ShipRequest("LL20261016001", 1L, 1L, "SF123",
                new BigDecimal("1"), null, false, 1, "张三", "138", "地址");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.ship(req, 1L, null));
        assertTrue(ex.getMessage().contains("并发发货") || ex.getMessage().contains("待发货"));
    }

    @Test
    void shipRejectsInactiveProvider() {
        when(orderMapper.selectById("LL20261016001")).thenReturn(paidOrder());
        LogisticsProvider stopped = activeProvider();
        stopped.setStatus(0);
        when(providerMapper.selectById(1L)).thenReturn(stopped);
        var req = new LogisticsWaybillService.ShipRequest("LL20261016001", 1L, 1L, "SF123",
                new BigDecimal("1"), null, false, 1, "张三", "138", "地址");
        assertThrows(BusinessException.class, () -> service.ship(req, 1L, null));
    }

    @Test
    void transitRejectsIllegalJump() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setStatus("pending_ship");
        when(waybillMapper.selectById(1L)).thenReturn(wb);
        // pending_ship → delivered 非法（跳级）
        assertThrows(BusinessException.class,
                () -> service.transit(1L, "delivered", null, 1L, null));
    }

    @Test
    void transitIdempotentSameStatus() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setStatus("in_transit");
        when(waybillMapper.selectById(1L)).thenReturn(wb);
        // 同状态幂等：不抛异常
        assertDoesNotThrow(() -> service.transit(1L, "in_transit", null, 1L, null));
    }

    @Test
    void alertIsIdempotentForSameType() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setWaybillNo("LL202610160001");
        wb.setOrderNo("LL20261016001");
        when(alertMapper.countActiveByType(1L, "reject")).thenReturn(1L); // 已有未处理告警
        boolean raised = service.raiseAlertIfNeeded(wb, "reject", "客户拒收");
        assertFalse(raised, "同类型未处理告警已存在时不应重复创建");
    }

    @Test
    void alertCreatedForNewType() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setWaybillNo("LL202610160001");
        wb.setOrderNo("LL20261016001");
        when(alertMapper.countActiveByType(1L, "lost")).thenReturn(0L);
        when(orderMapper.selectById("LL20261016001")).thenReturn(paidOrder());
        when(alertMapper.insert(any(LogisticsAlert.class))).thenReturn(1);
        boolean raised = service.raiseAlertIfNeeded(wb, "lost", "快件丢失");
        assertTrue(raised, "新类型异常应创建告警");
    }

    @Test
    void syncTraceImportsNodes() {
        LogisticsWaybill wb = new LogisticsWaybill();
        wb.setWaybillId(1L);
        wb.setWaybillNo("LL202610160001");
        wb.setStatus("picked_up");
        when(waybillMapper.selectById(1L)).thenReturn(wb);
        when(traceMapper.selectCount(any(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class))).thenReturn(0L);
        when(traceMapper.insert(any(LogisticsTrace.class))).thenReturn(1);
        when(waybillMapper.updateById(any(LogisticsWaybill.class))).thenReturn(1);
        when(orderMapper.selectById(any(String.class))).thenReturn(paidOrder());
        when(alertMapper.countActiveByType(any(Long.class), any(String.class))).thenReturn(0L);
        when(alertMapper.insert(any(LogisticsAlert.class))).thenReturn(1);
        Map<String, Object> result = service.syncTrace(1L, List.of(
                Map.of("traceTime", "2026-10-16T10:00:00", "description", "快件已揽收", "location", "广州"),
                Map.of("traceTime", "2026-10-16T12:00:00", "description", "快件已到达中转场", "location", "深圳")
        ));
        assertEquals(2, ((Number) result.get("imported")).intValue());
    }
}

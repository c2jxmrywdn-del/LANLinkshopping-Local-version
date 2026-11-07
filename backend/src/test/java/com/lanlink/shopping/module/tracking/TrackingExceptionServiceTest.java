package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.module.tracking.service.TrackingExceptionService;
import com.lanlink.shopping.module.tracking.service.TrackingTicketBridge;
import com.lanlink.shopping.support.SupportTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异常识别 + 看板 + 转工单单测（out/logistics-tracking-plan.md P2/P3 任务 4）：
 * 48h 无更新 → timeout_no_update 告警（同单同类型幂等）；签收后 24h 无更新 → 人工复核队列；
 * 转工单写 alert.ticket_id。
 */
class TrackingExceptionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0, 0);

    private LogisticsWaybillMapper waybillMapper;
    private LogisticsAlertMapper alertMapper;
    private TrackingEventMapper eventMapper;
    private TrackingSyncTaskMapper taskMapper;
    private TrackingTicketBridge ticketBridge;
    private JdbcTemplate jdbc;
    private TrackingExceptionService service;
    private LogisticsWaybill waybill;

    @BeforeEach
    void setUp() {
        waybillMapper = mock(LogisticsWaybillMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        eventMapper = mock(TrackingEventMapper.class);
        taskMapper = mock(TrackingSyncTaskMapper.class);
        ticketBridge = mock(TrackingTicketBridge.class);
        jdbc = mock(JdbcTemplate.class);
        TrackingProperties properties = new TrackingProperties();
        service = new TrackingExceptionService(waybillMapper, alertMapper, eventMapper,
                taskMapper, ticketBridge, jdbc, properties);

        waybill = new LogisticsWaybill();
        waybill.setWaybillId(77L);
        waybill.setWaybillNo("LL202611060001");
        waybill.setOrderNo("ORD-1");
        waybill.setStatus("in_transit");
        waybill.setUpdateTime(NOW.minusHours(50)); // 最后动态 50h 前
        when(eventMapper.selectList(any())).thenReturn(List.of()); // 无渠道事件
        when(waybillMapper.updateById(any(LogisticsWaybill.class))).thenReturn(1);
        when(alertMapper.insert(any(LogisticsAlert.class))).thenReturn(1);
    }

    @Test
    @DisplayName("48h 无更新 → timeout_no_update 高级别告警 + exception_flag=1")
    void staleWaybillTriggersTimeoutAlert() {
        when(waybillMapper.selectList(any())).thenReturn(List.of(waybill));
        when(alertMapper.countActiveByType(77L, "timeout_no_update")).thenReturn(0L);

        TrackingExceptionService.ExceptionSweepReport report = service.sweepExceptions(NOW);

        assertEquals(1, report.getScannedWaybills());
        assertEquals(1, report.getAlertsTriggered());
        ArgumentCaptor<LogisticsAlert> captor = ArgumentCaptor.forClass(LogisticsAlert.class);
        verify(alertMapper).insert(captor.capture());
        assertEquals("timeout_no_update", captor.getValue().getAlertType());
        assertEquals("high", captor.getValue().getAlertLevel());
        assertTrue(captor.getValue().getContent().contains("LL202611060001"));
        assertEquals(1, waybill.getExceptionFlag());
    }

    @Test
    @DisplayName("同单同类型未 resolved 告警已存在：幂等跳过不重复创建")
    void duplicateTimeoutAlertSkipped() {
        when(waybillMapper.selectList(any())).thenReturn(List.of(waybill));
        when(alertMapper.countActiveByType(77L, "timeout_no_update")).thenReturn(1L);

        TrackingExceptionService.ExceptionSweepReport report = service.sweepExceptions(NOW);

        assertEquals(1, report.getAlertsSkipped());
        assertEquals(0, report.getAlertsTriggered());
        verify(alertMapper, never()).insert(any(LogisticsAlert.class));
    }

    @Test
    @DisplayName("签收后 24h 无更新且 e-POD 未复核 → 进入人工复核队列；已复核完成的不进队列")
    void deliveredStaleWithoutPodAuditEntersReviewQueue() {
        waybill.setStatus("delivered");
        waybill.setDeliveredTime(NOW.minusHours(30));
        waybill.setPodAuditStatus("none");

        when(waybillMapper.selectList(any())).thenReturn(List.of(waybill));
        List<Map<String, Object>> queue = service.reviewQueue(NOW);

        assertEquals(1, queue.size());
        assertEquals(77L, queue.get(0).get("waybillId"));
        assertEquals("none", queue.get(0).get("podAuditStatus"));
        assertTrue(String.valueOf(queue.get(0).get("reason")).contains("人工复核"));

        waybill.setPodAuditStatus("approved");
        assertEquals(0, service.reviewQueue(NOW).size(), "e-POD 已复核完成不进复核队列");
    }

    @Test
    @DisplayName("看板「转工单」：取最新未 resolved 告警走桥接并回写 ticket_id")
    void createTicketBridgesExistingAlert() {
        LogisticsAlert alert = new LogisticsAlert();
        alert.setAlertId(5L);
        alert.setWaybillId(77L);
        alert.setAlertType("timeout_no_update");
        alert.setStatus("pending");
        when(waybillMapper.selectById(77L)).thenReturn(waybill);
        when(alertMapper.selectList(any())).thenReturn(List.of(alert));
        when(ticketBridge.bridge(any(LogisticsAlert.class), any(LogisticsWaybill.class), eq(9L)))
                .thenReturn(Map.of("alertId", 5L, "ticketId", 9L));

        Map<String, Object> result = service.createTicket(77L, 9L);

        assertEquals(5L, result.get("alertId"));
        assertEquals(9L, result.get("ticketId"));
        verify(alertMapper, never()).insert(any(LogisticsAlert.class));
    }

    @Test
    @DisplayName("无未处理告警时转工单：按运单态兜底建告警（delivered → pod_review）再桥接")
    void createTicketWithoutAlertCreatesOneFirst() {
        waybill.setStatus("delivered");
        when(waybillMapper.selectById(77L)).thenReturn(waybill);
        when(alertMapper.selectList(any())).thenReturn(List.of());
        when(ticketBridge.bridge(any(LogisticsAlert.class), any(LogisticsWaybill.class), eq(9L)))
                .thenReturn(Map.of("alertId", 1L, "ticketId", 9L));

        service.createTicket(77L, 9L);

        ArgumentCaptor<LogisticsAlert> captor = ArgumentCaptor.forClass(LogisticsAlert.class);
        verify(alertMapper).insert(captor.capture());
        assertEquals("pod_review", captor.getValue().getAlertType());
    }

    @Test
    @DisplayName("TrackingTicketBridge：建客服工单并 UPDATE t_logistics_alert.ticket_id（JdbcTemplate 直写热扩列）")
    void ticketBridgeWritesBackTicketId() {
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        com.lanlink.shopping.entity.SupportTicket ticket = new com.lanlink.shopping.entity.SupportTicket();
        ticket.setId(9L);
        ticket.setStatus("OPEN");
        when(supportTicketService.submit(eq(9L), eq("order"), contains("LL202611060001"), any(), any()))
                .thenReturn(ticket);
        JdbcTemplate realJdbc = mock(JdbcTemplate.class);
        TrackingTicketBridge bridge = new TrackingTicketBridge(supportTicketService, realJdbc);

        LogisticsAlert alert = new LogisticsAlert();
        alert.setAlertId(5L);
        alert.setAlertType("timeout_no_update");
        alert.setContent("运单 LL202611060001 渠道动态已 50 小时无更新");
        Map<String, Object> out = bridge.bridge(alert, waybill, 9L);

        assertEquals(9L, out.get("ticketId"));
        verify(realJdbc).update(contains("ticket_id"), eq(9L), eq(5L));
    }
}

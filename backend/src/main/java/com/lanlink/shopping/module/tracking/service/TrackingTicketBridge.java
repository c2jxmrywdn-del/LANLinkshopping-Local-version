package com.lanlink.shopping.module.tracking.service;

import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.entity.SupportTicket;
import com.lanlink.shopping.support.SupportTicketService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物流告警 → 客服工单桥接（out/logistics-tracking-plan.md §3.3 / D7）：
 * 调既有 SupportTicketService 建单（category 归入 order，userId=操作运营），
 * 回写 t_logistics_alert.ticket_id 热扩列（该列不在物流域实体上，JdbcTemplate 直写，
 * 与 WaybillTrackingListener 写运单热扩列同一模式）。不做双向状态同步：
 * 工单流转按客服侧既有流程，看板展示 ticket_id 即可。
 */
@Component
public class TrackingTicketBridge {

    private final SupportTicketService supportTicketService;
    private final JdbcTemplate jdbc;

    public TrackingTicketBridge(SupportTicketService supportTicketService, JdbcTemplate jdbc) {
        this.supportTicketService = supportTicketService;
        this.jdbc = jdbc;
    }

    /** 创建客服工单并回写 alert.ticket_id，返回看板「转工单」动作的落点数据 */
    public Map<String, Object> bridge(LogisticsAlert alert, LogisticsWaybill waybill, Long operatorUserId) {
        String title = truncate("物流异常转工单-" + waybill.getWaybillNo(), 100);
        String content = truncate(String.format(
                "运单 %s（订单 %s）物流异常转人工跟进。告警类型：%s；告警内容：%s",
                waybill.getWaybillNo(), waybill.getOrderNo(), alert.getAlertType(), alert.getContent()), 2000);
        SupportTicket ticket = supportTicketService.submit(operatorUserId, "order", title, content, null);

        jdbc.update("UPDATE t_logistics_alert SET ticket_id = ? WHERE alert_id = ?",
                ticket.getId(), alert.getAlertId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alertId", alert.getAlertId());
        out.put("ticketId", ticket.getId());
        out.put("waybillId", waybill.getWaybillId());
        out.put("waybillNo", waybill.getWaybillNo());
        return out;
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}

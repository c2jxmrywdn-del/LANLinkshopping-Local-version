package com.lanlink.shopping.module.tracking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.service.LogisticsStateMachine;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 物流异常识别与看板（out/logistics-tracking-plan.md P2/P3 任务 4）：
 *  1. 48h 无更新 → timeout_no_update 告警（同单同类型未 resolved 幂等）+ exception_flag，
 *     看板数据由此聚合（调度写法对齐 LogisticsSlaWatchdogService，不与其重复告警：
 *     watchdog 只覆盖 in_transit，本巡检覆盖全部非终态，幂等键兜底去重）；
 *  2. 关键词命中（派送失败/拒收/退回）→ EXCEPTION/RETURNED 状态推进在轨迹摄入链路完成
 *     （TrackingStatusMapper 七态收敛 → syncTrace 状态机），不在本巡检重复处理；
 *  3. 签收后 24h 无更新且 e-POD 未复核 → 人工复核队列（看板读时计算，不落表）。
 * 看板「转工单」落点：createTicket 写 alert.ticket_id（TrackingTicketBridge 桥接客服工单）。
 */
@Service
public class TrackingExceptionService {

    private static final Logger log = LoggerFactory.getLogger(TrackingExceptionService.class);

    /** 补拉任务死态告警类型（TrackingSyncWorker 死态时落，看板展示） */
    public static final String ALERT_SYNC_DEAD = "sync_dead";
    /** 签收后无更新人工复核告警类型（转工单兜底建告警时使用） */
    public static final String ALERT_POD_REVIEW = "pod_review";
    /** e-POD 已复核完成的审核状态值（t_logistics_waybill.pod_audit_status） */
    private static final List<String> POD_APPROVED = List.of("approved");

    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsAlertMapper alertMapper;
    private final TrackingEventMapper eventMapper;
    private final TrackingSyncTaskMapper taskMapper;
    private final TrackingTicketBridge ticketBridge;
    private final JdbcTemplate jdbc;
    private final TrackingProperties properties;

    public TrackingExceptionService(LogisticsWaybillMapper waybillMapper,
                                    LogisticsAlertMapper alertMapper,
                                    TrackingEventMapper eventMapper,
                                    TrackingSyncTaskMapper taskMapper,
                                    TrackingTicketBridge ticketBridge,
                                    JdbcTemplate jdbc,
                                    TrackingProperties properties) {
        this.waybillMapper = waybillMapper;
        this.alertMapper = alertMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.ticketBridge = ticketBridge;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 异常巡检结果摘要 */
    @Data
    public static class ExceptionSweepReport {
        private int scannedWaybills;
        private int alertsTriggered;
        private int alertsSkipped;
        private List<String> alertSummaries = new ArrayList<>();
    }

    /** 定时异常识别（对齐 SLA watchdog 的 cron 写法，默认每 30 分钟） */
    @Scheduled(cron = "${logistics.tracking.exception-cron:0 */30 * * * ?}")
    public void scheduledExceptionSweep() {
        if (!properties.isEnabled()) return;
        ExceptionSweepReport report = sweepExceptions(LocalDateTime.now());
        if (report.getAlertsTriggered() > 0) {
            log.info("[tracking] 异常巡检完成：扫描 {} 单，新增告警 {} 起，幂等忽略 {} 起",
                    report.getScannedWaybills(), report.getAlertsTriggered(), report.getAlertsSkipped());
        }
    }

    /** 48h 无更新规则：全部非终态运单（watchdog 只覆盖 in_transit，此处补齐其余在途态） */
    @Transactional
    public ExceptionSweepReport sweepExceptions(LocalDateTime now) {
        ExceptionSweepReport report = new ExceptionSweepReport();
        List<LogisticsWaybill> actives = waybillMapper.selectList(Wrappers.<LogisticsWaybill>lambdaQuery()
                .in(LogisticsWaybill::getStatus, LogisticsStateMachine.PICKED_UP,
                        LogisticsStateMachine.IN_TRANSIT, LogisticsStateMachine.OUT_FOR_DELIVERY,
                        LogisticsStateMachine.EXCEPTION));
        report.setScannedWaybills(actives.size());
        for (LogisticsWaybill wb : actives) {
            LocalDateTime lastActivity = lastActivityTime(wb);
            if (lastActivity == null
                    || !lastActivity.isBefore(now.minusHours(properties.getExceptionStaleHours()))) {
                continue;
            }
            long hours = Duration.between(lastActivity, now).toHours();
            String content = String.format("运单 %s 渠道动态已 %d 小时无更新（最后动态 %s），疑似滞留",
                    wb.getWaybillNo(), hours, lastActivity);
            long active = alertMapper.countActiveByType(wb.getWaybillId(), "timeout_no_update");
            if (active > 0) {
                report.setAlertsSkipped(report.getAlertsSkipped() + 1);
                continue;
            }
            LogisticsAlert alert = new LogisticsAlert();
            alert.setWaybillId(wb.getWaybillId());
            alert.setAlertType("timeout_no_update");
            alert.setAlertLevel("high");
            alert.setContent(content);
            alert.setStatus("pending");
            alert.setCreateTime(now);
            alertMapper.insert(alert);
            wb.setExceptionFlag(1);
            waybillMapper.updateById(wb);
            report.setAlertsTriggered(report.getAlertsTriggered() + 1);
            report.getAlertSummaries().add(content);
        }
        return report;
    }

    /**
     * 异常看板数据：超时异常运单 + 人工复核队列 + 待处理告警（含 ticket_id）+ 计数。
     * 看板消费端：GET /admin/logistics/exception。
     */
    public Map<String, Object> board() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> staleEntries = new ArrayList<>();
        for (LogisticsWaybill wb : staleWaybills(now)) {
            Map<String, Object> entry = baseWaybillEntry(wb);
            LocalDateTime last = lastActivityTime(wb);
            entry.put("lastActivityTime", last);
            entry.put("staleHours", last == null ? null : Duration.between(last, now).toHours());
            LogisticsAlert alert = latestUnresolvedAlert(wb.getWaybillId());
            if (alert != null) {
                entry.put("alertId", alert.getAlertId());
                entry.put("alertType", alert.getAlertType());
                entry.put("alertStatus", alert.getStatus());
            }
            staleEntries.add(entry);
        }

        List<Map<String, Object>> reviewQueue = reviewQueue(now);

        List<LogisticsAlert> pendingAlerts = alertMapper.selectList(Wrappers.<LogisticsAlert>lambdaQuery()
                .ne(LogisticsAlert::getStatus, "resolved")
                .orderByDesc(LogisticsAlert::getAlertId)
                .last("LIMIT 50"));
        Map<Long, Long> ticketIds = ticketIdsByAlert(pendingAlerts);
        List<Map<String, Object>> alertEntries = pendingAlerts.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertId", a.getAlertId());
            m.put("waybillId", a.getWaybillId());
            m.put("alertType", a.getAlertType());
            m.put("alertLevel", a.getAlertLevel());
            m.put("content", a.getContent());
            m.put("status", a.getStatus());
            m.put("createTime", a.getCreateTime());
            m.put("ticketId", ticketIds.get(a.getAlertId()));
            return m;
        }).collect(Collectors.toList());

        Long deadTasks = taskMapper.selectCount(Wrappers.<TrackingSyncTask>lambdaQuery()
                .eq(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_DEAD));

        Map<String, Object> board = new LinkedHashMap<>();
        board.put("generatedAt", now);
        board.put("exceptionStaleHours", properties.getExceptionStaleHours());
        board.put("reviewStaleHours", properties.getReviewStaleHours());
        board.put("staleWaybills", staleEntries);
        board.put("reviewQueue", reviewQueue);
        board.put("pendingAlerts", alertEntries);
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("staleCount", staleEntries.size());
        counters.put("reviewQueueCount", reviewQueue.size());
        counters.put("pendingAlertCount", alertEntries.size());
        counters.put("deadTaskCount", deadTasks == null ? 0 : deadTasks);
        board.put("counters", counters);
        return board;
    }

    /**
     * 看板「转工单」落点：取运单最新未 resolved 告警（无则按运单态兜底建一条），
     * 经 TrackingTicketBridge 创建客服工单并回写 alert.ticket_id。
     */
    @Transactional
    public Map<String, Object> createTicket(Long waybillId, Long operatorUserId) {
        LogisticsWaybill wb = waybillMapper.selectById(waybillId);
        if (wb == null) throw new BusinessException("运单不存在");
        LogisticsAlert alert = latestUnresolvedAlert(waybillId);
        if (alert == null) {
            alert = new LogisticsAlert();
            alert.setWaybillId(waybillId);
            alert.setAlertType(LogisticsStateMachine.DELIVERED.equals(wb.getStatus())
                    ? ALERT_POD_REVIEW : "timeout_no_update");
            alert.setAlertLevel("medium");
            alert.setContent(String.format("运单 %s 运营转人工跟进（%s 态，最后动态 %s）",
                    wb.getWaybillNo(), wb.getStatus(), lastActivityTime(wb)));
            alert.setStatus("pending");
            alert.setCreateTime(LocalDateTime.now());
            alertMapper.insert(alert);
        }
        return ticketBridge.bridge(alert, wb, operatorUserId);
    }

    /** 人工复核队列：签收后 reviewStaleHours 无更新且 e-POD 未复核完成（读时计算，不落表） */
    public List<Map<String, Object>> reviewQueue(LocalDateTime now) {
        List<LogisticsWaybill> delivered = waybillMapper.selectList(Wrappers.<LogisticsWaybill>lambdaQuery()
                .eq(LogisticsWaybill::getStatus, LogisticsStateMachine.DELIVERED)
                .orderByAsc(LogisticsWaybill::getDeliveredTime)
                .last("LIMIT 100"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LogisticsWaybill wb : delivered) {
            String podStatus = wb.getPodAuditStatus();
            if (podStatus != null && POD_APPROVED.contains(podStatus)) continue;
            LocalDateTime last = lastActivityTime(wb);
            if (last == null || !last.isBefore(now.minusHours(properties.getReviewStaleHours()))) continue;
            Map<String, Object> entry = baseWaybillEntry(wb);
            entry.put("lastActivityTime", last);
            entry.put("deliveredTime", wb.getDeliveredTime());
            entry.put("podAuditStatus", podStatus == null ? "none" : podStatus);
            entry.put("reason", String.format("签收后 %d 小时无后续更新且 e-POD 未复核完成，转人工复核",
                    Duration.between(last, now).toHours()));
            out.add(entry);
        }
        return out;
    }

    /** 非终态且最后动态超 exceptionStaleHours 的运单（48h 规则的看板读侧） */
    private List<LogisticsWaybill> staleWaybills(LocalDateTime now) {
        List<LogisticsWaybill> actives = waybillMapper.selectList(Wrappers.<LogisticsWaybill>lambdaQuery()
                .in(LogisticsWaybill::getStatus, LogisticsStateMachine.PICKED_UP,
                        LogisticsStateMachine.IN_TRANSIT, LogisticsStateMachine.OUT_FOR_DELIVERY,
                        LogisticsStateMachine.EXCEPTION)
                .orderByAsc(LogisticsWaybill::getWaybillId)
                .last("LIMIT 50"));
        List<LogisticsWaybill> stale = new ArrayList<>();
        for (LogisticsWaybill wb : actives) {
            LocalDateTime last = lastActivityTime(wb);
            if (last != null && last.isBefore(now.minusHours(properties.getExceptionStaleHours()))) {
                stale.add(wb);
            }
        }
        return stale;
    }

    /** 最后动态时间：渠道事件（排除 poll 记录）与运单自身更新时间取较新者（人工操作也算动态） */
    private LocalDateTime lastActivityTime(LogisticsWaybill wb) {
        LocalDateTime event = lastChannelEventTime(wb.getWaybillId());
        LocalDateTime updated = wb.getUpdateTime();
        if (event == null) return updated;
        if (updated == null) return event;
        return updated.isAfter(event) ? updated : event;
    }

    private LocalDateTime lastChannelEventTime(Long waybillId) {
        List<TrackingEvent> latest = eventMapper.selectList(Wrappers.<TrackingEvent>lambdaQuery()
                .eq(TrackingEvent::getWaybillId, waybillId)
                .ne(TrackingEvent::getEventType, TrackingEvent.TYPE_POLL)
                .orderByDesc(TrackingEvent::getCreateTime)
                .last("LIMIT 1"));
        return latest.isEmpty() ? null : latest.get(0).getCreateTime();
    }

    private LogisticsAlert latestUnresolvedAlert(Long waybillId) {
        List<LogisticsAlert> rows = alertMapper.selectList(Wrappers.<LogisticsAlert>lambdaQuery()
                .eq(LogisticsAlert::getWaybillId, waybillId)
                .ne(LogisticsAlert::getStatus, "resolved")
                .orderByDesc(LogisticsAlert::getAlertId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** alert.ticket_id 热扩列不在实体上，一次 IN 查询补齐看板展示 */
    private Map<Long, Long> ticketIdsByAlert(List<LogisticsAlert> alerts) {
        if (alerts.isEmpty()) return Map.of();
        String in = alerts.stream().map(a -> String.valueOf(a.getAlertId()))
                .collect(Collectors.joining(","));
        return jdbc.query("SELECT alert_id, ticket_id FROM t_logistics_alert WHERE alert_id IN (" + in + ")",
                rs -> {
                    Map<Long, Long> map = new HashMap<>();
                    while (rs.next()) {
                        long ticketId = rs.getLong(2);
                        if (!rs.wasNull()) map.put(rs.getLong(1), ticketId);
                    }
                    return map;
                });
    }

    private Map<String, Object> baseWaybillEntry(LogisticsWaybill wb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("waybillId", wb.getWaybillId());
        m.put("waybillNo", wb.getWaybillNo());
        m.put("orderNo", wb.getOrderNo());
        m.put("status", wb.getStatus());
        m.put("exceptionFlag", wb.getExceptionFlag());
        return m;
    }
}

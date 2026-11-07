package com.lanlink.shopping.module.tracking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingCallbackLog;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingCallbackLogMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.service.LogisticsStateMachine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跟踪链路监控指标（out/logistics-tracking-plan.md P2/P3 任务 5）：
 * GET /admin/logistics/metrics，从三表（tracking_callback_log / tracking_event /
 * tracking_sync_task）+ 运单表聚合：回调成功率 / 补拉失败率 / 入库延迟 P95 / 异常占比。
 */
@Service
public class TrackingMetricsService {

    /** 入库延迟 P95 的采样上限（按入库时间取最近 N 条，demo 量级足够代表） */
    private static final int LATENCY_SAMPLE_LIMIT = 1000;

    private final TrackingCallbackLogMapper callbackLogMapper;
    private final TrackingEventMapper eventMapper;
    private final TrackingSyncTaskMapper taskMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final TrackingProperties properties;

    public TrackingMetricsService(TrackingCallbackLogMapper callbackLogMapper,
                                  TrackingEventMapper eventMapper,
                                  TrackingSyncTaskMapper taskMapper,
                                  LogisticsWaybillMapper waybillMapper,
                                  TrackingProperties properties) {
        this.callbackLogMapper = callbackLogMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.waybillMapper = waybillMapper;
        this.properties = properties;
    }

    public Map<String, Object> collect(LocalDateTime now) {
        LocalDateTime since = now.minusHours(properties.getMetricsWindowHours());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", properties.getMetricsWindowHours());
        out.put("generatedAt", now);

        // 回调成功率：tracking_callback_log PASS / 全部（验签层口径）
        Long callbackTotal = callbackLogMapper.selectCount(Wrappers.<TrackingCallbackLog>lambdaQuery()
                .ge(TrackingCallbackLog::getCreateTime, since));
        Long callbackPass = callbackLogMapper.selectCount(Wrappers.<TrackingCallbackLog>lambdaQuery()
                .ge(TrackingCallbackLog::getCreateTime, since)
                .eq(TrackingCallbackLog::getVerifyResult, TrackingCallbackLog.VERIFY_PASS));
        long cbTotal = callbackTotal == null ? 0 : callbackTotal;
        long cbPass = callbackPass == null ? 0 : callbackPass;
        out.put("callbackTotal", cbTotal);
        out.put("callbackPass", cbPass);
        out.put("callbackFail", cbTotal - cbPass);
        out.put("callbackSuccessRate", percent(cbPass, cbTotal));

        // 补拉失败率：tracking_event event_type=poll 的 failed 占比（事件层口径）
        Long pollTotal = eventMapper.selectCount(Wrappers.<TrackingEvent>lambdaQuery()
                .eq(TrackingEvent::getEventType, TrackingEvent.TYPE_POLL)
                .ge(TrackingEvent::getCreateTime, since));
        Long pollFailed = eventMapper.selectCount(Wrappers.<TrackingEvent>lambdaQuery()
                .eq(TrackingEvent::getEventType, TrackingEvent.TYPE_POLL)
                .eq(TrackingEvent::getStatus, TrackingEvent.STATUS_FAILED)
                .ge(TrackingEvent::getCreateTime, since));
        long pTotal = pollTotal == null ? 0 : pollTotal;
        long pFailed = pollFailed == null ? 0 : pollFailed;
        out.put("pollTotal", pTotal);
        out.put("pollFailed", pFailed);
        out.put("pollFailureRate", percent(pFailed, pTotal));

        // 入库延迟 P95：normalized 事件的（入库时间 - 渠道侧最新节点时间），取最近采样窗口
        List<TrackingEvent> normalized = eventMapper.selectList(Wrappers.<TrackingEvent>lambdaQuery()
                .eq(TrackingEvent::getStatus, TrackingEvent.STATUS_NORMALIZED)
                .isNotNull(TrackingEvent::getEventTime)
                .ge(TrackingEvent::getCreateTime, since)
                .orderByDesc(TrackingEvent::getCreateTime)
                .last("LIMIT " + LATENCY_SAMPLE_LIMIT));
        List<Long> latencies = new ArrayList<>();
        for (TrackingEvent event : normalized) {
            if (event.getEventTime() == null || event.getCreateTime() == null) continue;
            long seconds = Duration.between(event.getEventTime(), event.getCreateTime()).getSeconds();
            if (seconds >= 0) latencies.add(seconds); // 负值=渠道时钟超前，剔除防污染分位
        }
        latencies.sort(Long::compare);
        out.put("ingestLatencySampleSize", latencies.size());
        out.put("ingestLatencyP95Seconds", percentile95(latencies));

        // 异常占比：非终态运单中 exception_flag=1 或 status=exception 的比例
        Long activeWaybills = waybillMapper.selectCount(Wrappers.<LogisticsWaybill>lambdaQuery()
                .notIn(LogisticsWaybill::getStatus, LogisticsStateMachine.TERMINAL));
        Long exceptionWaybills = waybillMapper.selectCount(Wrappers.<LogisticsWaybill>lambdaQuery()
                .notIn(LogisticsWaybill::getStatus, LogisticsStateMachine.TERMINAL)
                .and(w -> w.eq(LogisticsWaybill::getExceptionFlag, 1)
                        .or()
                        .eq(LogisticsWaybill::getStatus, LogisticsStateMachine.EXCEPTION)));
        long active = activeWaybills == null ? 0 : activeWaybills;
        long abnormal = exceptionWaybills == null ? 0 : exceptionWaybills;
        out.put("activeWaybills", active);
        out.put("exceptionWaybills", abnormal);
        out.put("exceptionRatio", percent(abnormal, active));

        // 补拉任务健康度（tracking_sync_task）
        Long deadTasks = taskMapper.selectCount(Wrappers.<TrackingSyncTask>lambdaQuery()
                .eq(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_DEAD));
        Long pendingTasks = taskMapper.selectCount(Wrappers.<TrackingSyncTask>lambdaQuery()
                .in(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_PENDING, TrackingSyncTask.STATUS_FAILED));
        out.put("deadTasks", deadTasks == null ? 0 : deadTasks);
        out.put("pendingTasks", pendingTasks == null ? 0 : pendingTasks);
        return out;
    }

    /** 百分比（一位小数）；样本为 0 时返回 0.0，看板以 total=0 判别「无数据」 */
    static double percent(long part, long total) {
        if (total <= 0) return 0.0;
        return Math.round(part * 1000.0 / total) / 10.0;
    }

    /** 升序 P95（ceil(0.95n)-1 下标）；空样本 0.0 */
    static double percentile95(List<Long> sorted) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}

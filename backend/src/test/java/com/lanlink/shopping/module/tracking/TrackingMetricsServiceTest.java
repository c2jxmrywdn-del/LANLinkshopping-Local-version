package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.mapper.TrackingCallbackLogMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.module.tracking.service.TrackingMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 监控指标聚合单测（out/logistics-tracking-plan.md P2/P3 任务 5 / ask 任务 5）：
 * 回调成功率 / 补拉失败率 / 入库延迟 P95 / 异常占比，从三表聚合（stub 顺序即聚合查询顺序）。
 */
class TrackingMetricsServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0, 0);

    private TrackingCallbackLogMapper callbackLogMapper;
    private TrackingEventMapper eventMapper;
    private TrackingSyncTaskMapper taskMapper;
    private LogisticsWaybillMapper waybillMapper;
    private TrackingMetricsService service;

    @BeforeEach
    void setUp() {
        callbackLogMapper = mock(TrackingCallbackLogMapper.class);
        eventMapper = mock(TrackingEventMapper.class);
        taskMapper = mock(TrackingSyncTaskMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        service = new TrackingMetricsService(callbackLogMapper, eventMapper, taskMapper,
                waybillMapper, new TrackingProperties());
    }

    @Test
    @DisplayName("四指标聚合：回调成功率 90%、补拉失败率 25%、异常占比 8%、P95 取分位下标 ceil(0.95n)-1")
    void aggregatesMetricsFromThreeTables() {
        // 聚合顺序：callback total/pass → poll total/failed → latency samples → active/abnormal → dead/pending
        when(callbackLogMapper.selectCount(any())).thenReturn(10L).thenReturn(9L);
        when(eventMapper.selectCount(any())).thenReturn(20L).thenReturn(5L);
        when(eventMapper.selectList(any())).thenReturn(latencySamples(100));
        when(waybillMapper.selectCount(any())).thenReturn(100L).thenReturn(8L);
        when(taskMapper.selectCount(any())).thenReturn(2L).thenReturn(3L);

        Map<String, Object> metrics = service.collect(NOW);

        assertEquals(24, metrics.get("windowHours"));
        assertEquals(10L, metrics.get("callbackTotal"));
        assertEquals(9L, metrics.get("callbackPass"));
        assertEquals(1L, metrics.get("callbackFail"));
        assertEquals(90.0, metrics.get("callbackSuccessRate"));
        assertEquals(20L, metrics.get("pollTotal"));
        assertEquals(5L, metrics.get("pollFailed"));
        assertEquals(25.0, metrics.get("pollFailureRate"));
        assertEquals(100, metrics.get("ingestLatencySampleSize"));
        assertEquals(95.0, metrics.get("ingestLatencyP95Seconds"));
        assertEquals(100L, metrics.get("activeWaybills"));
        assertEquals(8L, metrics.get("exceptionWaybills"));
        assertEquals(8.0, metrics.get("exceptionRatio"));
        assertEquals(2L, metrics.get("deadTasks"));
        assertEquals(3L, metrics.get("pendingTasks"));
    }

    @Test
    @DisplayName("空数据：比率与 P95 返回 0.0（看板以 total=0 判别无数据），不入库延迟负值被剔除")
    void emptyDataYieldsZeroRates() {
        when(callbackLogMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        when(eventMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        when(eventMapper.selectList(any())).thenReturn(List.of());
        when(waybillMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);

        Map<String, Object> metrics = service.collect(NOW);

        assertEquals(0.0, metrics.get("callbackSuccessRate"));
        assertEquals(0.0, metrics.get("pollFailureRate"));
        assertEquals(0.0, metrics.get("ingestLatencyP95Seconds"));
        assertEquals(0, metrics.get("ingestLatencySampleSize"));
        assertEquals(0.0, metrics.get("exceptionRatio"));
    }

    @Test
    @DisplayName("时钟超前（负延迟）样本剔除后 P95 不受污染")
    void negativeLatencyFiltered() {
        when(callbackLogMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        when(eventMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        TrackingEvent skewed = event(NOW, NOW.plusSeconds(30)); // 渠道节点时间晚于入库 30s → 负延迟剔除
        TrackingEvent normal = event(NOW, NOW.minusSeconds(10)); // 延迟 10s
        when(eventMapper.selectList(any())).thenReturn(List.of(skewed, normal));
        when(waybillMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(0L).thenReturn(0L);

        Map<String, Object> metrics = service.collect(NOW);

        assertEquals(1, metrics.get("ingestLatencySampleSize"));
        assertEquals(10.0, metrics.get("ingestLatencyP95Seconds"));
    }

    /** 生成 n 条延迟 1..n 秒的 normalized 事件（createTime = eventTime + i 秒） */
    private static List<TrackingEvent> latencySamples(int n) {
        List<TrackingEvent> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(event(NOW.minusSeconds(i), NOW.minusSeconds(i).minusSeconds(i)));
        }
        return out;
    }

    private static TrackingEvent event(LocalDateTime createTime, LocalDateTime eventTime) {
        TrackingEvent e = new TrackingEvent();
        e.setCreateTime(createTime);
        e.setEventTime(eventTime);
        e.setStatus(TrackingEvent.STATUS_NORMALIZED);
        e.setEventType(TrackingEvent.TYPE_POLL);
        return e;
    }
}

package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.MockCarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.ProviderRouter;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.adapter.WaybillTrackInfo;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.module.tracking.service.TrackingExceptionService;
import com.lanlink.shopping.module.tracking.service.TrackingSyncWorker;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时补拉 worker 单测（out/logistics-tracking-plan.md P2/P3 任务 1+2 / ask 任务 2/6）：
 * 4 小时静默扫描 + CAS 认领 + 主通道连续失败 3 次切备用通道 + 切换后轨迹按 event_time
 * 合并去重 + 未配置顺延不烧重试 + retry≥6 转死态告警。
 */
class TrackingSyncWorkerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 6, 12, 0, 0);
    private static final LocalDateTime LAST_EVENT = T0.minusHours(10);

    private TrackingSyncTaskMapper taskMapper;
    private TrackingEventMapper eventMapper;
    private LogisticsWaybillMapper waybillMapper;
    private LogisticsTraceMapper traceMapper;
    private LogisticsAlertMapper alertMapper;
    private LogisticsProviderMapper providerMapper;
    private LogisticsWaybillService waybillService;
    private TrackingProperties properties;
    private MockCarrierAdapter mockAdapter;
    private TrackingSyncWorker worker;
    private LogisticsWaybill waybill;

    /**
     * 纯单测无 MP 容器：lambda wrapper 解析列需要 TableInfo 缓存
     * （与 CampaignServiceTest 同一模式，逐实体幂等初始化）。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.lanlink.shopping.module.tracking.domain.TrackingSyncTask.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.lanlink.shopping.module.tracking.domain.TrackingEvent.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                LogisticsTrace.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                LogisticsWaybill.class);
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(TrackingSyncTaskMapper.class);
        eventMapper = mock(TrackingEventMapper.class);
        waybillMapper = mock(LogisticsWaybillMapper.class);
        traceMapper = mock(LogisticsTraceMapper.class);
        alertMapper = mock(LogisticsAlertMapper.class);
        providerMapper = mock(LogisticsProviderMapper.class);
        waybillService = mock(LogisticsWaybillService.class);
        mockAdapter = new MockCarrierAdapter();

        properties = new TrackingProperties();
        properties.setPrimaryFailThreshold(3);
        properties.getFallbackRoutes().put("kuaidi100", "mock");

        waybill = new LogisticsWaybill();
        waybill.setWaybillId(77L);
        waybill.setWaybillNo("LL202611060001");
        waybill.setOrderNo("ORD-1");
        waybill.setProviderId(1L);
        waybill.setStatus("in_transit");
        waybill.setShipTime(LAST_EVENT);

        when(taskMapper.update(any(), any())).thenReturn(1); // CAS 认领成功
        when(waybillMapper.selectById(77L)).thenReturn(waybill);
        when(waybillMapper.updateById(any(LogisticsWaybill.class))).thenReturn(1);
        when(eventMapper.selectList(any())).thenReturn(List.of()); // 无渠道事件 → shipTime 兜底判静默
        LogisticsProvider provider = new LogisticsProvider();
        provider.setProviderId(1L);
        provider.setProviderCode("SF");
        when(providerMapper.selectById(1L)).thenReturn(provider);
        when(waybillService.syncTrace(eq(77L), anyList())).thenReturn(Map.of("imported", 1));
    }

    private TrackingSyncWorker workerWith(CarrierAdapter... adapters) {
        CarrierAdapterRegistry registry = new CarrierAdapterRegistry(List.of(adapters), properties);
        return new TrackingSyncWorker(taskMapper, eventMapper, waybillMapper, traceMapper,
                alertMapper, providerMapper, registry,
                new ProviderRouter(properties, taskMapper), waybillService, properties);
    }

    private TrackingSyncTask dueTask(String providerCode) {
        TrackingSyncTask task = new TrackingSyncTask();
        task.setTaskId(11L);
        task.setWaybillId(77L);
        task.setProviderCode(providerCode);
        task.setLogisticsNo("SF-TEST-1");
        task.setStatus(TrackingSyncTask.STATUS_PENDING);
        task.setNextPollTime(T0.minusSeconds(1));
        task.setRetryCount(0);
        return task;
    }

    /** 恒失败适配器（主通道故障注入） */
    private static final class FailingAdapter implements CarrierAdapter {
        @Override public TrackingChannel channel() { return TrackingChannel.KUAIDI100; }
        @Override public boolean configured() { return true; }
        @Override public SubscribeResult subscribe(WaybillTrackInfo waybill) { return SubscribeResult.fail("no"); }
        @Override public PollResult poll(WaybillTrackInfo waybill) { return PollResult.fail("upstream 500"); }
        @Override public List<NormalizedTraceNode> parse(String rawBody) { return List.of(); }
    }

    @Test
    @DisplayName("静默超 4h 未签收运单被兜底补拉：合并去重（旧通道 10:00 保留 + 新 12:00 补充）并顺延任务")
    void staleWaybillPolledMergedAndDeferred() {
        worker = workerWith(mockAdapter);
        TrackingSyncTask task = dueTask("mock");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        // 旧通道已落库节点：10:00
        LogisticsTrace oldTrace = new LogisticsTrace();
        oldTrace.setWaybillId(77L);
        oldTrace.setTraceTime(T0.minusHours(2)); // 10:00
        oldTrace.setDescription("旧通道：已到达苏州转运中心");
        when(traceMapper.selectList(any())).thenReturn(List.of(oldTrace));
        // 备用（当前）通道补拉脚本：10:00 同事件措辞差异 + 12:00 新事件
        mockAdapter.registerScenario("SF-TEST-1", List.of(
                NormalizedTraceNode.of(T0.minusHours(2), "苏州", "备用通道：包裹抵达苏州集散点"),
                NormalizedTraceNode.of(T0, "南京", "备用通道：到达南京转运中心")));

        int polled = worker.sweepOnce(T0);

        assertEquals(1, polled);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> nodesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(waybillService, times(1)).syncTrace(eq(77L), nodesCaptor.capture());
        List<Map<String, Object>> merged = nodesCaptor.getValue();
        assertEquals(2, merged.size(), "同 event_time 去重后应剩旧通道 10:00 + 新 12:00");
        assertEquals("旧通道：已到达苏州转运中心", merged.get(0).get("description"));
        assertEquals("备用通道：到达南京转运中心", merged.get(1).get("description"));
        // 七态收敛随节点显式下传
        assertEquals("in_transit", merged.get(0).get("status"));

        // 成功后事件 normalized 且 event_time=渠道侧最新节点时间 12:00
        ArgumentCaptor<TrackingEvent> eventCaptor = ArgumentCaptor.forClass(TrackingEvent.class);
        verify(eventMapper, times(1)).insert(eventCaptor.capture());
        assertEquals(TrackingEvent.STATUS_NORMALIZED, eventCaptor.getValue().getStatus());
        assertEquals(T0, eventCaptor.getValue().getEventTime());
        assertEquals(1, eventCaptor.getValue().getNormalizedCount());

        // 任务顺延：pending + next_poll_time 前移
        assertEquals(TrackingSyncTask.STATUS_PENDING, task.getStatus());
        assertNotNull(task.getNextPollTime());
        assertTrue(task.getNextPollTime().isAfter(T0));
    }

    @Test
    @DisplayName("未超 4h 静默（1h 前有渠道事件）：不认领不补拉")
    void freshWaybillSkipped() {
        worker = workerWith(mockAdapter);
        TrackingSyncTask task = dueTask("mock");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        TrackingEvent recent = new TrackingEvent();
        recent.setWaybillId(77L);
        recent.setCreateTime(T0.minusHours(1));
        when(eventMapper.selectList(any())).thenReturn(List.of(recent));

        assertEquals(0, worker.sweepOnce(T0));
        // 每轮 sweepOnce 恰一次遗留认领回收；未超静默阈值 → 不发生任务认领（update 仅 1 次）
        verify(taskMapper, times(1)).update(any(), any());
        verify(waybillService, never()).syncTrace(any(), anyList());
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
    }

    @Test
    @DisplayName("CAS 认领失败（其他实例接手）：本轮不补拉")
    void claimFailureSkipsPoll() {
        worker = workerWith(mockAdapter);
        TrackingSyncTask task = dueTask("mock");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.update(any(), any())).thenReturn(0);

        assertEquals(0, worker.sweepOnce(T0));
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
        verify(waybillService, never()).syncTrace(any(), anyList());
    }

    @Test
    @DisplayName("终态运单（已签收）不再补拉")
    void terminalWaybillSkipped() {
        worker = workerWith(mockAdapter);
        waybill.setStatus("delivered");
        TrackingSyncTask task = dueTask("mock");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        assertEquals(0, worker.sweepOnce(T0));
        // 终态收敛：仅遗留认领回收 1 次，无任务认领、无补拉
        verify(taskMapper, times(1)).update(any(), any());
        verify(waybillService, never()).syncTrace(any(), anyList());
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
    }

    @Test
    @DisplayName("渠道未配置密钥：顺延 next_poll_time 且不烧 retry/fail_count、不落事件行")
    void notConfiguredDefersWithoutBurningRetry() {
        TrackingProperties emptyKeyProps = new TrackingProperties();
        emptyKeyProps.setPrimaryFailThreshold(3);
        CarrierAdapterRegistry registry = new CarrierAdapterRegistry(
                List.of(new com.lanlink.shopping.module.tracking.adapter.Kuaidi100Adapter(emptyKeyProps)),
                emptyKeyProps);
        worker = new TrackingSyncWorker(taskMapper, eventMapper, waybillMapper, traceMapper,
                alertMapper, providerMapper, registry,
                new ProviderRouter(emptyKeyProps, taskMapper), waybillService, emptyKeyProps);

        TrackingSyncTask task = dueTask("kuaidi100");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        assertEquals(0, worker.sweepOnce(T0));
        assertEquals(TrackingSyncTask.STATUS_PENDING, task.getStatus());
        assertEquals(0, task.getRetryCount());
        assertTrue(task.getNextPollTime().isAfter(T0));
        assertEquals("NOT_CONFIGURED", task.getLastError());
        verify(eventMapper, never()).insert(any(TrackingEvent.class));
    }

    @Test
    @DisplayName("主通道连续失败 3 次 → 第 4 轮切备用通道 mock 补拉成功（切换日志 + 合并去重）")
    void threeConsecutiveFailuresSwitchToFallbackThenSucceed() {
        worker = workerWith(new FailingAdapter(), mockAdapter);
        TrackingSyncTask task = dueTask("kuaidi100");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(traceMapper.selectList(any())).thenReturn(List.of());
        mockAdapter.registerScenario("SF-TEST-1", List.of(
                NormalizedTraceNode.of(T0.minusHours(1), "苏州", "备用通道节点")));

        // 第 1-3 轮：主通道 kuaidi100（故障注入）连续失败，按 1m/5m/30m 退避
        assertEquals(0, worker.sweepOnce(T0));
        assertEquals(1, task.getFailCount());
        assertEquals(TrackingSyncTask.STATUS_FAILED, task.getStatus());
        assertEquals(1, task.getRetryCount());

        assertEquals(0, worker.sweepOnce(T0.plusSeconds(61)));
        assertEquals(2, task.getFailCount());
        assertEquals(2, task.getRetryCount());

        assertEquals(0, worker.sweepOnce(T0.plusSeconds(420)));
        assertEquals(3, task.getFailCount());
        assertNullActiveChannel(task);

        // 第 4 轮：达阈值 3 切备用通道 mock → 补拉成功
        assertEquals(1, worker.sweepOnce(T0.plusSeconds(2400)));
        assertEquals("mock", task.getActiveChannel(), "连续失败 3 次后应已切换备用通道");
        assertEquals(0, task.getFailCount(), "切换后备用通道重新计数");
        assertTrue(task.getLastError() == null || !task.getLastError().contains("upstream"),
                "成功后应清除失败原因");
        verify(waybillService, times(1)).syncTrace(eq(77L), anyList());
    }

    private static void assertNullActiveChannel(TrackingSyncTask task) {
        org.junit.jupiter.api.Assertions.assertNull(task.getActiveChannel(),
                "仍在主通道重试阶段不应切换 active_channel");
    }

    @Test
    @DisplayName("重试耗尽（retry 5→6）：任务转 dead 并落 sync_dead 告警（同单同类型幂等由 countActiveByType 把关）")
    void retryExhaustedMarksDeadWithAlert() {
        worker = workerWith(new FailingAdapter(), mockAdapter);
        TrackingSyncTask task = dueTask("kuaidi100");
        task.setRetryCount(5); // 已重试 5 次，本轮第 6 次 → dead
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(alertMapper.countActiveByType(77L, TrackingExceptionService.ALERT_SYNC_DEAD)).thenReturn(0L);
        when(alertMapper.insert(any(com.lanlink.shopping.entity.LogisticsAlert.class))).thenReturn(1);

        assertEquals(0, worker.sweepOnce(T0));

        assertEquals(TrackingSyncTask.STATUS_DEAD, task.getStatus());
        assertNotNull(task.getFinishedTime());
        ArgumentCaptor<com.lanlink.shopping.entity.LogisticsAlert> alertCaptor =
                ArgumentCaptor.forClass(com.lanlink.shopping.entity.LogisticsAlert.class);
        verify(alertMapper, times(1)).insert(alertCaptor.capture());
        assertEquals(TrackingExceptionService.ALERT_SYNC_DEAD, alertCaptor.getValue().getAlertType());
        assertEquals(1, waybill.getExceptionFlag());
    }

    @Test
    @DisplayName("退避序列：1m/5m/30m/2h，超出取上限 2h")
    void backoffSequence() {
        assertEquals(60, TrackingSyncWorker.backoffSeconds(1));
        assertEquals(300, TrackingSyncWorker.backoffSeconds(2));
        assertEquals(1800, TrackingSyncWorker.backoffSeconds(3));
        assertEquals(7200, TrackingSyncWorker.backoffSeconds(4));
        assertEquals(7200, TrackingSyncWorker.backoffSeconds(7));
    }
}

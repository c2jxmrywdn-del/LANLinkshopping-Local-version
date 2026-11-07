package com.lanlink.shopping.module.tracking.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsTrace;
import com.lanlink.shopping.entity.LogisticsWaybill;
import com.lanlink.shopping.mapper.LogisticsAlertMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.LogisticsTraceMapper;
import com.lanlink.shopping.mapper.LogisticsWaybillMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.ProviderRouter;
import com.lanlink.shopping.module.tracking.adapter.TraceMerger;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.adapter.TrackingStatusMapper;
import com.lanlink.shopping.module.tracking.adapter.WaybillTrackInfo;
import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingEvent;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingEventMapper;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import com.lanlink.shopping.service.LogisticsStateMachine;
import com.lanlink.shopping.service.LogisticsWaybillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 定时补拉 worker（out/logistics-tracking-plan.md P2/P3 任务 2，补拉 = 兜底主路径）：
 * 每 10 分钟（logistics.tracking.poll-cron，对齐 SLA watchdog 的 cron 调度写法，不引入
 * XXL-Job）扫描「最后渠道事件超 4 小时仍未签收」的运单，经 ProviderRouter 主备决策后
 * 走对应通道 adapter.poll 兜底拉取。
 *
 * 多副本部署防重：tracking_sync_task 行级 CAS 认领（UPDATE ... WHERE status IN
 * (pending,failed)），与 OrderOutboxWorker 先例同一思路。
 *
 * 多实例部署预留（ShedLock 注释位）：引入 net.javacrumbs.shedlock 后，在此方法上追加
 * &#64;SchedulerLock(name = "tracking-poll-stale-waybills", lockAtMostFor = "9m") 及其表锁
 * DDL；单实例演示栈不引入（规划 §9 openQuestion #3），当前多副本防重由 CAS 认领兜底。
 *
 * 失败语义：fail_count 累计（连续失败达阈值由 ProviderRouter.decide 切备用通道）；
 * retry_count 指数退避 1m/5m/30m/2h，累计 6 次转 dead + 同步死态告警（同单同类型幂等）；
 * 渠道未配置（configured()=false）只顺延 next_poll_time，不烧重试。
 */
@Component
public class TrackingSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(TrackingSyncWorker.class);

    /** dead 转死态阈值（规划 P2 任务 2：retry_count≥6 → dead + 生成 alert） */
    static final int DEAD_RETRY_THRESHOLD = 6;
    /** 进程崩溃遗留 running 认领的回收分钟数 */
    private static final int CLAIM_STALE_MINUTES = 10;
    /** 失败退避序列秒：1m/5m/30m/2h（规划 P2 任务 2） */
    private static final long[] BACKOFF_SECONDS = {60, 300, 1800, 7200};

    private final TrackingSyncTaskMapper taskMapper;
    private final TrackingEventMapper eventMapper;
    private final LogisticsWaybillMapper waybillMapper;
    private final LogisticsTraceMapper traceMapper;
    private final LogisticsAlertMapper alertMapper;
    private final LogisticsProviderMapper providerMapper;
    private final CarrierAdapterRegistry adapterRegistry;
    private final ProviderRouter router;
    private final LogisticsWaybillService waybillService;
    private final TrackingProperties properties;
    /** CAS 认领者实例标识：进程内唯一即可（单轮巡检内同实例竞争由行级 CAS 保证） */
    private final String owner = "instance-" + Long.toHexString(System.currentTimeMillis())
            + "-" + Integer.toHexString(Runtime.getRuntime().hashCode());

    public TrackingSyncWorker(TrackingSyncTaskMapper taskMapper,
                              TrackingEventMapper eventMapper,
                              LogisticsWaybillMapper waybillMapper,
                              LogisticsTraceMapper traceMapper,
                              LogisticsAlertMapper alertMapper,
                              LogisticsProviderMapper providerMapper,
                              CarrierAdapterRegistry adapterRegistry,
                              ProviderRouter router,
                              LogisticsWaybillService waybillService,
                              TrackingProperties properties) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.waybillMapper = waybillMapper;
        this.traceMapper = traceMapper;
        this.alertMapper = alertMapper;
        this.providerMapper = providerMapper;
        this.adapterRegistry = adapterRegistry;
        this.router = router;
        this.waybillService = waybillService;
        this.properties = properties;
    }

    /** 每 10 分钟巡检（cron 写法对齐 LogisticsSlaWatchdogService.scheduledWatchdogJob） */
    @Scheduled(cron = "${logistics.tracking.poll-cron:0 */10 * * * ?}")
    public void scheduledPoll() {
        if (!properties.isEnabled()) return; // P4 灰度开关：false 时 worker 空转
        int polled = sweepOnce(LocalDateTime.now());
        if (polled > 0) {
            log.info("[tracking] 补拉巡检完成：本轮兜底补拉 {} 单", polled);
        }
    }

    /** 一轮巡检：恢复遗留认领 → 扫描超时静默任务 → CAS 认领 → 主备路由补拉。返回实际补拉单数 */
    public int sweepOnce(LocalDateTime now) {
        recoverStuckClaims(now);
        List<TrackingSyncTask> due = taskMapper.selectList(Wrappers.<TrackingSyncTask>lambdaQuery()
                .in(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_PENDING, TrackingSyncTask.STATUS_FAILED)
                .le(TrackingSyncTask::getNextPollTime, now)
                .orderByAsc(TrackingSyncTask::getTaskId));
        int polled = 0;
        for (TrackingSyncTask task : due) {
            try {
                polled += pollIfStale(task, now) ? 1 : 0;
            } catch (Exception e) {
                log.warn("[tracking] 补拉任务 {} 处理异常（下一轮重试）: {}",
                        task.getTaskId(), e.getMessage());
            }
        }
        return polled;
    }

    /** 静默判定：最后渠道事件（callback/manual，poll 记录不算更新）超 pollStaleHours 且运单未签收 */
    private boolean pollIfStale(TrackingSyncTask task, LocalDateTime now) {
        LogisticsWaybill wb = waybillMapper.selectById(task.getWaybillId());
        if (wb == null || LogisticsStateMachine.isTerminal(wb.getStatus())) {
            return false; // 已签收/终态：任务终态收敛交给成功路径置 done
        }
        LocalDateTime lastEvent = lastChannelEventTime(task.getWaybillId(), wb);
        if (lastEvent == null || !lastEvent.isBefore(now.minusHours(properties.getPollStaleHours()))) {
            return false; // 4 小时内有过渠道动态，无需兜底
        }
        TrackingChannel channel = TrackingChannel.fromCode(task.getProviderCode());
        if (channel == null) {
            return false; // 无路由（如 manual 自送），永久挂起看板可见，不烧事件行
        }
        if (!claim(task.getTaskId(), now)) {
            return false; // CAS 认领失败 = 其他实例已接手
        }
        return pollTask(task, wb, now);
    }

    /** 单任务补拉：主备路由 → poll → 双通道轨迹合并去重 → syncTrace → 成功/退避/切换/死态 */
    private boolean pollTask(TrackingSyncTask task, LogisticsWaybill wb, LocalDateTime now) {
        ProviderRouter.RouteDecision decision = router.decide(task);
        TrackingChannel channel = decision.channel();
        CarrierAdapter adapter = channel == null ? null : adapterRegistry.adapterFor(channel);

        if (adapter == null || !adapter.configured()) {
            // 渠道缺适配器/密钥未配置：顺延不烧重试（规划 P2 任务 2），不落事件行防垃圾数据
            defer(task, now, adapter == null ? "no adapter for channel "
                    + (channel == null ? task.getProviderCode() : channel.code()) : "NOT_CONFIGURED");
            return false;
        }

        LogisticsProvider provider = wb.getProviderId() != null ? providerMapper.selectById(wb.getProviderId()) : null;
        WaybillTrackInfo info = new WaybillTrackInfo(wb.getWaybillId(), wb.getWaybillNo(), wb.getOrderNo(),
                wb.getProviderId(), provider == null ? null : provider.getProviderCode(), task.getLogisticsNo());

        CarrierAdapter.PollResult result;
        try {
            result = adapter.poll(info);
        } catch (Exception e) {
            result = CarrierAdapter.PollResult.fail("poll error: " + e.getMessage());
        }

        if (!result.success()) {
            onPollFailed(task, wb, channel, result.errorMessage(), now);
            return false;
        }

        // 成功：与既有轨迹按 event_time 合并去重（不丢旧通道数据）后统一走 syncTrace
        List<NormalizedTraceNode> existing = loadExistingTraces(wb.getWaybillId());
        List<NormalizedTraceNode> merged = TraceMerger.merge(existing, result.nodes());
        int imported = 0;
        if (!merged.isEmpty()) {
            Map<String, Object> sync = waybillService.syncTrace(wb.getWaybillId(),
                    TrackingStatusMapper.toSyncNodeMaps(merged));
            imported = sync != null && sync.get("imported") instanceof Integer n ? n : 0;
        }
        insertPollEvent(task, channel, TrackingStatusMapper.maxNodeTime(result.nodes()),
                TrackingEvent.STATUS_NORMALIZED, null, imported);
        router.recordSuccess(task);

        LogisticsWaybill fresh = waybillMapper.selectById(wb.getWaybillId());
        if (fresh != null && LogisticsStateMachine.isTerminal(fresh.getStatus())) {
            task.setStatus(TrackingSyncTask.STATUS_DONE);
            task.setFinishedTime(now);
        } else {
            task.setStatus(TrackingSyncTask.STATUS_PENDING);
            task.setNextPollTime(now.plusNanos(properties.getPollIntervalMs() * 1_000_000));
        }
        task.setLastError(null);
        taskMapper.updateById(task);
        if (decision.switched()) {
            log.info("[tracking] 运单 {} 切换后经备用通道 {} 补拉成功，导入节点 {} 个",
                    wb.getWaybillNo(), channel.code(), imported);
        }
        return true;
    }

    /** 失败：fail_count 累计 + 退避；retry≥6 → dead + sync_dead 告警（同单同类型幂等） */
    private void onPollFailed(TrackingSyncTask task, LogisticsWaybill wb, TrackingChannel channel,
                              String error, LocalDateTime now) {
        String reason = "channel " + channel.code() + " poll failed: " + error;
        insertPollEvent(task, channel, null, TrackingEvent.STATUS_FAILED, reason, 0);
        router.recordFailure(task, reason);
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setRetryCount(retry);
        if (retry >= DEAD_RETRY_THRESHOLD) {
            task.setStatus(TrackingSyncTask.STATUS_DEAD);
            task.setFinishedTime(now);
            taskMapper.updateById(task);
            deadAlert(wb, reason);
            log.error("[tracking] 运单 {} 补拉重试 {} 次转死态：{}", wb.getWaybillNo(), retry, reason);
        } else {
            task.setStatus(TrackingSyncTask.STATUS_FAILED);
            task.setNextPollTime(now.plusSeconds(backoffSeconds(retry)));
            taskMapper.updateById(task);
            log.warn("[tracking] 运单 {} 补拉失败（retry={}，退避 {}s，fail_count={}）：{}",
                    wb.getWaybillNo(), retry, backoffSeconds(retry), task.getFailCount(), reason);
        }
    }

    public static long backoffSeconds(int retry) {
        int idx = Math.min(Math.max(retry, 1), BACKOFF_SECONDS.length) - 1;
        return BACKOFF_SECONDS[idx];
    }

    /** 渠道未配置/缺适配器：只顺延，不烧 retry/fail_count */
    private void defer(TrackingSyncTask task, LocalDateTime now, String reason) {
        task.setStatus(TrackingSyncTask.STATUS_PENDING);
        task.setNextPollTime(now.plusNanos(properties.getPollIntervalMs() * 1_000_000));
        task.setLastError(reason);
        taskMapper.updateById(task);
    }

    /** 行级 CAS 认领：恰一实例成功 */
    private boolean claim(Long taskId, LocalDateTime now) {
        return taskMapper.update(null, Wrappers.<TrackingSyncTask>lambdaUpdate()
                .set(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_RUNNING)
                .set(TrackingSyncTask::getOwner, owner)
                .set(TrackingSyncTask::getClaimedAt, now)
                .eq(TrackingSyncTask::getTaskId, taskId)
                .in(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_PENDING, TrackingSyncTask.STATUS_FAILED)) > 0;
    }

    /** 回收进程崩溃遗留的 running 认领（claimed_at 超时） */
    private void recoverStuckClaims(LocalDateTime now) {
        taskMapper.update(null, Wrappers.<TrackingSyncTask>lambdaUpdate()
                .set(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_PENDING)
                .set(TrackingSyncTask::getOwner, null)
                .eq(TrackingSyncTask::getStatus, TrackingSyncTask.STATUS_RUNNING)
                .le(TrackingSyncTask::getClaimedAt, now.minusMinutes(CLAIM_STALE_MINUTES)));
    }

    /** 补拉事件存档（event_type=poll，成功 normalized / 失败 failed，供失败率与延迟指标聚合） */
    private void insertPollEvent(TrackingSyncTask task, TrackingChannel channel, LocalDateTime eventTime,
                                 String status, String error, int normalizedCount) {
        try {
            TrackingEvent event = new TrackingEvent();
            event.setProviderCode(channel == null ? task.getProviderCode() : channel.code());
            event.setExternalEventId("poll:" + task.getTaskId() + ":" + System.currentTimeMillis());
            event.setWaybillId(task.getWaybillId());
            event.setLogisticsNo(task.getLogisticsNo());
            event.setEventType(TrackingEvent.TYPE_POLL);
            event.setStatus(status);
            event.setEventTime(eventTime);
            event.setNormalizedCount(normalizedCount);
            event.setErrorMsg(error);
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("[tracking] 补拉事件存档失败 taskId={}: {}", task.getTaskId(), e.getMessage());
        }
    }

    /** 既有轨迹节点（旧通道已落库数据，合并去重时的保留基线） */
    private List<NormalizedTraceNode> loadExistingTraces(Long waybillId) {
        List<LogisticsTrace> traces = traceMapper.selectList(Wrappers.<LogisticsTrace>lambdaQuery()
                .eq(LogisticsTrace::getWaybillId, waybillId));
        return traces.stream()
                .map(t -> NormalizedTraceNode.of(t.getTraceTime(), t.getLocation(), t.getDescription()))
                .toList();
    }

    /** 最后一次渠道事件时间（排除 poll 记录；无事件回退发货/创建时间） */
    private LocalDateTime lastChannelEventTime(Long waybillId, LogisticsWaybill wb) {
        List<TrackingEvent> latest = eventMapper.selectList(Wrappers.<TrackingEvent>lambdaQuery()
                .eq(TrackingEvent::getWaybillId, waybillId)
                .ne(TrackingEvent::getEventType, TrackingEvent.TYPE_POLL)
                .orderByDesc(TrackingEvent::getCreateTime)
                .last("LIMIT 1"));
        if (!latest.isEmpty() && latest.get(0).getCreateTime() != null) {
            return latest.get(0).getCreateTime();
        }
        return wb.getShipTime() != null ? wb.getShipTime() : wb.getCreateTime();
    }

    /** 死态告警（同单同类型未 resolved 幂等，对齐 watchdog 口径） */
    private void deadAlert(LogisticsWaybill wb, String reason) {
        try {
            long active = alertMapper.countActiveByType(wb.getWaybillId(), TrackingExceptionService.ALERT_SYNC_DEAD);
            if (active > 0) return;
            LogisticsAlert alert = new LogisticsAlert();
            alert.setWaybillId(wb.getWaybillId());
            alert.setAlertType(TrackingExceptionService.ALERT_SYNC_DEAD);
            alert.setAlertLevel("high");
            alert.setContent(truncate("运单 " + wb.getWaybillNo() + " 轨迹补拉连续失败转死态：" + reason, 255));
            alert.setStatus("pending");
            alert.setCreateTime(LocalDateTime.now());
            alertMapper.insert(alert);
            wb.setExceptionFlag(1);
            waybillMapper.updateById(wb);
        } catch (Exception e) {
            log.warn("[tracking] 死态告警落库失败 waybillId={}: {}", wb.getWaybillId(), e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}

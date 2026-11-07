package com.lanlink.shopping.module.tracking.service;

import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.CarrierAdapterRegistry;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.adapter.WaybillTrackInfo;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.event.WaybillShippedEvent;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 发货事件 → tracking 建档（out/logistics-tracking-plan.md D3/P1 任务 6）。
 *
 * AFTER_COMMIT（对齐 OrderPaidBridgeListener 既有模式，fallbackExecution 兜底非事务发布）：
 * 事务提交后才动作，规避"运单回滚但补拉任务已建"的双写不一致。
 * 隔离原则：任何失败仅记 ERROR 日志、不上抛——AFTER_COMMIT 阶段抛出会穿透发货入口，
 * 造成"运单已建但接口报错"；补拉任务缺失可由看板/对账自愈（P2/P3）。
 *
 * 运单 track_channel/track_subscribe_status 是热扩列（TrackingSchemaInitializer），
 * 实体类不含这两列，用 JdbcTemplate 直写，避免为 tracking 侧需求改动物流域实体。
 */
@Component
public class WaybillTrackingListener {

    private static final Logger log = LoggerFactory.getLogger(WaybillTrackingListener.class);

    private final TrackingSyncTaskMapper taskMapper;
    private final LogisticsProviderMapper providerMapper;
    private final CarrierAdapterRegistry adapterRegistry;
    private final JdbcTemplate jdbcTemplate;

    public WaybillTrackingListener(TrackingSyncTaskMapper taskMapper,
                                   LogisticsProviderMapper providerMapper,
                                   CarrierAdapterRegistry adapterRegistry,
                                   JdbcTemplate jdbcTemplate) {
        this.taskMapper = taskMapper;
        this.providerMapper = providerMapper;
        this.adapterRegistry = adapterRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWaybillShipped(WaybillShippedEvent event) {
        try {
            String providerCode = null;
            String apiType = "mock";
            LogisticsProvider provider = event.getProviderId() != null
                    ? providerMapper.selectById(event.getProviderId()) : null;
            if (provider != null) {
                providerCode = provider.getProviderCode();
                apiType = provider.getApiType();
            }
            TrackingChannel channel = adapterRegistry.resolveChannel(providerCode, apiType);

            // 首条补拉任务：uk_task_waybill 一单一任务，重复发货并发冲突即忽略
            TrackingSyncTask task = new TrackingSyncTask();
            task.setWaybillId(event.getWaybillId());
            task.setProviderCode(channel == null ? "unknown" : channel.code());
            task.setLogisticsNo(event.getLogisticsNo());
            task.setStatus(TrackingSyncTask.STATUS_PENDING);
            task.setNextPollTime(LocalDateTime.now());
            task.setRetryCount(0);
            try {
                taskMapper.insert(task);
            } catch (DuplicateKeyException dup) {
                log.info("[tracking] 运单 {} 补拉任务已存在，跳过", event.getWaybillNo());
            }

            String subscribeStatus;
            String channelCode = channel == null ? null : channel.code();
            CarrierAdapter adapter = adapterRegistry.adapterFor(channel);
            if (adapter == null) {
                // P1 仅 mock+kuaidi100 有适配器；kdniao/track17/aftership 为 P2 骨架
                subscribeStatus = "unconfigured";
            } else if (!adapter.configured()) {
                subscribeStatus = "unconfigured";
            } else if (event.getLogisticsNo() == null || event.getLogisticsNo().isBlank()) {
                subscribeStatus = "skipped";
            } else {
                CarrierAdapter.SubscribeResult r = adapter.subscribe(
                        new WaybillTrackInfo(event.getWaybillId(), event.getWaybillNo(),
                                event.getOrderNo(), event.getProviderId(), providerCode, event.getLogisticsNo()));
                subscribeStatus = r.success() ? "subscribed" : "failed";
                if (!r.success()) {
                    log.warn("[tracking] 运单 {} 渠道 {} 订阅失败（补拉兜底）: {}",
                            event.getWaybillNo(), channelCode, r.message());
                }
            }

            if (event.getWaybillId() != null) {
                jdbcTemplate.update(
                        "UPDATE t_logistics_waybill SET track_channel = ?, track_subscribe_status = ? WHERE waybill_id = ?",
                        channelCode, subscribeStatus, event.getWaybillId());
            }
            log.info("[tracking] 运单 {} 发货建档完成 channel={} subscribe={}",
                    event.getWaybillNo(), channelCode, subscribeStatus);
        } catch (Exception e) {
            // AFTER_COMMIT 阶段不上抛：发货主流程已提交，tracking 侧失败靠补拉/看板兜底
            log.error("[tracking] 运单发货事件处理失败 waybillNo={}（发货主流程不受影响）",
                    event.getWaybillNo(), e);
        }
    }
}

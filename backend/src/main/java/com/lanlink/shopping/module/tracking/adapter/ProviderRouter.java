package com.lanlink.shopping.module.tracking.adapter;

import com.lanlink.shopping.module.tracking.config.TrackingProperties;
import com.lanlink.shopping.module.tracking.domain.TrackingSyncTask;
import com.lanlink.shopping.module.tracking.mapper.TrackingSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 双通道路由主备决策（out/logistics-tracking-plan.md P2/P3 任务 1）：
 * 主通道（tracking_sync_task.provider_code 记录的渠道）连续失败达到阈值
 * （logistics.tracking.primary-fail-threshold，默认 3 次）即切换备用通道
 * （logistics.tracking.fallback-routes 主→备映射），fail_count 热扩列累计、
 * 成功清零；切换动作持久化到 active_channel 热扩列并落切换日志（task.last_error +
 * WARN 日志），跨重启/跨实例生效。未配置备用映射的渠道不切换（继续原渠道退避重试）。
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);

    private final TrackingProperties properties;
    private final TrackingSyncTaskMapper taskMapper;

    public ProviderRouter(TrackingProperties properties, TrackingSyncTaskMapper taskMapper) {
        this.properties = properties;
        this.taskMapper = taskMapper;
    }

    /** 路由决策：switched=true 表示本次触发主→备切换（调用方拿到的 channel 已是备用通道） */
    public record RouteDecision(TrackingChannel channel, boolean switched, String switchReason) {}

    /** 当前生效通道：active_channel 热扩列优先，未设置回退主通道（provider_code） */
    public TrackingChannel currentChannel(TrackingSyncTask task) {
        TrackingChannel active = TrackingChannel.fromCode(task.getActiveChannel());
        if (active != null) return active;
        return TrackingChannel.fromCode(task.getProviderCode());
    }

    int failCount(TrackingSyncTask task) {
        return task.getFailCount() == null ? 0 : task.getFailCount();
    }

    /**
     * 主备决策（补拉前调用）：仍在主通道且连续失败已达阈值、且配置了备用通道 →
     * 切换并持久化（active_channel=备用渠道、fail_count 清零、last_error 记切换原因）。
     * 已在备用通道或未达阈值 → 维持当前通道。
     */
    public RouteDecision decide(TrackingSyncTask task) {
        TrackingChannel primary = TrackingChannel.fromCode(task.getProviderCode());
        TrackingChannel current = currentChannel(task);
        if (primary != null && current == primary
                && failCount(task) >= properties.getPrimaryFailThreshold()) {
            TrackingChannel fallback = fallbackOf(primary);
            if (fallback != null) {
                String reason = "primary channel " + primary.code() + " failed "
                        + failCount(task) + " consecutive times";
                task.setActiveChannel(fallback.code());
                task.setFailCount(0);
                task.setLastError(truncate("switched " + primary.code() + " -> " + fallback.code()
                        + ": " + reason, 480));
                taskMapper.updateById(task);
                log.warn("[tracking] 运单 {} 主通道 {} 连续失败 {} 次，切换备用通道 {}（{}）",
                        task.getWaybillId(), primary.code(), failCount(task), fallback.code(), reason);
                return new RouteDecision(fallback, true, reason);
            }
        }
        return new RouteDecision(current, false, null);
    }

    /** 失败累计：当前生效通道连续失败 fail_count+1（切换发生在下一次 decide） */
    public void recordFailure(TrackingSyncTask task, String reason) {
        task.setFailCount(failCount(task) + 1);
        if (reason != null && !reason.isBlank()) {
            task.setLastError(truncate(reason, 480));
        }
        taskMapper.updateById(task);
    }

    /** 成功：当前生效通道连续失败清零（已在 0 且无 last_error 时不写库） */
    public void recordSuccess(TrackingSyncTask task) {
        if (failCount(task) == 0 && task.getLastError() == null) return;
        task.setFailCount(0);
        task.setLastError(null);
        taskMapper.updateById(task);
    }

    /** 主→备映射；未配置或映射到自己 → null（不切换） */
    private TrackingChannel fallbackOf(TrackingChannel primary) {
        String code = properties.getFallbackRoutes().get(primary.code());
        TrackingChannel fallback = TrackingChannel.fromCode(code);
        return fallback == null || fallback == primary ? null : fallback;
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}

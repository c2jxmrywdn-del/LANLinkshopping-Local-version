package com.lanlink.shopping.support;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * AI 外呼配额闸：参数化窗口计数 + 可选熔断状态（out/ai-polish-design.md §3.4/§3.7）。
 *
 * <p>窗口计数按 key 分桶（商户级传 userId，全局层传固定键），同一 key 在窗口内累加，
 * 超过 limit 返回 false 且不产生副作用（调用方不外呼）。limit <= 0 视为不限。
 * 条目容量上限防内存膨胀：满时惰性淘汰已过窗口条目，仍满则拒绝新 key（对齐
 * AuthRateLimitInterceptor 的 MAX_COUNTERS 处理思路）。
 *
 * <p>熔断状态（aiDisabledUntil）独立于计数：全渠道失败时由调用方 trip，
 * 冷却期内 circuitOpen()=true，先于配额检查（先查熔断→再查配额，PriceMonitorAiService.callAi 同序）。
 *
 * <p>PriceMonitorAiService 迁移到本组件为 P2（既有 PriceMonitorAiServiceTest 行为兜底，P0 不动稳定链路）。
 */
public class AiQuotaGate {

    /** 桶容量上限：商户级按 userId 分桶，惰性淘汰已过窗口条目，防长尾用户撑爆内存。 */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final String keyPrefix;
    private final long limit;
    private final long windowMs;
    private final int maxEntries;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /** 全渠道失败熔断到期时间戳（0=未熔断）；同实例内 volatile，跨请求共享。 */
    private volatile long disabledUntil = 0L;

    public AiQuotaGate(String keyPrefix, long limit, long windowMs, LongSupplier clock) {
        this(keyPrefix, limit, windowMs, DEFAULT_MAX_ENTRIES, clock);
    }

    public AiQuotaGate(String keyPrefix, long limit, long windowMs, int maxEntries, LongSupplier clock) {
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.limit = limit;
        this.windowMs = windowMs;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 窗口内计数 +1；超限（或桶满且无法腾位）返回 false。limit<=0 = 不限，恒返回 true。 */
    public boolean tryAcquire(String key) {
        if (limit <= 0) return true;
        long now = clock.getAsLong();
        WindowCounter counter = counters.get(keyPrefix + ":" + key);
        if (counter == null) {
            if (counters.size() >= maxEntries) {
                evictExpired(now);
            }
            if (counters.size() >= maxEntries) {
                return false;
            }
            WindowCounter candidate = new WindowCounter();
            WindowCounter previous = counters.putIfAbsent(keyPrefix + ":" + key, candidate);
            counter = previous == null ? candidate : previous;
        }
        return counter.tryAcquire(limit, windowMs, now);
    }

    /** 本窗口已消耗次数（观测/测试用；不推进窗口）。 */
    public long used(String key) {
        WindowCounter counter = counters.get(keyPrefix + ":" + key);
        return counter == null ? 0 : counter.currentCount();
    }

    /** 是否处于熔断冷却期（冷却期内调用方不得外呼，也不消耗配额）。 */
    public boolean circuitOpen() {
        return clock.getAsLong() < disabledUntil;
    }

    /** 全渠道失败后进入冷却。 */
    public void tripCircuit(long cooldownMs) {
        disabledUntil = clock.getAsLong() + cooldownMs;
    }

    /** 手动解除熔断（如管理端触发配置变更后）。 */
    public void resetCircuit() {
        disabledUntil = 0L;
    }

    /** 熔断剩余毫秒数（未熔断为 0）。 */
    public long circuitRemainMs() {
        return Math.max(0L, disabledUntil - clock.getAsLong());
    }

    private void evictExpired(long now) {
        counters.entrySet().removeIf(e -> e.getValue().isExpired(windowMs, now));
    }

    private static final class WindowCounter {
        private long windowStart;
        private final AtomicInteger count = new AtomicInteger();

        private synchronized boolean tryAcquire(long limit, long windowMs, long now) {
            if (windowStart == 0L || now < windowStart || now - windowStart >= windowMs) {
                windowStart = now;
                count.set(0);
            }
            if (count.incrementAndGet() > limit) {
                return false;
            }
            return true;
        }

        private synchronized boolean isExpired(long windowMs, long now) {
            return windowStart != 0L && now - windowStart >= windowMs;
        }

        private synchronized long currentCount() {
            return count.get();
        }
    }
}

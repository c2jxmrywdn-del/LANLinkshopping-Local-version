package com.lanlink.shopping.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播互动限流守卫（IP 维度滑动窗口近似）。
 * 公开互动端点（join/leave/like/click/comment）匿名可达，必须限流防止
 * 脚本刷写在线数/峰值/点击量等"真实计数"闭环指标。超限返回 false，控制器静默降级。
 * 内存窗口自清理（>5 万 key 时剔除过期项），单机部署足够；多实例部署换 Redis 实现。
 */
@Component
public class LiveRateGuard {

    /** key -> [窗口起始毫秒, 计数] */
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    /**
     * 判定是否放行。
     * @param key 限流维度键（建议 ip:action:target）
     * @param limit 窗口内允许次数
     * @param windowMillis 窗口时长
     */
    public boolean allow(String key, int limit, long windowMillis) {
        if (key == null) return false;
        long now = System.currentTimeMillis();
        long[] cur = hits.compute(key, (k, v) -> {
            if (v == null || now - v[0] >= windowMillis) return new long[]{now, 1};
            v[1] = v[1] + 1;
            return v;
        });
        if (hits.size() > 50_000) {
            hits.entrySet().removeIf(e -> now - e.getValue()[0] >= Math.max(60_000, windowMillis));
        }
        return cur[1] <= limit;
    }
}

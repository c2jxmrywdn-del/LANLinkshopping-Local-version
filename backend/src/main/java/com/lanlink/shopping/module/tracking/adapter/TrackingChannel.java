package com.lanlink.shopping.module.tracking.adapter;

/**
 * 跟踪渠道标识（out/logistics-tracking-plan.md §2.2 路由规则）。
 * 回调 URL /logistics/callback/{provider} 的 {provider} 段与本枚举 code 同一词汇表。
 * P1 只落 MOCK/KUAIDI100 两个适配器实现，其余为 P2 骨架占位。
 */
public enum TrackingChannel {

    MOCK("mock"),
    KUAIDI100("kuaidi100"),
    KDNIAO("kdniao"),
    TRACK17("track17"),
    AFTERSHIP("aftership");

    private final String code;

    TrackingChannel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 未收录的编码返回 null（调用方决定兜底策略），不静默归一到某一渠道 */
    public static TrackingChannel fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        for (TrackingChannel c : values()) {
            if (c.code.equalsIgnoreCase(code.trim())) return c;
        }
        return null;
    }
}

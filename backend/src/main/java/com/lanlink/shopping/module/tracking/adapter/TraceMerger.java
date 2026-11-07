package com.lanlink.shopping.module.tracking.adapter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 双通道轨迹合并（out/logistics-tracking-plan.md P2/P3 任务 1：主通道切到备用通道后，
 * 两通道轨迹按 event_time 合并去重，不丢旧通道数据）。
 *
 * 合并规则：以 event_time（分钟精度，容忍渠道间秒级抖动）为去重键；
 * 同一时间点两侧都有节点视为同一物理事件，只保留旧通道节点（旧通道数据已对用户可见，
 * 保留不动），新通道只补充旧通道没有的时间点；无时间节点不参与去重、原样保留；
 * 结果按时间升序。落库仍统一走 syncTrace（time+desc 节点级查重 + uk_trace_unique 兜底）。
 */
public final class TraceMerger {

    private TraceMerger() {
    }

    /** 分钟精度 event_time 键（容忍渠道间秒级抖动）；null 时间不参与去重 */
    static LocalDateTime key(LocalDateTime traceTime) {
        return traceTime == null ? null : traceTime.truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * 旧通道节点优先合并：oldChannel 全量保留，newChannel 仅保留旧通道没有的时间点。
     * 新旧两侧同时间点措辞不同（渠道文案差异）也视为同一事件去重。
     */
    public static List<NormalizedTraceNode> merge(List<NormalizedTraceNode> oldChannelNodes,
                                                  List<NormalizedTraceNode> newChannelNodes) {
        Map<LocalDateTime, NormalizedTraceNode> byTime = new TreeMap<>();
        List<NormalizedTraceNode> noTime = new ArrayList<>();
        collect(oldChannelNodes, byTime, noTime);
        collect(newChannelNodes, byTime, noTime);
        List<NormalizedTraceNode> out = new ArrayList<>(byTime.values());
        out.addAll(noTime);
        return List.copyOf(out);
    }

    private static void collect(List<NormalizedTraceNode> nodes,
                                Map<LocalDateTime, NormalizedTraceNode> byTime,
                                List<NormalizedTraceNode> noTime) {
        if (nodes == null) return;
        for (NormalizedTraceNode node : nodes) {
            if (node == null) continue;
            LocalDateTime k = key(node.getTraceTime());
            if (k == null) {
                noTime.add(node);
            } else {
                byTime.putIfAbsent(k, node);
            }
        }
    }
}

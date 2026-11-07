package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.TraceMerger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双通道轨迹合并去重单测（out/logistics-tracking-plan.md P2/P3 任务 1 / ask 任务 6「合并去重」）：
 * 主通道切备用后，两通道轨迹按 event_time（分钟精度）合并——同一物理事件（含措辞差异）只保留
 * 旧通道节点，新通道仅补充旧通道没有的时间点，旧通道数据一条不丢。
 */
class TrackingTraceMergeTest {

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 11, 6, 10, 0, 0);
    private static final LocalDateTime T11 = LocalDateTime.of(2026, 11, 6, 11, 0, 0);
    private static final LocalDateTime T12 = LocalDateTime.of(2026, 11, 6, 12, 0, 0);

    @Test
    @DisplayName("切换后合并：同 event_time 的双通道节点去重且保留旧通道版本，新时间点补充，旧数据不丢")
    void mergeDedupsSameEventTimeKeepingOldChannelData() {
        List<NormalizedTraceNode> oldChannel = List.of(
                NormalizedTraceNode.of(T10, "苏州", "旧通道：已到达苏州转运中心"),
                NormalizedTraceNode.of(T11, "无锡", "旧通道：离开无锡中转"));
        List<NormalizedTraceNode> newChannel = List.of(
                // 同一物理事件的备用通道措辞（去重对象）
                NormalizedTraceNode.of(T10, "苏州", "备用通道：包裹抵达苏州集散点"),
                // 备用通道新事件
                NormalizedTraceNode.of(T12, "南京", "备用通道：到达南京转运中心"));

        List<NormalizedTraceNode> merged = TraceMerger.merge(oldChannel, newChannel);

        assertEquals(3, merged.size(), "同时间点去重后应剩 3 个节点");
        // 旧通道数据一条不丢
        assertTrue(merged.stream().anyMatch(n -> n.getDescription().startsWith("旧通道：已到达")));
        assertTrue(merged.stream().anyMatch(n -> n.getDescription().startsWith("旧通道：离开无锡")));
        // 备用通道补充的新时间点
        assertTrue(merged.stream().anyMatch(n -> n.getDescription().startsWith("备用通道：到达南京")));
        // 同一时间点只保留旧通道版本，备用通道措辞被去重
        assertTrue(merged.stream().noneMatch(n -> n.getDescription().startsWith("备用通道：包裹抵达")));
        assertEquals(T10, merged.get(0).getTraceTime());
        assertEquals(T11, merged.get(1).getTraceTime());
        assertEquals(T12, merged.get(2).getTraceTime());
    }

    @Test
    @DisplayName("秒级抖动容错：同分钟不同秒视为同一 event_time")
    void sameMinuteDifferentSecondsDedup() {
        List<NormalizedTraceNode> merged = TraceMerger.merge(
                List.of(NormalizedTraceNode.of(T10.withSecond(5), null, "旧通道节点")),
                List.of(NormalizedTraceNode.of(T10.withSecond(48), null, "备用通道节点")));
        assertEquals(1, merged.size());
        assertEquals("旧通道节点", merged.get(0).getDescription());
    }

    @Test
    @DisplayName("全异时间点：两侧节点全量保留（联合不丢数据）")
    void distinctTimesAllKept() {
        List<NormalizedTraceNode> merged = TraceMerger.merge(
                List.of(NormalizedTraceNode.of(T10, null, "a")),
                List.of(NormalizedTraceNode.of(T11, null, "b")));
        assertEquals(2, merged.size());
    }

    @Test
    @DisplayName("空/null 集合与无时间节点：原样保留不参与去重")
    void emptyAndNullTimeNodesHandled() {
        assertTrue(TraceMerger.merge(null, null).isEmpty());
        assertTrue(TraceMerger.merge(List.of(), List.of()).isEmpty());

        NormalizedTraceNode noTime = new NormalizedTraceNode();
        noTime.setDescription("无时间节点");
        List<NormalizedTraceNode> merged = TraceMerger.merge(
                List.of(NormalizedTraceNode.of(T10, null, "a")), List.of(noTime));
        assertEquals(2, merged.size());
        assertTrue(merged.stream().anyMatch(n -> "无时间节点".equals(n.getDescription())));
    }
}

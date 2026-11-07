package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.TrackingStatusMapper;
import com.lanlink.shopping.service.LogisticsStateMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 七态收敛映射单测（out/logistics-tracking-plan.md P2/P3 任务 3 + ask 任务 4/6）：
 * 七态收敛表 → 平台 9 态全覆盖；异常关键词命中（派送失败/拒收/退回）→ EXCEPTION/RETURNED；
 * 「派送失败」必须先于「派送」规则命中 EXCEPTION（既有 LogisticsTraceParser 的误判风险在本层修正）。
 */
class TrackingStatusMapperTest {

    @Test
    @DisplayName("七态收敛表：PENDING/INFO_RECEIVED→picked_up，其余按表收敛到平台 9 态")
    void sevenStatesConvergeToPlatformStates() {
        assertEquals(LogisticsStateMachine.PICKED_UP,
                TrackingStatusMapper.map("pending", "渠道自定义文案").platformState());
        assertEquals(LogisticsStateMachine.PICKED_UP,
                TrackingStatusMapper.map("info_received", "包裹信息已收录").platformState());
        assertEquals(LogisticsStateMachine.IN_TRANSIT,
                TrackingStatusMapper.map("in_transit", "运输中").platformState());
        assertEquals(LogisticsStateMachine.OUT_FOR_DELIVERY,
                TrackingStatusMapper.map("out_for_delivery", "派送中").platformState());
        assertEquals(LogisticsStateMachine.DELIVERED,
                TrackingStatusMapper.map("delivered", "已签收").platformState());
        assertEquals(LogisticsStateMachine.EXCEPTION,
                TrackingStatusMapper.map("exception", "包裹异常").platformState());
        assertEquals(LogisticsStateMachine.RETURNED,
                TrackingStatusMapper.map("returned", "已退回").platformState());
    }

    @Test
    @DisplayName("关键词命中：派送失败/投递失败 → EXCEPTION（不被「派送」吞成 out_for_delivery）")
    void deliveryFailureKeywordHitsException() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("快件派送失败，正在联系收件人");
        assertEquals(TrackingStatusMapper.ChannelStatusCategory.EXCEPTION, mapped.category());
        assertEquals(LogisticsStateMachine.EXCEPTION, mapped.platformState());
        assertTrue(mapped.abnormal());
        assertEquals("delivery_failed", mapped.alertType());
        assertFalse(mapped.ambiguous());
    }

    @Test
    @DisplayName("关键词命中：拒收/拒签 → EXCEPTION + reject 告警类型")
    void rejectKeywordHitsException() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("客户拒收，包裹待处理");
        assertEquals(LogisticsStateMachine.EXCEPTION, mapped.platformState());
        assertEquals("reject", mapped.alertType());
        assertTrue(mapped.abnormal());
    }

    @Test
    @DisplayName("关键词命中：退回/退件 → RETURNED + return 告警类型")
    void returnKeywordHitsReturned() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("包裹已退回原寄地");
        assertEquals(TrackingStatusMapper.ChannelStatusCategory.RETURNED, mapped.category());
        assertEquals(LogisticsStateMachine.RETURNED, mapped.platformState());
        assertEquals("return", mapped.alertType());
    }

    @Test
    @DisplayName("丢失关键词：收敛为 EXCEPTION 态但平台态保留更精细的 lost")
    void lostKeywordRefinesToLostPlatformState() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("快件丢失，已启动理赔");
        assertEquals(TrackingStatusMapper.ChannelStatusCategory.EXCEPTION, mapped.category());
        assertEquals(LogisticsStateMachine.LOST, mapped.platformState());
        assertEquals("lost", mapped.alertType());
    }

    @Test
    @DisplayName("结构化状态优先：delivered 状态覆盖中性描述，不被关键词推导干扰")
    void structuredStatusWinsOverDescription() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("delivered", "包裹已到达苏州转运中心");
        assertEquals(LogisticsStateMachine.DELIVERED, mapped.platformState());
        assertFalse(mapped.ambiguous());
    }

    @Test
    @DisplayName("未知状态保守归 in_transit 并标记 ambiguous（人工纠偏入口可见）")
    void unknownDescriptionConservativeInTransit() {
        TrackingStatusMapper.Mapped mapped = TrackingStatusMapper.map("渠道私有状态码 X-77（无关键词）");
        assertEquals(LogisticsStateMachine.IN_TRANSIT, mapped.platformState());
        assertTrue(mapped.ambiguous());
    }

    @Test
    @DisplayName("toSyncNodeMaps：节点显式携带七态收敛结果下传 syncTrace（含 traceTime/description/location/status）")
    void toSyncNodeMapsCarriesConvergedStatus() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 6, 10, 0);
        NormalizedTraceNode node = NormalizedTraceNode.of(t, "苏州", "快件派送失败，等待处理");

        List<Map<String, Object>> maps = TrackingStatusMapper.toSyncNodeMaps(List.of(node));

        assertEquals(1, maps.size());
        Map<String, Object> m = maps.get(0);
        assertEquals(t, m.get("traceTime"));
        assertEquals("快件派送失败，等待处理", m.get("description"));
        assertEquals("苏州", m.get("location"));
        assertEquals(LogisticsStateMachine.EXCEPTION, m.get("status"));
    }

    @Test
    @DisplayName("maxNodeTime：取渠道侧最新节点时间（event_time 列，延迟指标分母）；空集合为 null")
    void maxNodeTimePicksLatest() {
        LocalDateTime early = LocalDateTime.of(2026, 11, 6, 8, 0);
        LocalDateTime late = LocalDateTime.of(2026, 11, 6, 12, 30);
        assertEquals(late, TrackingStatusMapper.maxNodeTime(List.of(
                NormalizedTraceNode.of(late, null, "b"),
                NormalizedTraceNode.of(early, null, "a"))));
        org.junit.jupiter.api.Assertions.assertNull(TrackingStatusMapper.maxNodeTime(List.of()));
    }
}

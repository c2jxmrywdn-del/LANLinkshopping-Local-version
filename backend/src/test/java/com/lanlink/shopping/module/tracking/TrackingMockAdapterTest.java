package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.adapter.CarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.MockCarrierAdapter;
import com.lanlink.shopping.module.tracking.adapter.NormalizedTraceNode;
import com.lanlink.shopping.module.tracking.adapter.TrackingChannel;
import com.lanlink.shopping.module.tracking.adapter.WaybillTrackInfo;
import com.lanlink.shopping.service.LogisticsStateMachine;
import com.lanlink.shopping.service.LogisticsTraceParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock 渠道适配器单测（out/logistics-tracking-plan.md P1 任务 5 / 演示主线 D8）：
 * 可编程轨迹 + 异常节点注入 + 回调原文解析。
 */
class TrackingMockAdapterTest {

    private final MockCarrierAdapter adapter = new MockCarrierAdapter();

    @Test
    @DisplayName("parse：nodes 数组形态逐节点解析，时间/地点/描述齐全")
    void parsesNodesArrayBody() {
        String body = """
                {"eventId":"EVT-1","logisticsNo":"SF123","nodes":[
                  {"time":"2026-11-06 10:00:00","location":"苏州转运中心","description":"包裹已到达苏州转运中心"},
                  {"time":"2026-11-06 12:30:00","location":"无锡","description":"包裹已发出，下一站南京"}
                ]}
                """;
        List<NormalizedTraceNode> nodes = adapter.parse(body);
        assertEquals(2, nodes.size());
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 0, 0), nodes.get(0).getTraceTime());
        assertEquals("苏州转运中心", nodes.get(0).getLocation());
        assertEquals("包裹已到达苏州转运中心", nodes.get(0).getDescription());
        assertEquals("包裹已发出，下一站南京", nodes.get(1).getDescription());
    }

    @Test
    @DisplayName("parse：单事件正文形态（顶层 time/description）解析为 1 节点")
    void parsesSingleEventBody() {
        List<NormalizedTraceNode> nodes = adapter.parse(
                "{\"time\":\"2026-11-06 09:15:00\",\"description\":\"商家已发货并完成包裹打包\"}");
        assertEquals(1, nodes.size());
        assertEquals("商家已发货并完成包裹打包", nodes.get(0).getDescription());
        assertNull(nodes.get(0).getLocation());
    }

    @Test
    @DisplayName("异常节点注入：滞留描述经 LogisticsTraceParser 推导为 exception+delay 告警链路输入")
    void abnormalNodeInjectionFeedsParser() {
        // 演示/测试的异常注入方式 = 场景描述携带异常关键词，解析与告警复用既有 parser 语义
        List<NormalizedTraceNode> nodes = adapter.parse(
                "{\"nodes\":[{\"time\":\"2026-11-06 08:00:00\",\"description\":\"包裹滞留于无锡分拨中心，已超24小时\"}]}");
        assertEquals(1, nodes.size());

        LogisticsTraceParser.ParsedNode parsed =
                new LogisticsTraceParser().parse(nodes.get(0).getDescription());
        assertEquals(LogisticsStateMachine.EXCEPTION, parsed.status());
        assertEquals("delay", parsed.alertType());
        assertTrue(parsed.abnormal());
    }

    @Test
    @DisplayName("poll：registerScenario 编程的轨迹脚本全量返回（节点级去重交给 syncTrace）")
    void pollReturnsProgrammedScenario() {
        NormalizedTraceNode n1 = NormalizedTraceNode.of(LocalDateTime.now(), "苏州", "包裹已到达苏州转运中心");
        NormalizedTraceNode n2 = NormalizedTraceNode.of(LocalDateTime.now(), "无锡", "包裹滞留于无锡分拨中心");
        adapter.registerScenario("SF-DEMO-1", List.of(n1, n2));

        CarrierAdapter.PollResult result = adapter.poll(new WaybillTrackInfo(
                77L, "LL202611060001", "ORD-1", 1L, "SF", "SF-DEMO-1"));
        assertTrue(result.success());
        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getDescription().contains("滞留")));
    }

    @Test
    @DisplayName("非法 JSON 抛出可识别异常（上层按 event failed + 成功应答处理）")
    void invalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parse("not-json"));
    }

    @Test
    @DisplayName("channel/configured/subscribe：Mock 恒可用，订阅即接受")
    void channelBasics() {
        assertEquals(TrackingChannel.MOCK, adapter.channel());
        assertTrue(adapter.configured());
        assertTrue(adapter.subscribe(new WaybillTrackInfo(
                77L, "LL1", "ORD-1", 1L, "SF", "SF-1")).success());
    }
}

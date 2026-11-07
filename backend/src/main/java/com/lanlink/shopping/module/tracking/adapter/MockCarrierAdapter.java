package com.lanlink.shopping.module.tracking.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 渠道适配器（out/logistics-tracking-plan.md D8 演示优先）：测试与本地演示用，
 * 零外部依赖跑通 发货→回调/补拉→轨迹→状态→告警 全链。
 * 可编程轨迹序列（registerScenario）供补拉演示；异常节点注入 = 场景里携带
 * "滞留/丢失/退回"等异常关键词描述，由 LogisticsTraceParser 推导 abnormal+alertType。
 */
@Component
public class MockCarrierAdapter implements CarrierAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** logisticsNo → 轨迹脚本（poll 全量返回，节点级去重由 syncTrace/uk_trace_unique 兜底） */
    private final Map<String, List<NormalizedTraceNode>> scenarios = new ConcurrentHashMap<>();

    public void registerScenario(String logisticsNo, List<NormalizedTraceNode> script) {
        if (logisticsNo != null) {
            scenarios.put(logisticsNo, script == null ? List.of() : List.copyOf(script));
        }
    }

    public void clearScenarios() {
        scenarios.clear();
    }

    @Override
    public TrackingChannel channel() {
        return TrackingChannel.MOCK;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public SubscribeResult subscribe(WaybillTrackInfo waybill) {
        return SubscribeResult.ok("mock-accepted");
    }

    @Override
    public PollResult poll(WaybillTrackInfo waybill) {
        if (waybill == null || waybill.logisticsNo() == null) {
            return PollResult.fail("mock poll missing logisticsNo");
        }
        return PollResult.ok(scenarios.getOrDefault(waybill.logisticsNo(), List.of()));
    }

    /**
     * Mock 推送原文 → 规范化节点。支持两种形态：
     * {"eventId":..,"logisticsNo":..,"nodes":[{time,location,description,abnormal?,longitude?,latitude?,city?,hubType?}]}
     * 或单事件正文 {time|traceTime, location, description, ...}
     */
    @Override
    public List<NormalizedTraceNode> parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return List.of();
        JsonNode root;
        try {
            root = JSON.readTree(rawBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("mock callback body is not valid JSON", e);
        }
        List<NormalizedTraceNode> out = new ArrayList<>();
        JsonNode nodes = root.get("nodes");
        if (nodes != null && nodes.isArray()) {
            for (JsonNode n : nodes) {
                NormalizedTraceNode node = toNode(n);
                if (node != null) out.add(node);
            }
        } else {
            NormalizedTraceNode node = toNode(root);
            if (node != null) out.add(node);
        }
        return out;
    }

    private NormalizedTraceNode toNode(JsonNode n) {
        String description = text(n, "description");
        String time = firstNonBlank(text(n, "time"), text(n, "traceTime"));
        if (description == null && time == null) return null;
        NormalizedTraceNode node = NormalizedTraceNode.of(
                NormalizedTraceNode.parseTime(time),
                text(n, "location"),
                description == null ? "" : description);
        String status = text(n, "status");
        if (status != null) node.setStatus(status);
        JsonNode abnormal = n.get("abnormal");
        if (abnormal != null && abnormal.isBoolean()) node.setIsAbnormal(abnormal.asBoolean());
        JsonNode lng = n.get("longitude");
        JsonNode lat = n.get("latitude");
        if (lng != null && lng.isNumber()) node.setLongitude(lng.decimalValue());
        if (lat != null && lat.isNumber()) node.setLatitude(lat.decimalValue());
        String city = text(n, "city");
        if (city != null) node.setCity(city);
        String hubType = text(n, "hubType");
        if (hubType != null) node.setHubType(hubType);
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}

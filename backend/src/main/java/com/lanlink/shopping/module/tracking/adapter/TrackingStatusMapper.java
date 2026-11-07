package com.lanlink.shopping.module.tracking.adapter;

import com.lanlink.shopping.service.LogisticsStateMachine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道状态 → 平台状态七态收敛映射（out/logistics-tracking-plan.md P2/P3 任务 3，纯函数）。
 *
 * 七态收敛表（渠道侧统一词汇表）→ 平台 9 态（LogisticsStateMachine）：
 *   PENDING、INFO_RECEIVED → picked_up（待揽收/已揽收；对 in_transit 的回退推进由
 *     canTransit 拒绝、同状态幂等放行，规划 D3「不调用 canTransit 回退」由此保证）
 *   IN_TRANSIT → in_transit；OUT_FOR_DELIVERY → out_for_delivery；DELIVERED → delivered；
 *   EXCEPTION → exception；RETURNED → returned。
 *
 * 关键词命中优先级（ask P2/P3 任务 4）：失败/异常关键词先于常规关键词判定——
 * 「派送失败」必须命中 EXCEPTION，而不是被「派送」规则吞成 out_for_delivery
 * （LogisticsTraceParser 的既有词表没有「派送失败」，该误判风险在本映射层修正）。
 * 未知结构化状态/未命中关键词保守归 in_transit 并标记 ambiguous（规划 P3 任务 1 口径）。
 *
 * 本类是 ingest/补拉链路的唯一状态入口：产出随 NormalizedTraceNode 显式下传，
 * 由 LogisticsWaybillService.syncTrace 优先采纳（显式合法状态 > 描述关键词推导），
 * 人工同步等不经过本类的路径保持既有 parser 语义不变。
 */
public final class TrackingStatusMapper {

    /** 七态收敛枚举 */
    public enum ChannelStatusCategory {
        PENDING, INFO_RECEIVED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNED
    }

    /**
     * 映射结果。platformState 为平台 9 态；abnormal 供告警链路（raiseAlertIfNeeded）输入；
     * ambiguous=true 表示未知描述保守归 in_transit，看板人工纠偏入口可见。
     */
    public record Mapped(ChannelStatusCategory category, String platformState,
                         boolean abnormal, String alertType, boolean ambiguous) {}

    private TrackingStatusMapper() {
    }

    /**
     * 渠道结构化状态字典（小写精确匹配）：mock 推送 status 字段 / 快递100 / 17TRACK /
     * AfterShip 常见值 + 平台态直传。未收录回退描述关键词，再未知保守 in_transit。
     */
    private static final Map<String, ChannelStatusCategory> STRUCTURED = Map.ofEntries(
            // 待揽收/已下单
            Map.entry("pending", ChannelStatusCategory.PENDING),
            Map.entry("pending_pickup", ChannelStatusCategory.PENDING),
            Map.entry("waiting", ChannelStatusCategory.PENDING),
            Map.entry("已下单", ChannelStatusCategory.PENDING),
            Map.entry("待揽收", ChannelStatusCategory.PENDING),
            // 已揽收/信息已收录
            Map.entry("info_received", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("collected", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("picked_up", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("pickedup", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("picked up", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("已揽收", ChannelStatusCategory.INFO_RECEIVED),
            Map.entry("揽收", ChannelStatusCategory.INFO_RECEIVED),
            // 运输中
            Map.entry("in_transit", ChannelStatusCategory.IN_TRANSIT),
            Map.entry("transit", ChannelStatusCategory.IN_TRANSIT),
            Map.entry("transporting", ChannelStatusCategory.IN_TRANSIT),
            Map.entry("运输中", ChannelStatusCategory.IN_TRANSIT),
            // 派送中
            Map.entry("out_for_delivery", ChannelStatusCategory.OUT_FOR_DELIVERY),
            Map.entry("outfordelivery", ChannelStatusCategory.OUT_FOR_DELIVERY),
            Map.entry("delivering", ChannelStatusCategory.OUT_FOR_DELIVERY),
            Map.entry("派送中", ChannelStatusCategory.OUT_FOR_DELIVERY),
            // 已签收
            Map.entry("delivered", ChannelStatusCategory.DELIVERED),
            Map.entry("signed", ChannelStatusCategory.DELIVERED),
            Map.entry("已签收", ChannelStatusCategory.DELIVERED),
            Map.entry("签收", ChannelStatusCategory.DELIVERED),
            // 异常
            Map.entry("exception", ChannelStatusCategory.EXCEPTION),
            Map.entry("failed", ChannelStatusCategory.EXCEPTION),
            Map.entry("error", ChannelStatusCategory.EXCEPTION),
            Map.entry("异常", ChannelStatusCategory.EXCEPTION),
            // 退回
            Map.entry("returned", ChannelStatusCategory.RETURNED),
            Map.entry("returning", ChannelStatusCategory.RETURNED),
            Map.entry("退回", ChannelStatusCategory.RETURNED),
            Map.entry("退件", ChannelStatusCategory.RETURNED),
            // 平台精细态直传
            Map.entry("lost", ChannelStatusCategory.EXCEPTION),
            Map.entry("丢失", ChannelStatusCategory.EXCEPTION));

    /**
     * 渠道状态映射：结构化状态优先（七态收敛表），未收录回退描述关键词（异常关键词优先），
     * 仍未命中 → in_transit + ambiguous。
     *
     * @param structuredStatus 渠道结构化状态（NormalizedTraceNode.status，可为 null）
     * @param description      轨迹描述原文（关键词兜底）
     */
    public static Mapped map(String structuredStatus, String description) {
        // 结构化状态并入丢失检测：渠道直发 "lost"/"丢失" 时收敛为 EXCEPTION 态但平台态保留
        // 更精细的 lost（canTransit 合法、告警类型 lost，与 LogisticsTraceParser 既有语义对齐）
        String d = (description == null ? "" : description.toLowerCase()) + " "
                + (structuredStatus == null ? "" : structuredStatus.trim().toLowerCase());
        if (containsAny(d, "丢失", "丢件", "lost")) {
            return new Mapped(ChannelStatusCategory.EXCEPTION, LogisticsStateMachine.LOST, true, "lost", false);
        }
        ChannelStatusCategory structured = structuredStatus == null || structuredStatus.isBlank()
                ? null : STRUCTURED.get(structuredStatus.trim().toLowerCase());
        if (structured != null) {
            return fromCategory(structured, null, d);
        }
        // —— 异常/失败关键词组（先于常规派送/签收词，ask 任务 4）——
        if (containsAny(d, "退回", "退件", "退货", "returned", "returning")) {
            return fromCategory(ChannelStatusCategory.RETURNED, "return", d);
        }
        if (containsAny(d, "派送失败", "投递失败", "配送失败", "派送异常", "签收失败",
                "delivery failed", "failed to deliver")) {
            return fromCategory(ChannelStatusCategory.EXCEPTION, "delivery_failed", d);
        }
        if (containsAny(d, "拒收", "拒签", "refused", "rejected")) {
            return fromCategory(ChannelStatusCategory.EXCEPTION, "reject", d);
        }
        if (containsAny(d, "破损", "损坏", "damage", "damaged")) {
            return fromCategory(ChannelStatusCategory.EXCEPTION, "damage", d);
        }
        if (containsAny(d, "滞留", "超时", "异常停留", "延误", "异常", "delay", "stuck", "exception", "failed", "error")) {
            return fromCategory(ChannelStatusCategory.EXCEPTION, "delay", d);
        }
        // —— 常规关键词组 ——
        if (containsAny(d, "签收", "已签", "delivered", "signed")) {
            return fromCategory(ChannelStatusCategory.DELIVERED, null, d);
        }
        if (containsAny(d, "派件", "派送", "out for delivery", "out_for_delivery", "delivering")) {
            return fromCategory(ChannelStatusCategory.OUT_FOR_DELIVERY, null, d);
        }
        if (containsAny(d, "揽收", "收件", "揽件", "已揽", "picked up", "picked_up", "collected")) {
            return fromCategory(ChannelStatusCategory.INFO_RECEIVED, null, d);
        }
        if (containsAny(d, "运输", "中转", "干线", "到达", "发出", "航班", "transit", "arrived", "departed")) {
            return fromCategory(ChannelStatusCategory.IN_TRANSIT, null, d);
        }
        // 未知状态保守归 in_transit，ambiguous 供看板人工纠偏
        return new Mapped(ChannelStatusCategory.IN_TRANSIT, LogisticsStateMachine.IN_TRANSIT, false, null, true);
    }

    /** 仅按描述关键词映射（无结构化状态） */
    public static Mapped map(String description) {
        return map(null, description);
    }

    private static Mapped fromCategory(ChannelStatusCategory category, String alertTypeOverride, String description) {
        String platformState = switch (category) {
            case PENDING, INFO_RECEIVED -> LogisticsStateMachine.PICKED_UP;
            case IN_TRANSIT -> LogisticsStateMachine.IN_TRANSIT;
            case OUT_FOR_DELIVERY -> LogisticsStateMachine.OUT_FOR_DELIVERY;
            case DELIVERED -> LogisticsStateMachine.DELIVERED;
            case EXCEPTION -> LogisticsStateMachine.EXCEPTION;
            case RETURNED -> LogisticsStateMachine.RETURNED;
        };
        boolean abnormal = category == ChannelStatusCategory.EXCEPTION
                || category == ChannelStatusCategory.RETURNED;
        String alertType = null;
        if (abnormal) {
            alertType = alertTypeOverride != null ? alertTypeOverride : "delay";
        }
        return new Mapped(category, platformState, abnormal, alertType, false);
    }

    /**
     * 规范化节点 → syncTrace 入参（D2 单一写路径）。
     * 节点未携带显式状态时在映射层补齐七态收敛结果，随节点显式下传，
     * syncTrace 侧按「显式合法状态优先」采纳。
     */
    public static List<Map<String, Object>> toSyncNodeMaps(List<NormalizedTraceNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (nodes == null) return out;
        for (NormalizedTraceNode node : nodes) {
            if (node == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceTime", node.getTraceTime() == null ? LocalDateTime.now() : node.getTraceTime());
            m.put("description", node.getDescription() == null ? "" : node.getDescription());
            m.put("location", node.getLocation() == null ? "" : node.getLocation());
            Mapped mapped = map(node.getStatus(), node.getDescription());
            m.put("status", mapped.platformState());
            out.add(m);
        }
        return out;
    }

    /** 节点集合里最新的渠道侧节点时间（tracking_event.event_time；入库延迟指标 = 入库时间 - 该时间） */
    public static LocalDateTime maxNodeTime(List<NormalizedTraceNode> nodes) {
        if (nodes == null) return null;
        LocalDateTime max = null;
        for (NormalizedTraceNode node : nodes) {
            if (node != null && node.getTraceTime() != null
                    && (max == null || node.getTraceTime().isAfter(max))) {
                max = node.getTraceTime();
            }
        }
        return max;
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }
}

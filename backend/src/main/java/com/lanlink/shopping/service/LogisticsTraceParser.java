package com.lanlink.shopping.service;

import org.springframework.stereotype.Component;

/**
 * 物流轨迹解析器（纯逻辑）：把服务商轨迹描述原文映射为平台状态 + 异常标记。
 *
 * 状态映射（描述关键词 → 运单状态）：
 *   揽收/收件 → picked_up；运输/中转/干线 → in_transit；派件/派送 → out_for_delivery；
 *   签收 → delivered；退回/拒收/破损/丢失/滞留 → exception 系（异常）。
 */
@Component
public class LogisticsTraceParser {

    /** 轨迹节点解析结果 */
    public record ParsedNode(String status, boolean abnormal, String alertType) {}

    public ParsedNode parse(String description) {
        String d = description == null ? "" : description.toLowerCase();
        // 异常优先判定（退回/拒收/破损/丢失/滞留）
        if (containsAny(d, "丢失", "lost", "丢件")) return new ParsedNode(LogisticsStateMachine.LOST, true, "lost");
        if (containsAny(d, "退回", "returned", "退件")) return new ParsedNode(LogisticsStateMachine.RETURNED, true, "return");
        if (containsAny(d, "拒收", "拒签", "refused")) return new ParsedNode(LogisticsStateMachine.EXCEPTION, true, "reject");
        if (containsAny(d, "破损", "damage", "损坏")) return new ParsedNode(LogisticsStateMachine.EXCEPTION, true, "damage");
        if (containsAny(d, "滞留", "超时", "异常停留", "delay")) return new ParsedNode(LogisticsStateMachine.EXCEPTION, true, "delay");
        // 正常节点
        if (containsAny(d, "签收", "已签", "delivered", "本人签收")) return new ParsedNode(LogisticsStateMachine.DELIVERED, false, null);
        if (containsAny(d, "派件", "派送", "派送中", "out for delivery")) return new ParsedNode(LogisticsStateMachine.OUT_FOR_DELIVERY, false, null);
        if (containsAny(d, "揽收", "收件", "已揽", "picked up", "揽件")) return new ParsedNode(LogisticsStateMachine.PICKED_UP, false, null);
        if (containsAny(d, "运输", "中转", "干线", "到达", "发出", "航班", "transit")) return new ParsedNode(LogisticsStateMachine.IN_TRANSIT, false, null);
        // 默认按运输中处理（未知描述不报错，保守不阻断同步）
        return new ParsedNode(LogisticsStateMachine.IN_TRANSIT, false, null);
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}

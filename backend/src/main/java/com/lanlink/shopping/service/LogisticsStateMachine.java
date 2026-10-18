package com.lanlink.shopping.service;

import java.util.Map;
import java.util.Set;

/**
 * 运单状态机（纯逻辑，无状态）。
 *
 * 合法状态流转（docs/物流板块业务设计文档.md 1.2）：
 *   pending_ship → picked_up → in_transit → out_for_delivery → delivered
 *   in_transit/out_for_delivery → exception；exception → in_transit | returned | lost
 * 禁止跳级/回退（如 delivered 不可回到 in_transit），非法流转由调用方抛 BusinessException。
 */
public final class LogisticsStateMachine {

    public static final String PENDING_SHIP = "pending_ship";
    public static final String PICKED_UP = "picked_up";
    public static final String IN_TRANSIT = "in_transit";
    public static final String OUT_FOR_DELIVERY = "out_for_delivery";
    public static final String DELIVERED = "delivered";
    public static final String EXCEPTION = "exception";
    public static final String RETURNED = "returned";
    public static final String LOST = "lost";
    public static final String CANCELLED = "cancelled";

    /** 终态集合：不可再流转 */
    public static final Set<String> TERMINAL = Set.of(DELIVERED, RETURNED, LOST, CANCELLED);

    private static final Map<String, Set<String>> EDGES = Map.of(
            PENDING_SHIP, Set.of(PICKED_UP, CANCELLED, EXCEPTION),
            PICKED_UP, Set.of(IN_TRANSIT, EXCEPTION, RETURNED, LOST),
            IN_TRANSIT, Set.of(IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION, RETURNED, LOST),
            OUT_FOR_DELIVERY, Set.of(DELIVERED, EXCEPTION, RETURNED, LOST),
            EXCEPTION, Set.of(IN_TRANSIT, OUT_FOR_DELIVERY, RETURNED, LOST, DELIVERED),
            DELIVERED, Set.of(),
            RETURNED, Set.of(),
            LOST, Set.of(),
            CANCELLED, Set.of()
    );

    private LogisticsStateMachine() {
    }

    /** 当前状态是否合法 */
    public static boolean isValid(String status) {
        return status != null && EDGES.containsKey(status);
    }

    /** 是否允许 from → to 流转（同状态视为幂等放行，用于轨迹重复同步） */
    public static boolean canTransit(String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return true; // 幂等：同状态重复扫描不报错
        Set<String> next = EDGES.get(from);
        return next != null && next.contains(to);
    }

    /** 是否终态 */
    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}

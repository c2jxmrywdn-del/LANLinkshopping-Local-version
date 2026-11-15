package com.lanlink.shopping.module.techservice.support;

import com.lanlink.shopping.common.BusinessException;

import java.util.Map;
import java.util.Set;

/**
 * 技术服务各聚合的状态迁移守卫。
 *
 * <p>历史缺陷：需求/评价/托管/中试预约的 {@code updateStatus} 只校验"目标状态在枚举内"，
 * 不校验迁移方向——运营误点或事件重放即可把已关闭/已完成的业务拉回进行中
 * （例如 CLOSED→MATCHING、COMPLETED→APPLIED、REJECTED→ACCEPTED），且无审计。
 *
 * <p>这里用显式白名单表达合法迁移：{@code from -> {allowed to}}。
 * 终态（CLOSED/COMPLETED/CANCELLED 等）默认不允许回退；确需回退时在白名单里显式列出。
 */
public final class TechStatusGuard {

    private TechStatusGuard() {
    }

    /**
     * 校验迁移是否合法。
     *
     * @param transitions 合法迁移表；{@code from} 不在表内表示该状态无出边（终态）
     * @param from        当前状态（null 视为"新建"，允许进入任意非终态入口）
     * @param to          目标状态
     * @param label       业务名，用于错误提示
     */
    public static void require(Map<String, Set<String>> transitions, String from, String to, String label) {
        if (to == null || to.isBlank()) {
            throw new BusinessException(400, label + "状态不能为空");
        }
        if (from == null || from.isBlank() || from.equals(to)) {
            return;
        }
        Set<String> allowed = transitions.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BusinessException(400,
                    label + "状态不能从 " + from + " 变更为 " + to);
        }
    }
}

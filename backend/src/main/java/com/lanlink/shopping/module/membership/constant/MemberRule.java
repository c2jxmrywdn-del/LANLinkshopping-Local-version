package com.lanlink.shopping.module.membership.constant;

/**
 * 会员积分规则（参考主流电商开源项目 macrozheng/mall 积分消费设置 的简化实现）。
 *
 * 规则总览（对用户展示于会员中心）：
 *  - 消费获取：每消费 1 元获得 1 积分（VIP 双倍）
 *  - 积分抵现：每 1000 积分抵 1 元
 *  - 抵现最小单位：1 元；使用积分必须为 1000 的整数倍，不足 1000 积分的零头不参与抵扣
 *  - 抵现上限：单笔订单最高抵扣应付金额的 10%（上限金额向下取整到整元）
 *  - 取消返还：订单取消后使用的积分全额返还
 */
public final class MemberRule {

    private MemberRule() {}

    /** 消费获取比例：每 X 元 = 1 积分（当前 1 元 = 1 积分） */
    public static final int POINTS_PER_YUAN_EARN = 1;

    /** 抵现比例：X 积分 = 1 元 */
    public static final int REDEEM_POINTS_PER_YUAN = 1000;

    /** 单笔抵现上限：最高抵扣订单应付金额的百分比 */
    public static final int REDEEM_MAX_PERCENT = 10;

    /** VIP 消费积分倍数 */
    public static final int VIP_EARN_MULTIPLIER = 2;

    /** 积分流水类型：下单抵现 */
    public static final String CHANGE_REDEEM = "redeem_order";
    /** 积分流水类型：取消返还 */
    public static final String CHANGE_REFUND = "refund_order";
    /** 积分流水类型：全额退款扣回支付所得 */
    public static final String CHANGE_EARN_REVERSAL = "earn_reversal";

    /**
     * 按积分数计算可抵扣金额（元，整元向下取整）。
     * 抵扣最小单位为 1 元：不足 REDEEM_POINTS_PER_YUAN 的零头积分不参与抵扣。
     * 例：1500 积分 → ¥1（消耗 1000 积分）；999 积分 → ¥0。
     */
    public static java.math.BigDecimal pointsToAmount(int points) {
        if (points < REDEEM_POINTS_PER_YUAN) return java.math.BigDecimal.ZERO;
        return java.math.BigDecimal.valueOf(points / REDEEM_POINTS_PER_YUAN);
    }

    /**
     * 归整实际参与抵扣的积分数：向下取整到 1000 的整数倍（不足 1 元的零头不抵扣）。
     * 例：1500 → 1000；999 → 0；3000 → 3000。
     */
    public static int usablePoints(int points) {
        if (points <= 0) return 0;
        return points / REDEEM_POINTS_PER_YUAN * REDEEM_POINTS_PER_YUAN;
    }

    /** 按整元金额换算所需积分数（1 元 = 1000 积分） */
    public static int amountToRedeemPoints(java.math.BigDecimal amountYuan) {
        if (amountYuan == null || amountYuan.signum() <= 0) return 0;
        return amountYuan.intValue() * REDEEM_POINTS_PER_YUAN;
    }

    /** 按金额计算可获得积分数（1元=1分，向下取整） */
    public static int amountToPoints(java.math.BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return 0;
        return amount.intValue(); // 1 积分/元，不足 1 元部分不计
    }
}

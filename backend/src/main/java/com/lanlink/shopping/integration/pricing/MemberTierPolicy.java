package com.lanlink.shopping.integration.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 「会员折扣」与「阶梯价」的互斥取优决策（纯函数，便于单测把两个分支钉死）。
 *
 * <p>背景：会员折扣是按金额比例的，阶梯价是商家按数量让出的批发价。两者叠乘会让同一单
 * 既吃商家让利又吃平台会员让利，毛利被双重击穿、对账也说不清是谁让的价。政策口径是
 * **谁让得多用谁**：阶梯省得 ≥ 会员省得 → 只吃阶梯并关闭会员折扣；反之回到原价成交、
 * 只吃会员折扣。促销（满减/折扣/秒杀）与优惠券不在本决策范围内，仍按既有顺序在成交额之上计算。
 */
public final class MemberTierPolicy {

    /**
     * @param useTier     是否按阶梯价成交
     * @param applyMember 是否允许 PricingFacade 再叠加会员折扣（与 useTier 互斥）
     * @param goodsTotal  进入促销/券计算前的成交货值
     */
    public record Decision(boolean useTier, boolean applyMember, BigDecimal goodsTotal) {
    }

    private MemberTierPolicy() {
    }

    public static Decision decide(BigDecimal listTotal, BigDecimal tierTotal, BigDecimal memberRate) {
        BigDecimal list = listTotal == null ? BigDecimal.ZERO : listTotal;
        BigDecimal tier = tierTotal == null ? list : tierTotal;
        BigDecimal rate = memberRate == null ? BigDecimal.ONE : memberRate;

        BigDecimal tierSaving = list.subtract(tier);
        boolean memberRateValid = rate.signum() > 0 && rate.compareTo(BigDecimal.ONE) < 0;
        BigDecimal memberSaving = memberRateValid
                ? list.subtract(list.multiply(rate).setScale(2, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;

        boolean useTier = tierSaving.signum() > 0 && tierSaving.compareTo(memberSaving) >= 0;
        boolean applyMember = memberRateValid && !useTier;
        BigDecimal goodsTotal = useTier ? tier : list;
        return new Decision(useTier, applyMember, goodsTotal);
    }
}

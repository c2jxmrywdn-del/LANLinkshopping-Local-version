package com.lanlink.shopping.integration.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会员折扣 vs 阶梯价 互斥取优。
 *
 * <p>用例取自真实商品：基准 3,340，阶梯「满 5 件 3,006」。白银 99 折、铂金 95 折两种会员档都覆盖。
 */
class MemberTierPolicyTest {

    private static final BigDecimal LIST_5 = new BigDecimal("16700.00");
    private static final BigDecimal TIER_5 = new BigDecimal("15030.00");

    @Test
    void tierWinsWhenItSavesMoreThanMembership() {
        // 阶梯省 1,670；白银 99 折只省 167 → 只吃阶梯，且不允许再叠会员折扣
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, TIER_5, new BigDecimal("0.99"));
        assertTrue(d.useTier());
        assertFalse(d.applyMember());
        assertEquals(0, d.goodsTotal().compareTo(TIER_5));
    }

    @Test
    void membershipWinsWhenItSavesMoreThanTier() {
        // 阶梯几乎没让利（16,695，只省 5）；白银 99 折省 167 → 回到原价、关闭阶梯
        BigDecimal tinyTier = new BigDecimal("16695.00");
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, tinyTier, new BigDecimal("0.99"));
        assertFalse(d.useTier());
        assertTrue(d.applyMember());
        assertEquals(0, d.goodsTotal().compareTo(LIST_5));
    }

    @Test
    void equalSavingsPreferTier() {
        // 两边都省 167：优先阶梯（商家让利优先，平台不再补贴同一笔）
        BigDecimal equalTier = new BigDecimal("16533.00");
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, equalTier, new BigDecimal("0.99"));
        assertTrue(d.useTier());
        assertFalse(d.applyMember());
    }

    @Test
    void noTierFallsBackToMembershipOnly() {
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, LIST_5, new BigDecimal("0.95"));
        assertFalse(d.useTier());
        assertTrue(d.applyMember());
        assertEquals(0, d.goodsTotal().compareTo(LIST_5));
    }

    @Test
    void noMemberRateKeepsTierOnly() {
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, TIER_5, BigDecimal.ONE);
        assertTrue(d.useTier());
        assertFalse(d.applyMember());
    }

    @Test
    void noDiscountAtAllChargesListPrice() {
        MemberTierPolicy.Decision d = MemberTierPolicy.decide(LIST_5, LIST_5, BigDecimal.ONE);
        assertFalse(d.useTier());
        assertFalse(d.applyMember());
        assertEquals(0, d.goodsTotal().compareTo(LIST_5));
    }
}

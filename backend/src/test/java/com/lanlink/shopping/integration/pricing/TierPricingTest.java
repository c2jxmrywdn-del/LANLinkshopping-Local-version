package com.lanlink.shopping.integration.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶梯价解析与取价规则。
 *
 * <p>重点钉住三件事：① 种子数据用的是 qty 键、AI 侧历史上只认 minQty，两套写法都要认；
 * ② 取「满足条件的最大档位」，不是"所有命中档里挑最低"；③ 脏数据一律回落基准价且不抛异常。
 */
class TierPricingTest {

    private static final BigDecimal BASE = new BigDecimal("100.00");

    @Test
    void parsesSeedQtyKeyAndAiMinQtyKey() {
        assertEquals(List.of(new TierPricing.Tier(100, new BigDecimal("90.00"))),
                TierPricing.parse("[{\"qty\":100,\"price\":90}]"));
        assertEquals(List.of(new TierPricing.Tier(100, new BigDecimal("90.00"))),
                TierPricing.parse("[{\"minQty\":100,\"price\":90}]"));
        assertEquals(List.of(new TierPricing.Tier(50, new BigDecimal("95.00"))),
                TierPricing.parse("[{\"min_qty\":50,\"price\":95}]"));
    }

    @Test
    void picksTheLargestQualifiedTier() {
        String json = "[{\"qty\":100,\"price\":90},{\"qty\":500,\"price\":80},{\"qty\":1000,\"price\":70}]";

        assertEquals(new TierPricing.Resolved(500, new BigDecimal("80.00")),
                TierPricing.resolve(BASE, json, 600));
        assertEquals(new TierPricing.Resolved(100, new BigDecimal("90.00")),
                TierPricing.resolve(BASE, json, 100));
        assertEquals(new TierPricing.Resolved(1000, new BigDecimal("70.00")),
                TierPricing.resolve(BASE, json, 5000));
    }

    @Test
    void fallsBackToBasePriceBelowTheFirstTier() {
        String json = "[{\"qty\":100,\"price\":90}]";
        assertEquals(new TierPricing.Resolved(0, BASE), TierPricing.resolve(BASE, json, 99));
        assertEquals(new TierPricing.Resolved(0, BASE), TierPricing.resolve(BASE, null, 100));
        assertEquals(new TierPricing.Resolved(0, BASE), TierPricing.resolve(BASE, "", 100));
    }

    @Test
    void dirtyJsonNeverBreaksCheckout() {
        assertEquals(List.of(), TierPricing.parse("not-json"));
        assertEquals(List.of(), TierPricing.parse("{\"qty\":100}"));
        assertEquals(List.of(), TierPricing.parse("[{\"qty\":0,\"price\":90},{\"qty\":100,\"price\":0}]"));
        assertEquals(new TierPricing.Resolved(0, BASE), TierPricing.resolve(BASE, "[oops", 500));
    }

    @Test
    void duplicateTierKeepsTheLowerPrice() {
        String json = "[{\"qty\":100,\"price\":90},{\"qty\":100,\"price\":85}]";
        assertEquals(new TierPricing.Resolved(100, new BigDecimal("85.00")),
                TierPricing.resolve(BASE, json, 200));
    }

    @Test
    void tierPricerHigherThanBaseIsIgnored() {
        // 运营把档位填反（批得越多越贵）：不能真按更贵的价卖，回落基准价
        String json = "[{\"qty\":10,\"price\":120}]";
        assertEquals(new TierPricing.Resolved(0, BASE), TierPricing.resolve(BASE, json, 20));
        assertTrue(TierPricing.parse(json).size() == 1);
    }

    @Test
    void nextTierSupportsBuyMoreHint() {
        String json = "[{\"qty\":100,\"price\":90},{\"qty\":500,\"price\":80}]";
        assertEquals(new TierPricing.Tier(100, new BigDecimal("90.00")), TierPricing.next(json, 50));
        assertEquals(new TierPricing.Tier(500, new BigDecimal("80.00")), TierPricing.next(json, 100));
        assertNull(TierPricing.next(json, 500));
    }
}

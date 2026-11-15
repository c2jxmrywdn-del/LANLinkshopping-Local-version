package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶梯批发价数据契约（P1-17）：写侧校验 + 归一化 + MOQ + 对外出站形态。
 */
class TierPricingContractTest {

    private static Product.TierPrice tier(int minQty, String price) {
        return new Product.TierPrice(minQty, new BigDecimal(price));
    }

    // ===== encodeContract：合法输入归一化 =====

    @Test
    void encodesNormalizedJsonSortedAscendingWithTwoDecimals() {
        // 乱序输入归一为 minQty 升序；价格补两位小数
        String json = TierPricing.encodeContract(
                List.of(tier(500, "80"), tier(10, "88")), new BigDecimal("100.00"));
        assertEquals("[{\"minQty\":10,\"price\":88.00},{\"minQty\":500,\"price\":80.00}]", json);
    }

    @Test
    void emptyOrNullTiersEncodeToNull() {
        assertNull(TierPricing.encodeContract(null, new BigDecimal("100.00")));
        assertNull(TierPricing.encodeContract(List.of(), new BigDecimal("100.00")));
    }

    @Test
    void equalPricesAcrossTiersAreAllowed() {
        String json = TierPricing.encodeContract(
                List.of(tier(10, "88.00"), tier(100, "88.00")), new BigDecimal("100.00"));
        assertTrue(json.contains("\"price\":88.00"));
    }

    // ===== encodeContract：非法输入拒绝并给可读文案 =====

    @Test
    void rejectsZeroOrNegativeQty() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(List.of(tier(0, "88.00")), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("正整数"));
    }

    @Test
    void rejectsZeroOrNegativePrice() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(List.of(tier(10, "0")), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("大于 0"));
    }

    @Test
    void rejectsDuplicateMinQty() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(
                        List.of(tier(100, "88.00"), tier(100, "85.00")), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("重复"));
    }

    @Test
    void rejectsPriceIncreaseAlongQty() {
        // 批得越多越贵：写侧直接拒绝（读侧容错回落是存量数据兜底，写侧不让新脏数据进门）
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(
                        List.of(tier(10, "88.00"), tier(100, "90.00")), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("递减或持平"));
    }

    @Test
    void rejectsTierAboveBasePrice() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(List.of(tier(10, "120.00")), new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("不能高于商品单价"));
    }

    @Test
    void rejectsOversizedJsonBeyondColumnLimit() {
        // t_product.tier_price_json VARCHAR(500)：超限在写侧拦截而不是截断成脏 JSON
        List<Product.TierPrice> many = new java.util.ArrayList<>();
        for (int q = 10; q <= 400; q += 10) many.add(tier(q, "88.00"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> TierPricing.encodeContract(many, new BigDecimal("100.00")));
        assertTrue(e.getMessage().contains("500"));
    }

    // ===== minOrderQty（MOQ）：最小档即起订量 =====

    @Test
    void moqIsSmallestTierQty() {
        String json = "[{\"minQty\":100,\"price\":88.00},{\"minQty\":500,\"price\":80.00}]";
        assertEquals(100, TierPricing.minOrderQty(json));
    }

    @Test
    void moqIsZeroWithoutTiers() {
        assertEquals(0, TierPricing.minOrderQty(null));
        assertEquals(0, TierPricing.minOrderQty(""));
        assertEquals(0, TierPricing.minOrderQty("not-json"));
    }

    // ===== toContract：出站形态与升序 =====

    @Test
    void contractOutIsSortedListOfTierPrice() {
        String json = "[{\"minQty\":500,\"price\":80.00},{\"minQty\":10,\"price\":88.00}]";
        List<Product.TierPrice> out = TierPricing.toContract(json);
        assertEquals(2, out.size());
        assertEquals(10, out.get(0).minQty());
        assertEquals(0, out.get(0).price().compareTo(new BigDecimal("88.00")));
        assertEquals(500, out.get(1).minQty());
        assertEquals(0, out.get(1).price().compareTo(new BigDecimal("80.00")));
    }

    @Test
    void contractOutEmptyForDirtyJson() {
        assertTrue(TierPricing.toContract("oops").isEmpty());
    }

    // ===== 阶梯命中边界（与既有 TierPricingTest 互补：正好 minQty / 区间内 / 超最大档） =====

    @Test
    void resolveHitsBoundaryAndRangeAndBeyondMaxTier() {
        String json = "[{\"minQty\":10,\"price\":88.00},{\"minQty\":100,\"price\":80.00}]";
        // 正好 minQty：命中该档
        assertEquals(new TierPricing.Resolved(10, new BigDecimal("88.00")),
                TierPricing.resolve(new BigDecimal("100.00"), json, 10));
        // 区间内（下一档之前）：维持当前档
        assertEquals(new TierPricing.Resolved(10, new BigDecimal("88.00")),
                TierPricing.resolve(new BigDecimal("100.00"), json, 99));
        // 超最大档：取最低价档
        assertEquals(new TierPricing.Resolved(100, new BigDecimal("80.00")),
                TierPricing.resolve(new BigDecimal("100.00"), json, 100000));
        // 差一件未达首档：基准价
        assertEquals(new TierPricing.Resolved(0, new BigDecimal("100.00")),
                TierPricing.resolve(new BigDecimal("100.00"), json, 9));
    }
}

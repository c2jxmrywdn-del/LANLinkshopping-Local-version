package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LogisticsTemplate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运费计算引擎单元测试：覆盖正常计费/体积重/满额包邮/固定运费/按件/区域加价/偏远附加/边界值。
 */
class LogisticsFreightCalculatorTest {

    private final LogisticsFreightCalculator calculator = new LogisticsFreightCalculator();

    private LogisticsTemplate weightTemplate() {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setPricingType("weight");
        t.setFirstWeight(new BigDecimal("1"));
        t.setFirstPrice(new BigDecimal("12"));
        t.setContinuedWeight(new BigDecimal("1"));
        t.setContinuedPrice(new BigDecimal("5"));
        t.setVolumeFactor(new BigDecimal("6000"));
        t.setFreeThreshold(BigDecimal.ZERO);
        t.setRemoteSurcharge(BigDecimal.ZERO);
        return t;
    }

    @Test
    void basicWeightWithinFirstWeight() {
        // 0.5kg ≤ 首重 1kg → 只收首重 12 元
        var r = calculator.calculate(weightTemplate(), new BigDecimal("0.5"), null, "广东", false, null, 1);
        assertEquals(new BigDecimal("12.00"), r.freight());
    }

    @Test
    void weightOverFirstWeightAddsContinued() {
        // 3.2kg：超首重 2.2kg → ceil(2.2/1)=3 个续重 → 12 + 3*5 = 27
        var r = calculator.calculate(weightTemplate(), new BigDecimal("3.2"), null, "广东", false, null, 1);
        assertEquals(new BigDecimal("27.00"), r.freight());
    }

    @Test
    void exactBoundaryWeight() {
        // 1kg 整：不超首重 → 12
        var r = calculator.calculate(weightTemplate(), new BigDecimal("1"), null, "广东", false, null, 1);
        assertEquals(new BigDecimal("12.00"), r.freight());
    }

    @Test
    void volumeWeightOverridesLightActualWeight() {
        // 0.1kg 实际轻但体积 0.06m³=60000cm³/6000=10kg 体积重 → 按 10kg 计
        // 10kg：12 + ceil(9/1)*5 = 57
        var r = calculator.calculate(weightTemplate(), new BigDecimal("0.1"), new BigDecimal("0.06"), "广东", false, null, 1);
        assertEquals(new BigDecimal("57.00"), r.freight());
    }

    @Test
    void freeShippingThresholdMet() {
        LogisticsTemplate t = weightTemplate();
        t.setFreeThreshold(new BigDecimal("199"));
        // 订单 200 ≥ 199 → 包邮 0 元
        var r = calculator.calculate(t, new BigDecimal("5"), null, "广东", false, new BigDecimal("200"), 1);
        assertEquals(new BigDecimal("0.00"), r.freight());
    }

    @Test
    void freeShippingThresholdNotMet() {
        LogisticsTemplate t = weightTemplate();
        t.setFreeThreshold(new BigDecimal("199"));
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "广东", false, new BigDecimal("100"), 1);
        assertEquals(new BigDecimal("12.00"), r.freight());
    }

    @Test
    void fixedPricingType() {
        LogisticsTemplate t = weightTemplate();
        t.setPricingType("fixed");
        t.setFirstPrice(new BigDecimal("15"));
        var r = calculator.calculate(t, null, null, "广东", false, null, 1);
        assertEquals(new BigDecimal("15.00"), r.freight());
    }

    @Test
    void piecePricingType() {
        LogisticsTemplate t = weightTemplate();
        t.setPricingType("piece");
        t.setFirstPrice(new BigDecimal("10"));
        t.setContinuedPrice(new BigDecimal("4"));
        // 5 件：10 + 4*4 = 26
        var r = calculator.calculate(t, null, null, "广东", false, null, 5);
        assertEquals(new BigDecimal("26.00"), r.freight());
    }

    @Test
    void remoteSurchargeApplied() {
        LogisticsTemplate t = weightTemplate();
        t.setRemoteSurcharge(new BigDecimal("15"));
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆", true, null, 1);
        assertEquals(new BigDecimal("27.00"), r.freight()); // 12 + 15
    }

    @Test
    void regionSurchargeApplied() {
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\",\"西藏\"],\"surcharge\":10.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆", false, null, 1);
        assertEquals(new BigDecimal("22.00"), r.freight()); // 12 + 10
    }

    @Test
    void invalidRegionRuleJsonDoesNotBreakCalculation() {
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("not-a-json");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "广东", false, null, 1);
        assertEquals(new BigDecimal("12.00"), r.freight());
    }

    @Test
    void detailJsonContainsAuditFields() {
        var r = calculator.calculate(weightTemplate(), new BigDecimal("2"), null, "广东", false, null, 1);
        assertTrue(r.detailJson().contains("\"pricingType\":\"weight\""));
        assertTrue(r.detailJson().contains("\"firstPrice\":12"));
        assertTrue(r.detailJson().contains("\"province\":\"广东\""));
    }

    @Test
    void nullTemplateThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.calculate(null, null, null, null, false, null, 1));
    }

    @Test
    void negativeFreightClampedToZero() {
        LogisticsTemplate t = weightTemplate();
        t.setFirstPrice(new BigDecimal("-5"));
        t.setContinuedPrice(BigDecimal.ZERO);
        var r = calculator.calculate(t, null, null, "广东", false, null, 1);
        assertEquals(new BigDecimal("0.00"), r.freight());
    }

    // ===== Issue1 回归：省份含数字时区域加价不误匹配 =====

    @Test
    void regionRuleWithNumericRegionNameMatchesCorrectly() {
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"内蒙古1区\"],\"surcharge\":8.0}]");
        // 省份名"内蒙古1区"含数字，Jackson 正规解析应精确匹配 surcharge=8
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "内蒙古1区", false, null, 1);
        assertEquals(new BigDecimal("20.00"), r.freight()); // 12 + 8
    }

    @Test
    void regionSurchargeExactMatchNotOverlappingOtherSegments() {
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":10.0},{\"regions\":[\"西藏\"],\"surcharge\":20.0}]");
        var xinjiang = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆", false, null, 1);
        assertEquals(new BigDecimal("22.00"), xinjiang.freight()); // 12 + 10
        var xizang = calculator.calculate(t, new BigDecimal("0.5"), null, "西藏", false, null, 1);
        assertEquals(new BigDecimal("32.00"), xizang.freight()); // 12 + 20
    }

    @Test
    void regionSurchargeNumericSurchargeValue() {
        // surcharge 为整数（非小数）时 Jackson 解析 Number 正常
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":15}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆", false, null, 1);
        assertEquals(new BigDecimal("27.00"), r.freight()); // 12 + 15
    }

    // ===== Issue2 回归：完整地址 → 省份提取 → 区域加价生效 =====

    @Test
    void extractProvinceFromFullAddress() {
        assertEquals("广东省", LogisticsFreightCalculator.extractProvince("广东省广州市天河区体育西路101号"));
        assertEquals("新疆维吾尔自治区", LogisticsFreightCalculator.extractProvince("新疆维吾尔自治区乌鲁木齐市天山区xx路"));
        assertEquals("北京", LogisticsFreightCalculator.extractProvince("北京朝阳区xx街道"));
        assertEquals("内蒙古", LogisticsFreightCalculator.extractProvince("内蒙古呼和浩特市xx区"));
    }

    @Test
    void extractProvincePrefersFullSuffixOverShort() {
        // "黑龙江省" 不应误判为"黑龙"
        assertEquals("黑龙江省", LogisticsFreightCalculator.extractProvince("黑龙江省哈尔滨市南岗区"));
    }

    @Test
    void extractProvinceFallbackForUnknownAddress() {
        // 未识别地址兜底取前 2 字
        assertEquals("某某", LogisticsFreightCalculator.extractProvince("某某未知区域地址"));
        assertEquals("", LogisticsFreightCalculator.extractProvince(null));
    }

    @Test
    void regionSurchargeWorksWithFullAddressViaExtractProvince() {
        // 端到端：完整地址 → extractProvince → regionSurcharge 匹配
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":10.0}]");
        String province = LogisticsFreightCalculator.extractProvince("新疆维吾尔自治区乌鲁木齐市xx路");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, province, false, null, 1);
        assertEquals(new BigDecimal("22.00"), r.freight()); // 12 + 10（区域加价生效）
    }

    @Test
    void regionSurchargeLongNamePrefixMatch() {
        // regions 写简称"内蒙古"，province 为全称"内蒙古自治区"时应前缀匹配
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"内蒙古\"],\"surcharge\":7.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "内蒙古自治区", false, null, 1);
        assertEquals(new BigDecimal("19.00"), r.freight()); // 12 + 7
    }

    // ===== Issue1 回归（多规则累加）：多规则同时命中同一省份时 surcharge 应累加 =====

    @Test
    void multipleMatchingRulesAccumulate() {
        // 规则A「新疆」+10 与规则B「新疆维吾尔自治区」+5 同时命中 → 合计 15
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":10.0},{\"regions\":[\"新疆维吾尔自治区\"],\"surcharge\":5.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆维吾尔自治区", false, null, 1);
        assertEquals(new BigDecimal("27.00"), r.freight()); // 12 + 10 + 5
    }

    @Test
    void sameRuleMultipleRegionsCountsOnce() {
        // 同一规则内 regions 含两个都命中的项（"新疆"与"新疆维吾尔自治区"）→ surcharge 只计一次
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\",\"新疆维吾尔自治区\"],\"surcharge\":9.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆维吾尔自治区", false, null, 1);
        assertEquals(new BigDecimal("21.00"), r.freight()); // 12 + 9（不重复计）
    }

    @Test
    void mixedRulesOnlyMatchedAccumulate() {
        // 3 条规则：2 条命中（+8、+4）+ 1 条不命中（+100）→ 合计 12，不含未命中规则
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"内蒙古\"],\"surcharge\":8.0},"
                + "{\"regions\":[\"西藏\"],\"surcharge\":100.0},"
                + "{\"regions\":[\"内蒙古自治区\"],\"surcharge\":4.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "内蒙古自治区", false, null, 1);
        assertEquals(new BigDecimal("24.00"), r.freight()); // 12 + 8 + 4
    }

    @Test
    void threeMatchingRulesAllAccumulate() {
        // 3 条规则同时命中 → 全部累加 3+4+5=12
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"新疆\"],\"surcharge\":3.0},"
                + "{\"regions\":[\"新疆维吾尔\"],\"surcharge\":4.0},"
                + "{\"regions\":[\"新疆维吾尔自治区\"],\"surcharge\":5.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "新疆维吾尔自治区", false, null, 1);
        assertEquals(new BigDecimal("24.00"), r.freight()); // 12 + 3 + 4 + 5
    }

    @Test
    void extractProvinceSupportsTaiwan() {
        assertEquals("台湾省", LogisticsFreightCalculator.extractProvince("台湾省台北市信义区市府路1号"));
        assertEquals("台湾", LogisticsFreightCalculator.extractProvince("台湾台北市大安区忠孝东路"));
    }

    @Test
    void regionSurchargeBidirectionalShortAddressMatchesFullRule() {
        // 规则模板写全称"广东省"，收件地址提取为简称"广东"
        LogisticsTemplate t = weightTemplate();
        t.setRegionRuleJson("[{\"regions\":[\"广东省\"],\"surcharge\":6.0}]");
        var r = calculator.calculate(t, new BigDecimal("0.5"), null, "广东", false, null, 1);
        assertEquals(new BigDecimal("18.00"), r.freight()); // 12 + 6
    }
}

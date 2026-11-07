package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LogisticsTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工业大宗阶梯运费引擎与增值服务池专项单元测试 (Phase 2)。
 */
class LogisticsFreightCalculatorTieredTest {

    private final LogisticsFreightCalculator calculator = new LogisticsFreightCalculator();

    private LogisticsTemplate tieredTemplate() {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setPricingType("weight");
        t.setTierMode("tiered");
        t.setTierRuleJson("""
            [
              {"minWeight":0, "maxWeight":300, "unitPrice":1.5, "basePrice":30.0},
              {"minWeight":300, "maxWeight":1000, "unitPrice":1.1, "basePrice":0.0},
              {"minWeight":1000, "maxWeight":999999, "unitPrice":0.8, "basePrice":0.0}
            ]
            """);
        t.setVasRuleJson("""
            {
              "forklift": {"basePrice":80.0, "freeTons":1.0, "perTonPrice":50.0},
              "upstairs": {"basePrice":50.0, "perFloorPrice":20.0},
              "woodenCrate": {"pricePerM3":120.0},
              "oversizeOverweight": {"surchargeRate":0.20}
            }
            """);
        t.setMinCharge(new BigDecimal("100.00"));
        t.setVolumeFactor(new BigDecimal("3000")); // 零担重抛比 1:3000
        return t;
    }

    @Test
    @DisplayName("阶梯一：200kg 落在 0~300kg 阶梯 (basePrice 30 + 200*1.5 = 330 元)")
    void testTierOneCalculation() {
        LogisticsTemplate t = tieredTemplate();
        var result = calculator.calculate(t, new BigDecimal("200"), null, "江苏", false, BigDecimal.ZERO, 1);
        assertEquals(new BigDecimal("330.00"), result.freight());
        assertTrue(result.detailJson().contains("\"tierMode\":\"tiered\""));
    }

    @Test
    @DisplayName("阶梯二：500kg 落在 300~1000kg 阶梯 (500*1.1 = 550 元)")
    void testTierTwoCalculation() {
        LogisticsTemplate t = tieredTemplate();
        var result = calculator.calculate(t, new BigDecimal("500"), null, "上海", false, BigDecimal.ZERO, 1);
        assertEquals(new BigDecimal("550.00"), result.freight());
    }

    @Test
    @DisplayName("阶梯三：1500kg 落在 1000kg+ 阶梯 (1500*0.8 = 1200 元)")
    void testTierThreeCalculation() {
        LogisticsTemplate t = tieredTemplate();
        var result = calculator.calculate(t, new BigDecimal("1500"), null, "浙江", false, BigDecimal.ZERO, 1);
        assertEquals(new BigDecimal("1200.00"), result.freight());
    }

    @Test
    @DisplayName("增值服务：叉车装卸(2.5吨超1吨免额收 80+1.5*50=155) + 木架定制(2m³收 240)")
    void testVasForkliftAndWoodenCrate() {
        LogisticsTemplate t = tieredTemplate();
        // 2500kg 运费: 2500*0.8 = 2000 元
        // 叉车: 80 + (2.5-1.0)*50 = 155 元
        // 木架: 2.0 * 120 = 240 元
        // 合计: 2000 + 155 + 240 = 2395.00 元
        var vas = new LogisticsFreightCalculator.VasRequest(true, false, 1, true, false);
        var result = calculator.calculate(t, new BigDecimal("2500"), new BigDecimal("2.0"), "江苏", false, BigDecimal.ZERO, 1, vas);
        assertEquals(new BigDecimal("2395.00"), result.freight());
        assertTrue(result.detailJson().contains("\"forklift\":155.00"));
        assertTrue(result.detailJson().contains("\"woodenCrate\":240.00"));
    }

    @Test
    @DisplayName("增值服务：送货上楼(3楼收 50+2*20=90) + 超重超长(基准运费 20% 加收)")
    void testVasUpstairsAndOversize() {
        LogisticsTemplate t = tieredTemplate();
        // 500kg 运费: 500*1.1 = 550 元
        // 上楼3楼: 50 + 2*20 = 90 元
        // 超长超重: 550 * 0.20 = 110 元
        // 合计: 550 + 90 + 110 = 750.00 元
        var vas = new LogisticsFreightCalculator.VasRequest(false, true, 3, false, true);
        var result = calculator.calculate(t, new BigDecimal("500"), null, "江苏", false, BigDecimal.ZERO, 1, vas);
        assertEquals(new BigDecimal("750.00"), result.freight());
        assertTrue(result.detailJson().contains("\"upstairs\":90.00"));
        assertTrue(result.detailJson().contains("\"oversize\":110.00"));
    }

    @Test
    @DisplayName("最低起运价保底：小件算费 60 元但低于 minCharge 100 元，自动拉升至 100.00 元")
    void testMinChargeProtection() {
        LogisticsTemplate t = tieredTemplate();
        // 20kg 运费: 30 + 20*1.5 = 60 元 < 100 元保底
        var result = calculator.calculate(t, new BigDecimal("20"), null, "江苏", false, BigDecimal.ZERO, 1);
        assertEquals(new BigDecimal("100.00"), result.freight());
        assertTrue(result.detailJson().contains("\"minCharge\":100.00"));
    }

    @Test
    @DisplayName("大宗纯吨位计费模式 (ton)")
    void testTonPricingMode() {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setPricingType("weight");
        t.setTierMode("ton");
        t.setFirstPrice(new BigDecimal("450.00")); // 450元/吨
        // 3500kg = 3.5吨 * 450 = 1575.00 元
        var result = calculator.calculate(t, new BigDecimal("3500"), null, "江苏", false, BigDecimal.ZERO, 1);
        assertEquals(new BigDecimal("1575.00"), result.freight());
    }
}

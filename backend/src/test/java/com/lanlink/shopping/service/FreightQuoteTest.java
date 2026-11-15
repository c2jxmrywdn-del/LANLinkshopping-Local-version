package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.mapper.LogisticsTemplateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运费报价 MVP（P1-5 批次 1）：fixed / piece / region 三模式 + 满额包邮 + 最低起运价 + 边界。
 *
 * <p>钉住的事：① 三模式在“无商品重量”前提下也能算出不同地区/不同件数的运费；
 * ② 包邮门槛基数必须是 goods_amount（不含运费）；③ 模板缺失/停用/脏 JSON 一律 fail-open；
 * ④ 金额统一 2 位小数、超大额不溢出。
 */
class FreightQuoteTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal q(FreightQuoteService svc, LogisticsTemplate t, String goods,
                               String region, int pieces) {
        return svc.quote(t, goods == null ? null : bd(goods), region, pieces);
    }

    private static LogisticsTemplate base(String pricingType) {
        LogisticsTemplate t = new LogisticsTemplate();
        t.setPricingType(pricingType);
        t.setStatus(1);
        t.setFirstPrice(bd("12.00"));
        t.setContinuedPrice(bd("5.00"));
        t.setFreeThreshold(BigDecimal.ZERO);
        t.setMinCharge(BigDecimal.ZERO);
        return t;
    }

    private static final String REGION_JSON = "["
            + "{\"regions\":[\"内蒙古\",\"新疆\"],\"surcharge\":15},"
            + "{\"regions\":[\"西藏\"],\"surcharge\":25}]";

    private final FreightQuoteService svc = new FreightQuoteService(null, 1L, true);

    // ==================== 三模式 ====================

    @Test
    void fixedModeChargesFlatPricePerOrder() {
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("15.00"));
        assertEquals(0, q(svc, t, "100.00", "广东", 3).compareTo(bd("15.00")));
        assertEquals(0, q(svc, t, "100.00", "广东", 1).compareTo(bd("15.00")));
    }

    @Test
    void pieceModeChargesFirstPlusContinuedPerPiece() {
        LogisticsTemplate t = base("piece");
        t.setFirstPrice(bd("10.00"));
        t.setContinuedPrice(bd("2.50"));
        // 首件 10 + 2×2.5 = 15
        assertEquals(0, q(svc, t, "100.00", "广东", 3).compareTo(bd("15.00")));
        assertEquals(0, q(svc, t, "100.00", "广东", 1).compareTo(bd("10.00")));
        // 件数 ≤0 / 非法按 1 件
        assertEquals(0, q(svc, t, "100.00", "广东", 0).compareTo(bd("10.00")));
        assertEquals(0, q(svc, t, "100.00", "广东", -5).compareTo(bd("10.00")));
    }

    @Test
    void regionModeAddsConfiguredSurchargeWithoutWeight() {
        // weight/volume 无重量数据 → region 模式：基础价 + 地区加价
        LogisticsTemplate t = base("weight");
        t.setRegionRuleJson(REGION_JSON);
        assertEquals(0, q(svc, t, "100.00", "内蒙古", 1).compareTo(bd("27.00")));
        assertEquals(0, q(svc, t, "100.00", "新疆维吾尔自治区", 1).compareTo(bd("27.00")));
        assertEquals(0, q(svc, t, "100.00", "广东省", 1).compareTo(bd("12.00")));
        assertEquals(0, q(svc, t, "100.00", null, 1).compareTo(bd("12.00")));
        assertEquals(0, q(svc, t, "100.00", "  ", 1).compareTo(bd("12.00")));
    }

    @Test
    void regionSurchargeAppliesToFixedAndPieceToo() {
        LogisticsTemplate fixed = base("fixed");
        fixed.setFirstPrice(bd("8.00"));
        fixed.setRegionRuleJson(REGION_JSON);
        assertEquals(0, q(svc, fixed, "100.00", "内蒙古", 1).compareTo(bd("23.00")));

        LogisticsTemplate piece = base("piece");
        piece.setFirstPrice(bd("10.00"));
        piece.setContinuedPrice(bd("2.00"));
        piece.setRegionRuleJson(REGION_JSON);
        // 3 件：10 + 2×2 = 14，内蒙古 +15 = 29
        assertEquals(0, q(svc, piece, "100.00", "内蒙古", 3).compareTo(bd("29.00")));
    }

    // ==================== 包邮 / 最低起运价 ====================

    @Test
    void freeThresholdUsesGoodsAmountOnly() {
        LogisticsTemplate t = base("weight");
        t.setFreeThreshold(bd("199.00"));
        assertEquals(0, q(svc, t, "199.00", "广东", 1).compareTo(bd("0.00")));   // 刚达门槛 → 包邮
        assertEquals(0, q(svc, t, "198.99", "广东", 1).compareTo(bd("12.00"))); // 差 1 分 → 收运费
        assertEquals(0, q(svc, t, null, "广东", 1).compareTo(bd("12.00")));     // 货款 null 不得误判包邮
        assertEquals(0, q(svc, t, "5000.00", "内蒙古", 1).compareTo(bd("0.00"))); // 包邮短路优先于地区加价
    }

    @Test
    void minChargeFloorApplies() {
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("5.00"));
        t.setMinCharge(bd("20.00"));
        assertEquals(0, q(svc, t, "100.00", "广东", 1).compareTo(bd("20.00")));

        LogisticsTemplate above = base("fixed");
        above.setFirstPrice(bd("30.00"));
        above.setMinCharge(bd("20.00"));
        assertEquals(0, q(svc, above, "100.00", "广东", 1).compareTo(bd("30.00")));
    }

    // ==================== 失败/边界 ====================

    @Test
    void nullOrDisabledTemplateCostsZero() {
        assertEquals(0, q(svc, null, "100.00", "广东", 1).compareTo(bd("0.00")));

        LogisticsTemplate disabled = base("fixed");
        disabled.setStatus(0);
        assertEquals(0, q(svc, disabled, "100.00", "广东", 1).compareTo(bd("0.00")));
    }

    @Test
    void dirtyRegionJsonFallsBackToBasePrice() {
        LogisticsTemplate t = base("weight");
        for (String dirty : new String[]{"not-json", "{}", "[]", "[{\"regions\":\"内蒙古\"}]",
                "[{\"surcharge\":\"abc\"}]"}) {
            t.setRegionRuleJson(dirty);
            // 脏 JSON 一律不加价、不抛异常（"[]" 无规则→12；"abc" 非法数字→12）
            assertEquals(0, q(svc, t, "100.00", "内蒙古", 1).compareTo(bd("12.00")), "dirty=" + dirty);
        }
    }

    @Test
    void numericSurchargeStringIsAccepted() {
        LogisticsTemplate t = base("weight");
        t.setRegionRuleJson("[{\"regions\":[\"青海\"],\"surcharge\":\"8.50\"}]");
        assertEquals(0, q(svc, t, "100.00", "青海省", 1).compareTo(bd("20.50")));
    }

    @Test
    void hugeAmountsDoNotOverflow() {
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("9999999.99"));
        assertEquals(0, q(svc, t, "9999999999.99", "广东", 1).compareTo(bd("9999999.99")));

        LogisticsTemplate piece = base("piece");
        piece.setFirstPrice(bd("9999999.99"));
        piece.setContinuedPrice(bd("0.01"));
        assertEquals(0, q(svc, piece, "0.00", "广东", 200000).compareTo(bd("10001999.98")));
    }

    @Test
    void negativeConfiguredPriceFloorsToZero() {
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("-5.00"));
        assertEquals(0, q(svc, t, "100.00", "广东", 1).compareTo(bd("0.00")));
    }

    // ==================== 模板解析（merId 入参 / fail-open） ====================

    @Test
    void quoteByMerchantLoadsDefaultTemplate() {
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("18.00"));
        when(mapper.selectById(7L)).thenReturn(t);

        FreightQuoteService service = new FreightQuoteService(mapper, 7L, true);
        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("18.00")));
    }

    @Test
    void quoteByMerchantFailsOpenWhenMapperMissingOrBroken() {
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        when(mapper.selectById(1L)).thenThrow(new RuntimeException("db down"));
        FreightQuoteService service = new FreightQuoteService(mapper, 1L, true);
        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("0.00")));

        FreightQuoteService noMapper = new FreightQuoteService(null, 1L, true);
        assertEquals(0, noMapper.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("0.00")));
    }

    // ==================== 总开关 app.freight.enabled（默认 false，上线防资损） ====================

    @Test
    void disabledSwitchReturnsZeroAndNeverQueriesTemplate() {
        // 默认关闭：无论传什么参数都返回 0，且绝不查询模板 Mapper（不查模板/不打包邮/不加价）
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        FreightQuoteService service = new FreightQuoteService(mapper, 1L, false);

        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 3).compareTo(bd("0.00")));
        assertEquals(0, service.quote(42L, bd("5000.00"), "内蒙古", 99).compareTo(bd("0.00")));
        assertEquals(0, service.quote((Long) null, null, null, 0).compareTo(bd("0.00")));

        // 关键证据：关闭态一次模板查询都没有（即使库里有 id=1 的历史模板也不会被读到）
        verify(mapper, never()).selectById(any());
    }

    @Test
    void enabledButDefaultTemplateMissingReturnsZeroFailOpenNoThrow() {
        // 开关已开但默认模板不存在：fail-open 返回 0，只 WARN，不抛异常、不阻断下单
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        when(mapper.selectById(1L)).thenReturn(null);
        FreightQuoteService service = new FreightQuoteService(mapper, 1L, true);

        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("0.00")));
        verify(mapper).selectById(1L);
    }

    @Test
    void enabledButDefaultTemplateDisabledReturnsZero() {
        // 开关已开但默认模板已停用（status!=1）：fail-open 返回 0（与 LogisticsWaybillService 校验语义一致）
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        LogisticsTemplate disabled = base("fixed");
        disabled.setStatus(0);
        when(mapper.selectById(1L)).thenReturn(disabled);
        FreightQuoteService service = new FreightQuoteService(mapper, 1L, true);

        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("0.00")));
    }

    @Test
    void enabledWithValidTemplateChargesNormally() {
        // 开关已开 + 默认模板存在且启用：正常按模板计费（fixed 首价 20）
        LogisticsTemplateMapper mapper = mock(LogisticsTemplateMapper.class);
        LogisticsTemplate t = base("fixed");
        t.setFirstPrice(bd("20.00"));
        when(mapper.selectById(3L)).thenReturn(t);
        FreightQuoteService service = new FreightQuoteService(mapper, 3L, true);

        assertEquals(0, service.quote(42L, bd("100.00"), "广东", 1).compareTo(bd("20.00")));
        assertEquals(0, service.quote(42L, bd("100.00"), "内蒙古", 5).compareTo(bd("20.00")));
    }
}

package com.lanlink.shopping.module.promotion.service;

import com.lanlink.shopping.module.promotion.entity.Promotion;
import com.lanlink.shopping.module.promotion.mapper.PromotionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 促销系统单测：满减 / 折扣 / 门槛 / 行业限定 / 多规则取优
 */
class PromotionServiceTest {

    private PromotionMapper mapper;
    private PromotionService service;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        mapper = mock(PromotionMapper.class);
        service = new PromotionService(mapper);
    }

    private Promotion promo(String type, BigDecimal threshold, BigDecimal benefit, BigDecimal rate,
                            String scope, Long indId) {
        Promotion p = new Promotion();
        p.setTitle(type + "-test");
        p.setType(type);
        p.setThreshold(threshold);
        p.setBenefitAmount(benefit);
        p.setDiscountRate(rate);
        p.setScope(scope);
        p.setIndId(indId);
        p.setStartTime(now.minusDays(1));
        p.setEndTime(now.plusDays(1));
        p.setStatus(1);
        return p;
    }

    @Test
    void fullReduceAboveThreshold() {
        when(mapper.selectList(any())).thenReturn(List.of(
                promo("full_reduce", new BigDecimal("500"), new BigDecimal("50"), null, "all", null)));
        BigDecimal d = service.calculateDiscount(new BigDecimal("800"), 1L);
        assertEquals(new BigDecimal("50"), d, "满500减50，订单800应减50");
    }

    @Test
    void fullReduceBelowThresholdNoBenefit() {
        when(mapper.selectList(any())).thenReturn(List.of(
                promo("full_reduce", new BigDecimal("500"), new BigDecimal("50"), null, "all", null)));
        assertEquals(BigDecimal.ZERO, service.calculateDiscount(new BigDecimal("400"), 1L));
    }

    @Test
    void discountRateApplied() {
        when(mapper.selectList(any())).thenReturn(List.of(
                promo("discount", new BigDecimal("100"), null, new BigDecimal("0.95"), "all", null)));
        // 1000 * 5% = 50
        assertEquals(new BigDecimal("50.00"), service.calculateDiscount(new BigDecimal("1000"), 1L));
    }

    @Test
    void industryScopeFiltered() {
        when(mapper.selectList(any())).thenReturn(List.of(
                promo("discount", new BigDecimal("0"), null, new BigDecimal("0.95"), "ind", 1L)));
        // 行业1(建材)生效，行业2不生效
        assertEquals(new BigDecimal("50.00"), service.calculateDiscount(new BigDecimal("1000"), 1L));
        assertEquals(BigDecimal.ZERO, service.calculateDiscount(new BigDecimal("1000"), 2L));
    }

    @Test
    void multipleRulesPickBest() {
        when(mapper.selectList(any())).thenReturn(List.of(
                promo("full_reduce", new BigDecimal("500"), new BigDecimal("50"), null, "all", null),
                promo("discount", new BigDecimal("100"), null, new BigDecimal("0.90"), "all", null)));
        // 订单 1000：满减省50 vs 9折省100 → 取 100
        assertEquals(new BigDecimal("100.00"), service.calculateDiscount(new BigDecimal("1000"), 1L));
    }

    @Test
    void inactivePromotionIgnored() {
        Promotion p = promo("full_reduce", new BigDecimal("0"), new BigDecimal("10"), null, "all", null);
        p.setStatus(0);
        when(mapper.selectList(any())).thenReturn(List.of(p));
        assertEquals(BigDecimal.ZERO, service.calculateDiscount(new BigDecimal("100"), 1L));
    }
}

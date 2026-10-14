package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.promotion.service.PromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 集成测试：PricingFacade 促销 × 会员折扣 互斥取优（冲突防止核心规则）
 */
class PricingFacadeTest {

    private PromotionService promotionService;
    private MembershipService membershipService;
    private PricingFacade facade;

    @BeforeEach
    void setUp() {
        promotionService = mock(PromotionService.class);
        membershipService = mock(MembershipService.class);
        facade = new PricingFacade(promotionService, membershipService);
    }

    @Test
    void promotionWinsWhenBigger() {
        when(promotionService.calculateDiscount(any(), any())).thenReturn(new BigDecimal("100"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));

        PricingFacade.Quote q = facade.quote(2L, new BigDecimal("1000"), 1L, true);
        // 促销省100 > 会员折扣50 → 取促销，会员优惠归零（互斥不叠加）
        assertEquals(0, new BigDecimal("100").compareTo(q.getPromoDiscount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getMemberDiscount()));
        assertEquals(PricingFacade.SOURCE_PROMOTION, q.getSource());
        assertEquals(0, new BigDecimal("900.00").compareTo(q.finalAmount()));
    }

    @Test
    void memberWinsWhenBigger() {
        when(promotionService.calculateDiscount(any(), any())).thenReturn(new BigDecimal("10"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));

        PricingFacade.Quote q = facade.quote(2L, new BigDecimal("1000"), 1L, true);
        // 会员折扣省50 > 促销省10 → 取会员，促销归零
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getPromoDiscount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(q.getMemberDiscount()));
        assertEquals(PricingFacade.SOURCE_MEMBER, q.getSource());
        assertEquals(0, new BigDecimal("950.00").compareTo(q.finalAmount()));
    }

    @Test
    void noBenefitWhenNotEligible() {
        when(promotionService.calculateDiscount(any(), any())).thenReturn(BigDecimal.ZERO);
        // 商户/管理员不享有会员折扣
        PricingFacade.Quote q = facade.quote(3L, new BigDecimal("1000"), 1L, false);
        assertEquals(0, new BigDecimal("1000.00").compareTo(q.finalAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getMemberDiscount()));
    }

    @Test
    void finalAmountNeverNegative() {
        when(promotionService.calculateDiscount(any(), any())).thenReturn(new BigDecimal("2000"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));
        // 原价1000 促销2000 → 保底 0
        assertEquals(0, BigDecimal.ZERO.compareTo(facade.quote(2L, new BigDecimal("1000"), 1L, true).finalAmount()));
    }
}

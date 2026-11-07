package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.campaign.service.CampaignService.Line;
import com.lanlink.shopping.module.campaign.service.CampaignService.PromoResult;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 行感知计价集成测试：PricingFacade 行感知重载（商户促销参与计价）+ Quote.appliedTitle 传播。
 * 互斥不变式不回归：促销（平台+商户取优）vs 会员折扣 仍二选一。
 */
class PricingFacadeLineTest {

    private CampaignService campaignService;
    private MembershipService membershipService;
    private PricingFacade facade;

    @BeforeEach
    void setUp() {
        campaignService = mock(CampaignService.class);
        membershipService = mock(MembershipService.class);
        facade = new PricingFacade(campaignService, membershipService, null, null);
    }

    @Test
    void lineAwareQuoteCarriesAppliedTitleWhenPromotionWins() {
        List<Line> lines = List.of(new Line(1L, 10L, new BigDecimal("600")));
        when(campaignService.calculateDiscountDetailed(any(), any(), any()))
                .thenReturn(new PromoResult(new BigDecimal("80"), "店铺满减"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));

        PricingFacade.Quote q = facade.quote(2L, new BigDecimal("1000"), 1L, true, lines);

        // 促销省80 > 会员折扣50 → 促销生效，appliedTitle 随促销来源
        assertEquals(0, new BigDecimal("80").compareTo(q.getPromoDiscount()));
        assertEquals(PricingFacade.SOURCE_PROMOTION, q.getSource());
        assertEquals("店铺满减", q.getAppliedTitle());
        assertEquals(0, new BigDecimal("920.00").compareTo(q.finalAmount()));
    }

    @Test
    void appliedTitleIsNullWhenMemberDiscountWins() {
        List<Line> lines = List.of(new Line(1L, 10L, new BigDecimal("600")));
        when(campaignService.calculateDiscountDetailed(any(), any(), any()))
                .thenReturn(new PromoResult(new BigDecimal("10"), "小满减"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));

        PricingFacade.Quote q = facade.quote(2L, new BigDecimal("1000"), 1L, true, lines);

        assertEquals(PricingFacade.SOURCE_MEMBER, q.getSource());
        assertNull(q.getAppliedTitle());
        assertEquals(0, new BigDecimal("950.00").compareTo(q.finalAmount()));
    }

    @Test
    void legacyQuoteWithoutLinesStillUsesOriginalDiscountMethod() {
        when(campaignService.calculateDiscount(any(), any())).thenReturn(new BigDecimal("100"));
        when(membershipService.levelDiscountRate(any())).thenReturn(new BigDecimal("0.95"));

        PricingFacade.Quote q = facade.quote(2L, new BigDecimal("1000"), 1L, true);

        assertEquals(0, new BigDecimal("100").compareTo(q.getPromoDiscount()));
        assertEquals(PricingFacade.SOURCE_PROMOTION, q.getSource());
        // 无行信息不传活动标题（原口径不回归）
        assertNull(q.getAppliedTitle());
        verify(campaignService).calculateDiscount(any(), any());
        verify(campaignService, never()).calculateDiscountDetailed(any(), any(), any());
    }
}

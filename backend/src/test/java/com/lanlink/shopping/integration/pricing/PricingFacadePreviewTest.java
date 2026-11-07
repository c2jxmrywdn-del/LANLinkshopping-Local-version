package com.lanlink.shopping.integration.pricing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.campaign.service.CampaignService.Line;
import com.lanlink.shopping.module.campaign.service.CampaignService.PromoResult;
import com.lanlink.shopping.module.campaign.service.PricingPreviewService;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * G2 计价回归用例：促销预览（PricingPreviewService）与下单「同参同果」。
 *
 * 锁定的不变式：
 *  1. 预览按购物车行服务端重算出的 total/indId/行快照，与下单路径（OrderService 逐行重算）完全一致；
 *  2. 预览走 PricingFacade.quote 本体 → 促销 vs 会员互斥取优不叠加，与下单同一实现同果；
 *  3. 会员资格判定走共用工具 MemberEligibility（roleId=1 采购方），商户/管理员不参与；
 *  4. 预览只读：不核销券、不触碰活动写路径（campaignService 仅 calculateDiscountDetailed 一次调用）；
 *  5. 凑单提示 cartHint：未达门槛的平台级 full_reduce 中选差额最小者，不新增促销类型。
 */
class PricingFacadePreviewTest {

    private CampaignService campaignService;
    private MembershipService membershipService;
    private CartMapper cartMapper;
    private ProductMapper productMapper;
    private UserMapper userMapper;
    private CampaignMapper campaignMapper;
    private PricingFacade facade;
    private PricingPreviewService service;

    @BeforeEach
    void setUp() {
        // 纯单元测试不启动 MyBatis，需手动初始化实体元数据，LambdaQueryWrapper 才能解析列名
        // （与 CampaignServiceTest / MarketNewsServiceTest 同款做法）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Cart.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Campaign.class);
        campaignService = mock(CampaignService.class);
        membershipService = mock(MembershipService.class);
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        userMapper = mock(UserMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        facade = new PricingFacade(campaignService, membershipService, cartMapper, productMapper, campaignMapper);
        service = new PricingPreviewService(facade, userMapper);
    }

    private User userWithRole(long userId, long roleId) {
        User u = new User();
        u.setUserId(userId);
        u.setRoleId(roleId);
        return u;
    }

    private Product product(long prodId, long merId, long indId, String price) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        p.setIndId(indId);
        p.setPrice(new BigDecimal(price));
        return p;
    }

    private Cart cart(long cartId, long prodId, int quantity) {
        Cart c = new Cart();
        c.setCartId(cartId);
        c.setUserId(2L);
        c.setProdId(prodId);
        c.setQuantity(quantity);
        return c;
    }

    private Campaign fullReduce(long id, String title, String threshold, String benefit, Long indId, String scope) {
        Campaign c = new Campaign();
        c.setCampaignId(id).setTitle(title).setType(CampaignService.TYPE_FULL_REDUCE)
                .setStatus(1).setReviewStatus(1).setScope(scope).setIndId(indId)
                .setThreshold(new BigDecimal(threshold)).setBenefitAmount(new BigDecimal(benefit));
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewUsesCheckoutIdenticalRecalc_promotionWinsCarriesTitle() {
        // 两行购物车：prod1 单价300×2=600（商户10），prod2 单价400×1=400（商户20），行业均为1
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(101, 1, 2), cart(102, 2, 1)));
        when(productMapper.selectById(1L)).thenReturn(product(1, 10, 1, "300"));
        when(productMapper.selectById(2L)).thenReturn(product(2, 20, 1, "400"));
        when(userMapper.selectById(2L)).thenReturn(userWithRole(2, 1)); // 采购方 → 会员资格
        when(campaignService.calculateDiscountDetailed(any(), any(), any()))
                .thenReturn(new PromoResult(new BigDecimal("80"), "店铺满减"));
        when(membershipService.levelDiscountRate(2L)).thenReturn(new BigDecimal("0.95"));

        Map<String, Object> m = service.preview(2L, List.of(101L, 102L));

        // 同参：下单路径以同一批购物车行重算 total=1000、indId=1、行快照 [(1,10,600),(2,20,400)]，
        // 预览必须把完全相同的参数交给行感知计价（calculateDiscountDetailed 即下单计价入口）
        ArgumentCaptor<List<Line>> linesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(campaignService, only()).calculateDiscountDetailed(any(), any(), linesCaptor.capture());
        List<Line> lines = linesCaptor.getValue();
        assertEquals(2, lines.size());
        assertEquals(1L, lines.get(0).getProdId());
        assertEquals(10L, lines.get(0).getMerId());
        assertEquals(0, new BigDecimal("600").compareTo(lines.get(0).getLineAmount()));
        assertEquals(2L, lines.get(1).getProdId());
        assertEquals(20L, lines.get(1).getMerId());
        assertEquals(0, new BigDecimal("400").compareTo(lines.get(1).getLineAmount()));

        // 同果：促销 80 > 会员 50 → 促销生效，appliedTitle 随促销传播，预估应付 920（不含券与积分抵现）
        assertEquals(0, new BigDecimal("1000").compareTo((BigDecimal) m.get("original")));
        assertEquals(0, new BigDecimal("80").compareTo((BigDecimal) m.get("promoDiscount")));
        assertEquals("店铺满减", m.get("promoTitle"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("memberDiscount")));
        assertEquals(PricingFacade.SOURCE_PROMOTION, m.get("source"));
        assertEquals(0, new BigDecimal("920.00").compareTo((BigDecimal) m.get("estimateFinal")));
    }

    @Test
    void previewUsesQuoteDirectly_memberWinsNoStacking() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(101, 1, 2)));
        when(productMapper.selectById(1L)).thenReturn(product(1, 10, 1, "300")); // total=600
        when(userMapper.selectById(2L)).thenReturn(userWithRole(2, 1));
        when(campaignService.calculateDiscountDetailed(any(), any(), any()))
                .thenReturn(new PromoResult(new BigDecimal("10"), "小满减"));
        when(membershipService.levelDiscountRate(2L)).thenReturn(new BigDecimal("0.95")); // 会员省 30

        Map<String, Object> m = service.preview(2L, List.of(101L));

        // 会员 30 > 促销 10 → 会员生效、促销归零（互斥不叠加），promoTitle 为 null（appliedTitle 不传播）
        assertEquals(0, new BigDecimal("600").compareTo((BigDecimal) m.get("original")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("promoDiscount")));
        assertNull(m.get("promoTitle"));
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) m.get("memberDiscount")));
        assertEquals(PricingFacade.SOURCE_MEMBER, m.get("source"));
        assertEquals(0, new BigDecimal("570.00").compareTo((BigDecimal) m.get("estimateFinal")));
    }

    @Test
    void previewPassesMemberEligibleFalseForNonBuyerSameAsCheckout() {
        // 商户（roleId=2）不参与会员折扣：预览必须与下单共用 MemberEligibility 判定传 memberEligible=false
        PricingFacade mockedFacade = mock(PricingFacade.class);
        PricingPreviewService mockedService = new PricingPreviewService(mockedFacade, userMapper);
        when(mockedFacade.recalcCart(eq(3L), any()))
                .thenReturn(new PricingFacade.CartTotals(new BigDecimal("300"), 1L,
                        List.of(new Line(1L, 10L, new BigDecimal("300")))));
        when(mockedFacade.quote(eq(3L), eq(new BigDecimal("300")), eq(1L), eq(false), any()))
                .thenReturn(new PricingFacade.Quote(new BigDecimal("300"), BigDecimal.ZERO,
                        BigDecimal.ZERO, PricingFacade.SOURCE_PROMOTION, null));
        when(userMapper.selectById(3L)).thenReturn(userWithRole(3, 2));

        mockedService.preview(3L, List.of(201L));

        verify(mockedFacade).quote(eq(3L), eq(new BigDecimal("300")), eq(1L), eq(false), any());
    }

    @Test
    void memberEligibilityCriteriaIsSharedAndStable() {
        // 采购方 roleId=1 享有会员折扣；商户/管理员/未登录（null）不参与 —— 下单与预览唯一判定入口
        assertTrue(MemberEligibility.isMemberEligible(userWithRole(2, 1)));
        assertFalse(MemberEligibility.isMemberEligible(userWithRole(3, 2)));
        assertFalse(MemberEligibility.isMemberEligible(userWithRole(4, 9)));
        User noRole = new User();
        noRole.setUserId(5L);
        assertFalse(MemberEligibility.isMemberEligible(noRole));
        assertFalse(MemberEligibility.isMemberEligible(null));
    }

    @Test
    void emptyCartPreviewReturnsZeroedResultWithoutPricing() {
        PricingFacade mockedFacade = mock(PricingFacade.class);
        PricingPreviewService mockedService = new PricingPreviewService(mockedFacade, userMapper);
        when(mockedFacade.recalcCart(eq(2L), any())).thenReturn(null); // 无可结算购物车行

        Map<String, Object> m = mockedService.preview(2L, List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("original")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("promoDiscount")));
        assertNull(m.get("promoTitle"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("memberDiscount")));
        assertNull(m.get("source"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) m.get("estimateFinal")));
        // 空车不触发计价链
        verify(mockedFacade, never()).quote(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void cartHintPicksSmallestGapAmongUnreachedPlatformFullReduce() {
        // 购物车 600 元：门槛 1500 差 900，门槛 800 差 200 → 选差额最小者「小满减」
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(101, 1, 2)));
        when(productMapper.selectById(1L)).thenReturn(product(1, 10, 1, "300"));
        when(campaignMapper.selectList(any())).thenReturn(List.of(
                fullReduce(11L, "大满减", "1500", "200", null, CampaignService.SCOPE_ALL),
                fullReduce(12L, "小满减", "800", "50", null, CampaignService.SCOPE_ALL)));

        PricingFacade.CartHint hint = facade.cartHint(2L, List.of(101L));

        assertNotNull(hint);
        assertEquals("小满减", hint.getCampaignTitle());
        assertEquals(0, new BigDecimal("200").compareTo(hint.getGap()));
        assertEquals(0, new BigDecimal("800").compareTo(hint.getThreshold()));
        assertEquals(0, new BigDecimal("50").compareTo(hint.getBenefitAmount()));
    }

    @Test
    void cartHintSkipsReachedThresholdAndIndustryMismatch() {
        // 已达门槛（500 ≤ 600）的满减其优惠已由 quote 自动应用，不作凑单提示；
        // 行业限定满减（indId=9 ≠ 购物车行业 1）不适用 → 无可提示活动返回 null
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(101, 1, 2)));
        when(productMapper.selectById(1L)).thenReturn(product(1, 10, 1, "300"));
        when(campaignMapper.selectList(any())).thenReturn(List.of(
                fullReduce(13L, "已达门槛", "500", "30", null, CampaignService.SCOPE_ALL),
                fullReduce(14L, "他行业", "1200", "100", 9L, CampaignService.SCOPE_IND)));

        assertNull(facade.cartHint(2L, List.of(101L)));
    }

    @Test
    void cartHintDegradesGracefullyWithoutCampaignAccessOrCarts() {
        // 无可结算购物车行 → null
        when(cartMapper.selectList(any())).thenReturn(List.of());
        assertNull(facade.cartHint(2L, List.of(101L)));
        // 兼容构造（无活动 Mapper，既有 4 参单测口径）→ 降级返回 null 不抛错
        PricingFacade degraded = new PricingFacade(campaignService, membershipService, cartMapper, productMapper);
        assertNull(degraded.cartHint(2L, List.of(101L)));
    }
}

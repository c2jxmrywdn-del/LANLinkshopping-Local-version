package com.lanlink.shopping.module.campaign.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.MerchantPromoDTO;
import com.lanlink.shopping.entity.Enterprise;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.EnterpriseMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.campaign.service.CampaignService.Line;
import com.lanlink.shopping.module.campaign.service.CampaignService.PromoResult;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 商户自建促销单测：行感知计价（平台取优/商户行优惠/两者取 max/appliedTitle）、
 * 商户创建越权校验、上下架审核门禁、店铺券（scope=prod）行感知过滤与核销。
 * 纯 JUnit5 + Mockito（直接 new + mock mapper，同 MerchantChatServiceTest 模式）。
 */
class CampaignMerchantPromoTest {

    private CampaignMapper campaignMapper;
    private UserCouponMapper userCouponMapper;
    private CampaignParticipantMapper participantMapper;
    private CampaignMerchantMapper campaignMerchantMapper;
    private MerchantMapper merchantMapper;
    private IndustryMapper industryMapper;
    private MembershipService membershipService;
    private ProductMapper productMapper;
    private EnterpriseMapper enterpriseMapper;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        participantMapper = mock(CampaignParticipantMapper.class);
        campaignMerchantMapper = mock(CampaignMerchantMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        industryMapper = mock(IndustryMapper.class);
        membershipService = mock(MembershipService.class);
        productMapper = mock(ProductMapper.class);
        enterpriseMapper = mock(EnterpriseMapper.class);
        service = new CampaignService(campaignMapper, userCouponMapper, participantMapper,
                campaignMerchantMapper, merchantMapper, industryMapper, membershipService,
                productMapper, enterpriseMapper);
        // 纯 mock 环境需初始化实体元信息（lambda 列名解析：merchantToggle 的 LambdaUpdateWrapper.set，参照 MerchantChatServiceTest）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Campaign.class);
    }

    private Campaign campaign(String type, String scope, Long indId, Long merId, String prodIds,
                              Integer reviewStatus, BigDecimal threshold, BigDecimal benefit, BigDecimal rate) {
        return new Campaign()
                .setCampaignId(1L).setTitle("测试活动").setType(type)
                .setScope(scope).setIndId(indId).setMerId(merId).setProdIds(prodIds)
                .setReviewStatus(reviewStatus).setThreshold(threshold)
                .setBenefitAmount(benefit).setDiscountRate(rate)
                .setStatus(1).setStartTime(LocalDateTime.now().minusDays(1))
                .setEndTime(LocalDateTime.now().plusDays(1))
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
    }

    private Merchant merchant(Long merId, int reviewStatus) {
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(7L);
        m.setReviewStatus(reviewStatus);
        return m;
    }

    // ===== 行感知计价：平台促销取优 + appliedTitle =====

    @Test
    void platformPromosPickBestAndCarryAppliedTitle() {
        Campaign fullReduce = campaign("full_reduce", "all", null, null, null, 1,
                new BigDecimal("500"), new BigDecimal("50"), null).setTitle("全场满减");
        Campaign discount = campaign("discount", "all", null, null, null, 1,
                new BigDecimal("100"), null, new BigDecimal("0.90")).setTitle("平台折扣");
        when(campaignMapper.selectList(any())).thenReturn(List.of(fullReduce, discount));

        PromoResult r = service.calculateDiscountDetailed(new BigDecimal("1000"), 1L, null);

        assertEquals(0, new BigDecimal("100.00").compareTo(r.getDiscount()));
        assertEquals("平台折扣", r.getAppliedTitle());
        // 保留方法委托同口径
        assertEquals(0, r.getDiscount().compareTo(service.calculateDiscount(new BigDecimal("1000"), 1L)));
    }

    // ===== 行感知计价：商户促销按命中行金额 =====

    @Test
    void merchantPromoAppliesOnlyToMatchingMerchantProductLines() {
        // 商户10 全_reduce 门槛100 减30，覆盖商品 1,2
        Campaign merchantPromo = campaign("full_reduce", "prod", null, 10L, "1,2", 1,
                new BigDecimal("100"), new BigDecimal("30"), null).setTitle("店铺满减");
        when(campaignMapper.selectList(any())).thenReturn(List.of(merchantPromo));

        List<Line> lines = List.of(
                new Line(1L, 10L, new BigDecimal("60")),
                new Line(3L, 10L, new BigDecimal("80")),   // 商品不在 prod_ids
                new Line(5L, 99L, new BigDecimal("500")),  // 其他商户
                new Line(2L, 10L, new BigDecimal("50")));
        PromoResult r = service.calculateDiscountDetailed(new BigDecimal("690"), 1L, lines);

        // 命中行金额 60+50=110 ≥ 门槛 100 → 减 30；行金额而非订单总额参与门槛
        assertEquals(0, new BigDecimal("30").compareTo(r.getDiscount()));
        assertEquals("店铺满减", r.getAppliedTitle());
    }

    @Test
    void merchantDiscountPromoComputesRateOnLineAmountWithCap() {
        Campaign merchantPromo = campaign("discount", "prod", null, 10L, "7", 1,
                BigDecimal.ZERO, new BigDecimal("25"), new BigDecimal("0.90")).setTitle("店铺折扣");
        when(campaignMapper.selectList(any())).thenReturn(List.of(merchantPromo));

        List<Line> lines = List.of(new Line(7L, 10L, new BigDecimal("300")));
        PromoResult r = service.calculateDiscountDetailed(new BigDecimal("300"), 1L, lines);

        // 300×0.10=30 → 封顶 25
        assertEquals(0, new BigDecimal("25.00").compareTo(r.getDiscount()));
        assertEquals("店铺折扣", r.getAppliedTitle());
    }

    @Test
    void platformAndMerchantPromosTakeMaxWithoutStacking() {
        Campaign platform = campaign("flash_sale", "all", null, null, null, 1,
                BigDecimal.ZERO, null, new BigDecimal("0.90")).setTitle("平台秒杀");
        Campaign merchantPromo = campaign("full_reduce", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("30"), null).setTitle("店铺满减");
        when(campaignMapper.selectList(any())).thenReturn(List.of(platform, merchantPromo));

        List<Line> lines = List.of(new Line(1L, 10L, new BigDecimal("200")));
        // 平台 1000×0.10=100 > 商户 30 → 平台生效
        PromoResult r1 = service.calculateDiscountDetailed(new BigDecimal("1000"), 1L, lines);
        assertEquals(0, new BigDecimal("100.00").compareTo(r1.getDiscount()));
        assertEquals("平台秒杀", r1.getAppliedTitle());

        // 商户 120 > 平台 100 → 商户生效，不叠加
        Campaign biggerMerchant = campaign("full_reduce", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("120"), null).setTitle("大额店铺满减");
        when(campaignMapper.selectList(any())).thenReturn(List.of(platform, biggerMerchant));
        PromoResult r2 = service.calculateDiscountDetailed(new BigDecimal("1000"), 1L, lines);
        assertEquals(0, new BigDecimal("120").compareTo(r2.getDiscount()));
        assertEquals("大额店铺满减", r2.getAppliedTitle());
    }

    @Test
    void pendingOrRejectedMerchantPromoIsIgnoredInPricing() {
        Campaign pending = campaign("full_reduce", "prod", null, 10L, "1", 0,
                new BigDecimal("100"), new BigDecimal("30"), null);
        Campaign rejected = campaign("full_reduce", "prod", null, 10L, "1", 2,
                new BigDecimal("100"), new BigDecimal("50"), null);
        when(campaignMapper.selectList(any())).thenReturn(List.of(pending, rejected));

        List<Line> lines = List.of(new Line(1L, 10L, new BigDecimal("500")));
        PromoResult r = service.calculateDiscountDetailed(new BigDecimal("500"), 1L, lines);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getDiscount()));
        assertNull(r.getAppliedTitle());
    }

    @Test
    void merchantPromoBelowLineThresholdGivesNoBenefit() {
        Campaign merchantPromo = campaign("full_reduce", "prod", null, 10L, "1", 1,
                new BigDecimal("200"), new BigDecimal("30"), null);
        when(campaignMapper.selectList(any())).thenReturn(List.of(merchantPromo));

        List<Line> lines = List.of(new Line(1L, 10L, new BigDecimal("150")));
        PromoResult r = service.calculateDiscountDetailed(new BigDecimal("500"), 1L, lines);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.getDiscount()));
    }

    // ===== 商户创建：归属校验 + 默认状态 =====

    private MerchantPromoDTO promoDto(String type, List<Long> prodIds) {
        MerchantPromoDTO dto = new MerchantPromoDTO();
        dto.setTitle("国庆钜惠");
        dto.setType(type);
        dto.setDescription("店铺促销");
        dto.setStartTime(LocalDateTime.now().minusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(1));
        dto.setThreshold(new BigDecimal("100"));
        dto.setBenefitAmount(new BigDecimal("30"));
        dto.setProdIds(prodIds);
        return dto;
    }

    private Product product(long prodId, long merId) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setMerId(merId);
        p.setTitle("商品" + prodId);
        return p;
    }

    @Test
    void merchantCreateStoresPendingReviewPromoForOwnProducts() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product(101L, 9L), product(102L, 9L)));

        Campaign saved = service.merchantCreate(7L, promoDto("full_reduce", List.of(101L, 102L)));

        ArgumentCaptor<Campaign> captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignMapper).insert(captor.capture());
        Campaign inserted = captor.getValue();
        assertSame(saved, inserted);
        assertEquals(9L, inserted.getMerId());
        assertEquals("prod", inserted.getScope());
        assertEquals("101,102", inserted.getProdIds());
        assertEquals(0, inserted.getReviewStatus()); // 待平台审核
        assertEquals(0, inserted.getStatus());       // 默认下架
    }

    @Test
    void merchantCreateRejectsProductsOwnedByOthers() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(productMapper.selectBatchIds(any()))
                .thenReturn(List.of(product(101L, 9L), product(102L, 8L))); // 102 属于其他商户

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.merchantCreate(7L, promoDto("full_reduce", List.of(101L, 102L))));
        assertTrue(ex.getMessage().contains("只能为本商户的商品"));
        verify(campaignMapper, never()).insert(any(Campaign.class));
    }

    @Test
    void merchantCreateRejectsMissingOrEmptyProductsAndIllegalType() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product(101L, 9L)));

        // 商品查不到（数量不齐）
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.merchantCreate(7L, promoDto("discount", List.of(101L, 999L))));
        assertTrue(ex1.getMessage().contains("不存在"));
        // 空商品
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.merchantCreate(7L, promoDto("discount", List.of())));
        assertTrue(ex2.getMessage().contains("商品"));
        // 非法类型（领券等仅平台可建）
        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> service.merchantCreate(7L, promoDto("coupon", List.of(101L))));
        assertTrue(ex3.getMessage().contains("促销类型"));
        verify(campaignMapper, never()).insert(any(Campaign.class));
    }

    @Test
    void merchantCreateRejectsOversizeTitleAndDescription() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(productMapper.selectBatchIds(any())).thenReturn(List.of(product(101L, 9L)));

        // B7：DTO 入参超长拒绝（title ≤100 / description ≤500，与 t_campaign 列宽对齐）
        MerchantPromoDTO longTitle = promoDto("full_reduce", List.of(101L));
        longTitle.setTitle("促".repeat(101));
        BusinessException ex1 = assertThrows(BusinessException.class, () -> service.merchantCreate(7L, longTitle));
        assertTrue(ex1.getMessage().contains("活动名称"));

        MerchantPromoDTO longDesc = promoDto("full_reduce", List.of(101L));
        longDesc.setDescription("促".repeat(501));
        BusinessException ex2 = assertThrows(BusinessException.class, () -> service.merchantCreate(7L, longDesc));
        assertTrue(ex2.getMessage().contains("活动描述"));
        verify(campaignMapper, never()).insert(any(Campaign.class));
    }

    // ===== 商户上下架：越权 + 审核门禁 =====

    @Test
    void merchantToggleSwitchesStatusAtomicallyForOwner() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(10L, 1));
        Campaign c = campaign("full_reduce", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("30"), null).setStatus(0);
        when(campaignMapper.selectById(1L)).thenReturn(c);
        when(campaignMapper.update(any(), any())).thenReturn(1);

        int status = service.merchantToggle(7L, 1L);

        assertEquals(1, status);
        verify(campaignMapper).update(any(), any());
    }

    @Test
    void merchantToggleRejectsForeignCampaignAndPendingReview() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(10L, 1));
        // 他人活动
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 11L, "1", 1,
                BigDecimal.ZERO, new BigDecimal("30"), null));
        BusinessException ex1 = assertThrows(BusinessException.class, () -> service.merchantToggle(7L, 1L));
        assertTrue(ex1.getMessage().contains("无权"));
        // 待审不可上架
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 0,
                BigDecimal.ZERO, new BigDecimal("30"), null).setStatus(0));
        BusinessException ex2 = assertThrows(BusinessException.class, () -> service.merchantToggle(7L, 1L));
        assertTrue(ex2.getMessage().contains("平台审核"));
        // 已驳回同样不可上架
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 2,
                BigDecimal.ZERO, new BigDecimal("30"), null).setStatus(0));
        assertThrows(BusinessException.class, () -> service.merchantToggle(7L, 1L));
        verify(campaignMapper, never()).update(any(), any());
    }

    @Test
    void merchantToggleTakesApprovedCampaignOffline() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(10L, 1));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 1,
                BigDecimal.ZERO, new BigDecimal("30"), null).setStatus(1));
        when(campaignMapper.update(any(), any())).thenReturn(1);

        assertEquals(0, service.merchantToggle(7L, 1L));
    }

    // ===== 管理端审核 =====

    @Test
    void merchantReviewRequiresNoteOnRejection() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 0,
                BigDecimal.ZERO, new BigDecimal("30"), null));
        assertThrows(BusinessException.class, () -> service.merchantReview(1L, false, "   "));

        // B8：审核改为条件更新（仅 SET review_status/review_note/update_time）
        when(campaignMapper.update(any(), any())).thenReturn(1);
        Campaign approved = service.merchantReview(1L, true, null);
        assertEquals(1, approved.getReviewStatus());

        Campaign rejected = service.merchantReview(1L, false, "优惠力度过大");
        assertEquals(2, rejected.getReviewStatus());
        assertEquals("优惠力度过大", rejected.getReviewNote());
        // 不再整实体写回（updateById 移除）
        verify(campaignMapper, never()).updateById(any(Campaign.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void merchantReviewConditionallyUpdatesOnlyReviewColumns() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 0,
                BigDecimal.ZERO, new BigDecimal("30"), null));
        when(campaignMapper.update(any(), any())).thenReturn(1);

        service.merchantReview(1L, true, "通过");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Campaign>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(campaignMapper).update(isNull(), cap.capture());
        com.baomidou.mybatisplus.core.conditions.update.Update w =
                (com.baomidou.mybatisplus.core.conditions.update.Update) cap.getValue();
        String sqlSet = w.getSqlSet();
        assertTrue(sqlSet.contains("review_status"), "应仅 SET review_status: " + sqlSet);
        assertTrue(sqlSet.contains("review_note"), "应 SET review_note: " + sqlSet);
        assertTrue(sqlSet.contains("update_time"), "应 SET update_time: " + sqlSet);
        assertFalse(sqlSet.contains("claimed_count"), "审核不得写回计数列: " + sqlSet);
        assertFalse(sqlSet.startsWith("status=") || sqlSet.contains(",status="),
                "审核不得写回上下架 status（review_status 除外）: " + sqlSet);
        assertFalse(sqlSet.contains("title"), "审核不得写回内容列: " + sqlSet);
    }

    @Test
    void merchantReviewRejectsOversizeNote() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("full_reduce", "prod", null, 10L, "1", 0,
                BigDecimal.ZERO, new BigDecimal("30"), null));
        // B7：审核意见 ≤200 字符（列宽 VARCHAR(200)）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.merchantReview(1L, false, "驳".repeat(201)));
        assertTrue(ex.getMessage().contains("200"));
        verify(campaignMapper, never()).update(any(), any());
    }

    // ===== 店铺券（scope=prod）：行感知过滤与核销 =====

    private UserCoupon myCoupon() {
        UserCoupon uc = new UserCoupon();
        uc.setId(11L);
        uc.setCampaignId(1L);
        uc.setUserId(7L);
        uc.setStatus(0);
        return uc;
    }

    @Test
    void storeCouponUsableOnlyWhenOrderContainsMerchantLines() {
        when(userCouponMapper.selectList(any())).thenReturn(List.of(myCoupon()));
        Campaign storeCoupon = campaign("coupon", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("20"), null);
        when(campaignMapper.selectBatchIds(any())).thenReturn(List.of(storeCoupon));

        // 订单行包含商户10商品 → 可用（门槛仍按订单总额）
        List<java.util.Map<String, Object>> hit = service.quoteCoupons(7L, new BigDecimal("200"), 1L,
                List.of(new Line(1L, 10L, new BigDecimal("150"))));
        assertEquals(1, hit.size());
        assertEquals(Boolean.TRUE, hit.get(0).get("applicable"));
        assertEquals(0, new BigDecimal("20.00").compareTo((BigDecimal) hit.get(0).get("benefit")));

        // 订单行不含该商户商品 → 不可用（即便总额达标）
        List<java.util.Map<String, Object>> miss = service.quoteCoupons(7L, new BigDecimal("200"), 1L,
                List.of(new Line(5L, 99L, new BigDecimal("300"))));
        assertEquals(Boolean.FALSE, miss.get(0).get("applicable"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) miss.get(0).get("benefit")));
    }

    @Test
    void storeCouponRedemptionRejectsWithoutMerchantLine() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon());
        Campaign storeCoupon = campaign("coupon", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("20"), null);
        when(campaignMapper.selectById(1L)).thenReturn(storeCoupon);

        // 无本商户商品行 → 拒绝核销
        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L,
                        new BigDecimal("450"), "ORD1", List.of(new Line(5L, 99L, new BigDecimal("500")))));
        assertTrue(ex.getMessage().contains("店铺券"));

        // 命中商户行 → 正常核销
        when(userCouponMapper.useOne(11L, 7L, "ORD1")).thenReturn(1);
        BigDecimal benefit = service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L,
                new BigDecimal("450"), "ORD1", List.of(new Line(1L, 10L, new BigDecimal("500"))));
        assertEquals(0, new BigDecimal("20").compareTo(benefit));
    }

    @Test
    void storeCouponQuoteCarriesMerchantNameButPlatformCouponDoesNot() {
        // B10：试算返回补 merName——scope=prod 店铺券带商户→企业名（前端展示"限 XX 店铺可用"），
        // scope=all/ind 平台券 merName=null（口径同 promosForProduct）
        UserCoupon store = myCoupon();
        UserCoupon platform = new UserCoupon();
        platform.setId(12L);
        platform.setCampaignId(2L);
        platform.setUserId(7L);
        platform.setStatus(0);
        when(userCouponMapper.selectList(any())).thenReturn(List.of(store, platform));

        Campaign storeCoupon = campaign("coupon", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("20"), null);
        Campaign allCoupon = campaign("coupon", "all", null, null, null, 1,
                new BigDecimal("100"), new BigDecimal("30"), null).setCampaignId(2L);
        when(campaignMapper.selectBatchIds(any())).thenReturn(List.of(storeCoupon, allCoupon));
        Merchant m = merchant(10L, 1);
        m.setEntId(77L);
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(m));
        Enterprise ent = new Enterprise();
        ent.setEntId(77L);
        ent.setName("华东机械厂");
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of(ent));

        List<java.util.Map<String, Object>> rows = service.quoteCoupons(7L, new BigDecimal("200"), 1L,
                List.of(new Line(1L, 10L, new BigDecimal("150"))));

        assertEquals(2, rows.size());
        assertEquals("华东机械厂", rows.get(0).get("merName"));
        assertNull(rows.get(1).get("merName"));
    }

    @Test
    void legacyIndCouponStillWorksWithoutLines() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon());
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null, null, null, 1,
                new BigDecimal("200"), new BigDecimal("30"), null));
        when(userCouponMapper.useOne(11L, 7L, "ORD1")).thenReturn(1);

        BigDecimal benefit = service.applyCouponForOrder(7L, 11L, new BigDecimal("500"),
                1L, new BigDecimal("450"), "ORD1");
        assertEquals(0, new BigDecimal("30").compareTo(benefit));
    }

    // ===== 商品详情页促销（for-product）=====

    @Test
    void promosForProductHitsPlatformAndApprovedMerchantPromosWithMerName() {
        Product p = new Product();
        p.setProdId(101L);
        p.setMerId(10L);
        p.setIndId(4L);
        when(productMapper.selectById(101L)).thenReturn(p);

        Campaign platform = campaign("full_reduce", "all", null, null, null, 1,
                new BigDecimal("500"), new BigDecimal("50"), null).setTitle("全场满减");
        Campaign mine = campaign("discount", "prod", null, 10L, "101,102", 1,
                BigDecimal.ZERO, null, new BigDecimal("0.90")).setTitle("店铺折扣");
        Campaign otherProduct = campaign("discount", "prod", null, 10L, "999", 1,
                BigDecimal.ZERO, null, new BigDecimal("0.80")).setTitle("别家商品促销");
        when(campaignMapper.selectList(any())).thenReturn(List.of(platform, mine, otherProduct));

        Merchant m = merchant(10L, 1);
        m.setEntId(77L);
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(m));
        Enterprise ent = new Enterprise();
        ent.setEntId(77L);
        ent.setName("华东机械厂");
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of(ent));

        List<java.util.Map<String, Object>> rows = service.promosForProduct(101L);

        assertEquals(2, rows.size());
        assertEquals("全场满减", rows.get(0).get("title"));
        assertNull(rows.get(0).get("merName"));
        assertEquals("店铺折扣", rows.get(1).get("title"));
        assertEquals("华东机械厂", rows.get(1).get("merName"));
    }

    // ===== /campaign/list promo tab 混入商户促销 =====

    @Test
    @SuppressWarnings("unchecked")
    void userOngoingPromoTabMixesMerchantPromosWithMerName() {
        Campaign platform = campaign("full_reduce", "all", null, null, null, 1,
                new BigDecimal("500"), new BigDecimal("50"), null).setTitle("全场满减");
        Campaign mine = campaign("full_reduce", "prod", null, 10L, "1", 1,
                new BigDecimal("100"), new BigDecimal("30"), null).setTitle("店铺满减");
        Campaign pending = campaign("full_reduce", "prod", null, 10L, "1", 0,
                new BigDecimal("100"), new BigDecimal("30"), null).setTitle("待审促销");
        when(campaignMapper.selectList(any())).thenReturn(List.of(platform, mine, pending));

        Merchant m = merchant(10L, 1);
        m.setEntId(77L);
        when(merchantMapper.selectBatchIds(any())).thenReturn(List.of(m));
        Enterprise ent = new Enterprise();
        ent.setEntId(77L);
        ent.setName("华东机械厂");
        when(enterpriseMapper.selectBatchIds(any())).thenReturn(List.of(ent));

        List<java.util.Map<String, Object>> rows = service.userOngoing(7L, "promo");

        assertEquals(2, rows.size()); // 待审商户促销不出现
        assertEquals("全场满减", rows.get(0).get("title"));
        assertNull(rows.get(0).get("merName"));
        assertEquals("店铺满减", rows.get(1).get("title"));
        assertEquals("华东机械厂", rows.get(1).get("merName"));
    }
}

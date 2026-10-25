package com.lanlink.shopping.module.campaign.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.entity.CampaignMerchant;
import com.lanlink.shopping.module.campaign.entity.CampaignParticipant;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 统一活动系统单测：促销计价 / 领券（限领+超发防护）/ 券核销与回退 / 激励参与 / 商户激励领奖。
 */
class CampaignServiceTest {

    private CampaignMapper campaignMapper;
    private UserCouponMapper userCouponMapper;
    private CampaignParticipantMapper participantMapper;
    private CampaignMerchantMapper campaignMerchantMapper;
    private MerchantMapper merchantMapper;
    private IndustryMapper industryMapper;
    private MembershipService membershipService;
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
        service = new CampaignService(campaignMapper, userCouponMapper, participantMapper,
                campaignMerchantMapper, merchantMapper, industryMapper, membershipService);
    }

    private Campaign campaign(String type, String scope, Long indId,
                              BigDecimal threshold, BigDecimal benefit, BigDecimal rate) {
        return new Campaign()
                .setCampaignId(1L).setTitle("测试活动").setType(type)
                .setScope(scope).setIndId(indId).setThreshold(threshold)
                .setBenefitAmount(benefit).setDiscountRate(rate)
                .setStatus(1).setStartTime(LocalDateTime.now().minusDays(1))
                .setEndTime(LocalDateTime.now().plusDays(1))
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
    }

    // ===== 促销计价（PricingFacade 依赖的核心口径） =====

    @Test
    void calculateDiscountPicksBestAcrossTypes() {
        List<Campaign> promos = List.of(
                campaign("full_reduce", "all", null, new BigDecimal("500"), new BigDecimal("50"), null),
                campaign("discount", "all", null, new BigDecimal("100"), null, new BigDecimal("0.95")),
                campaign("flash_sale", "all", null, BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("0.90")));
        when(campaignMapper.selectList(any())).thenReturn(promos);

        // 满1000：满减50 / 折扣50 / 秒杀100 → 取最优 100
        assertEquals(0, new BigDecimal("100.00").compareTo(
                service.calculateDiscount(new BigDecimal("1000"), 1L)));
    }

    @Test
    void calculateDiscountAppliesCapAndIndustryScope() {
        List<Campaign> promos = List.of(
                campaign("flash_sale", "all", null, BigDecimal.ZERO, new BigDecimal("200"), new BigDecimal("0.90")),
                campaign("full_reduce", "ind", 2L, new BigDecimal("500"), new BigDecimal("80"), null));
        when(campaignMapper.selectList(any())).thenReturn(promos);

        // 满3000：秒杀 3000*0.1=300 → 封顶 200；行业满减(行业2)不适用于行业1订单
        assertEquals(0, new BigDecimal("200.00").compareTo(
                service.calculateDiscount(new BigDecimal("3000"), 1L)));
        // 行业2订单：行业满减 80 仍不敌秒杀封顶 200
        assertEquals(0, new BigDecimal("200.00").compareTo(
                service.calculateDiscount(new BigDecimal("3000"), 2L)));
    }

    @Test
    void calculateDiscountSkipsBelowThreshold() {
        when(campaignMapper.selectList(any())).thenReturn(List.of(
                campaign("full_reduce", "all", null, new BigDecimal("500"), new BigDecimal("50"), null)));
        // 400 < 门槛500 → 无优惠
        assertEquals(0, BigDecimal.ZERO.compareTo(service.calculateDiscount(new BigDecimal("400"), 1L)));
    }

    // ===== 领券：限领 + 超发防护 =====

    @Test
    void claimCouponInsertsWhenWithinLimitAndQuota() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null).setPerUserLimit(1).setTotalQuota(100));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(campaignMapper.claimOne(1L)).thenReturn(1);

        service.claimCoupon(7L, 1L);

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(captor.capture());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void claimCouponRejectsWhenPerUserLimitReached() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null).setPerUserLimit(1));
        when(userCouponMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.claimCoupon(7L, 1L));
        assertTrue(ex.getMessage().contains("限领"));
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void claimCouponRejectsWhenQuotaExhausted() {
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null).setPerUserLimit(3));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(campaignMapper.claimOne(1L)).thenReturn(0); // 原子扣减失败 = 已抢完

        BusinessException ex = assertThrows(BusinessException.class, () -> service.claimCoupon(7L, 1L));
        assertTrue(ex.getMessage().contains("抢完"));
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    // ===== 结算核销：归属/状态/门槛/行业 + 封顶 =====

    private UserCoupon myCoupon(int status) {
        UserCoupon uc = new UserCoupon();
        uc.setId(11L);
        uc.setCampaignId(1L);
        uc.setUserId(7L);
        uc.setStatus(status);
        return uc;
    }

    @Test
    void applyCouponMarksUsedAndReturnsBenefit() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(0));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null));

        BigDecimal benefit = service.applyCouponForOrder(7L, 11L, new BigDecimal("500"),
                1L, new BigDecimal("450"), "ORD1");

        assertEquals(0, new BigDecimal("30").compareTo(benefit));
        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("ORD1", captor.getValue().getOrderNo());
        assertNotNull(captor.getValue().getUsedTime());
    }

    @Test
    void applyCouponRejectsOtherOwnersCoupon() {
        UserCoupon others = myCoupon(0);
        others.setUserId(99L);
        when(userCouponMapper.selectById(11L)).thenReturn(others);

        assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L, new BigDecimal("450"), "ORD1"));
    }

    @Test
    void applyCouponRejectsUsedOrThresholdNotMet() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(1)); // 已使用
        assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L, new BigDecimal("450"), "ORD1"));

        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(0));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null));
        // 订单 150 < 门槛 200
        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("150"), 1L, new BigDecimal("140"), "ORD1"));
        assertTrue(ex.getMessage().contains("门槛"));
    }

    @Test
    void applyCouponBenefitCappedAtPayable() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(0));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null));

        // 券前应付仅 20 → 券面 30 被封顶为 20（保证应付 ≥ 0）
        BigDecimal benefit = service.applyCouponForOrder(7L, 11L, new BigDecimal("500"),
                1L, new BigDecimal("20"), "ORD1");
        assertEquals(0, new BigDecimal("20").compareTo(benefit));
    }

    @Test
    void applyCouponRejectsIndustryMismatch() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(0));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "ind", 2L,
                new BigDecimal("200"), new BigDecimal("30"), null));

        assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L, new BigDecimal("450"), "ORD1"));
    }

    // ===== 券回退（订单取消/全额退款） =====

    @Test
    void releaseCouponRestoresToUsable() {
        UserCoupon used = myCoupon(1);
        used.setOrderNo("ORD1");
        when(userCouponMapper.selectList(any())).thenReturn(List.of(used));

        service.releaseCoupon("ORD1");

        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        assertNull(captor.getValue().getOrderNo());
    }

    // ===== 激励参与（注册有礼 / 消费有礼） =====

    @Test
    void joinIncentiveRejectsDuplicateAndAwardsPoints() {
        Campaign register = campaign("register", "all", null, null, null, null).setRewardPoints(100);
        when(campaignMapper.selectById(1L)).thenReturn(register);
        when(participantMapper.selectCount(any())).thenReturn(0L);

        service.joinIncentive(7L, 1L);

        verify(membershipService).awardPoints(eq(7L), eq(100), eq("campaign:1"), any());

        // 重复参与被拒
        when(participantMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> service.joinIncentive(7L, 1L));
    }

    @Test
    void autoJoinPurchaseJoinsAllActivePurchaseCampaigns() {
        Campaign p1 = campaign("purchase", "all", null, null, null, null).setCampaignId(1L).setRewardPoints(100);
        Campaign p2 = campaign("purchase", "all", null, null, null, null).setCampaignId(2L).setRewardPoints(50);
        when(campaignMapper.selectList(any())).thenReturn(List.of(p1, p2));
        when(participantMapper.selectCount(any())).thenReturn(0L);

        service.autoJoinPurchase(7L);

        verify(participantMapper, times(2)).insert(any(CampaignParticipant.class));
        verify(membershipService).awardPoints(eq(7L), eq(100), eq("campaign:1"), any());
        verify(membershipService).awardPoints(eq(7L), eq(50), eq("campaign:2"), any());
    }

    // ===== 商户激励：报名 + 达标领奖 =====

    private Merchant merchant(Long merId, int reviewStatus) {
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(7L);
        m.setReviewStatus(reviewStatus);
        return m;
    }

    @Test
    void merchantJoinRequiresApprovedMerchant() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 0)); // 审核未通过
        assertThrows(BusinessException.class, () -> service.merchantJoin(7L, 1L));
    }

    @Test
    void merchantClaimRewardRequiresTargetReached() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        Campaign incentive = campaign("merchant_incentive", "all", null, null, null, null)
                .setMerTargetAmount(new BigDecimal("50000")).setMerRewardPoints(2000);
        when(campaignMapper.selectById(1L)).thenReturn(incentive);
        CampaignMerchant enrolled = new CampaignMerchant();
        enrolled.setCampaignId(1L);
        enrolled.setMerId(9L);
        enrolled.setRewarded(0);
        when(campaignMerchantMapper.selectOne(any())).thenReturn(enrolled);
        when(campaignMapper.merchantSalesInWindow(eq(9L), any(), any()))
                .thenReturn(new BigDecimal("40000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.merchantClaimReward(7L, 1L));
        assertTrue(ex.getMessage().contains("未达标"));

        // 达标 → 发奖并标记
        when(campaignMapper.merchantSalesInWindow(eq(9L), any(), any()))
                .thenReturn(new BigDecimal("60000"));
        service.merchantClaimReward(7L, 1L);
        verify(membershipService).awardPoints(eq(7L), eq(2000), eq("campaign:mer:1:9"), any());
        ArgumentCaptor<CampaignMerchant> captor = ArgumentCaptor.forClass(CampaignMerchant.class);
        verify(campaignMerchantMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getRewarded());
    }

    @Test
    void merchantClaimRewardRejectsNotEnrolledOrAlreadyRewarded() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("merchant_incentive", "all", null,
                null, null, null).setMerTargetAmount(BigDecimal.ONE).setMerRewardPoints(10));
        when(campaignMerchantMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.merchantClaimReward(7L, 1L));

        CampaignMerchant rewarded = new CampaignMerchant();
        rewarded.setRewarded(1);
        when(campaignMerchantMapper.selectOne(any())).thenReturn(rewarded);
        assertThrows(BusinessException.class, () -> service.merchantClaimReward(7L, 1L));
    }

    // ===== 管理端：类型化校验 + 删除保护 =====

    @Test
    void adminCreateValidatesTypeFields() {
        // 满减缺优惠金额 → 拒绝
        Campaign bad = campaign("full_reduce", "all", null, new BigDecimal("500"), null, null);
        assertThrows(BusinessException.class, () -> service.adminCreate(bad));

        // 折扣率越界 → 拒绝
        Campaign badRate = campaign("discount", "all", null, BigDecimal.ZERO, null, new BigDecimal("1.5"));
        assertThrows(BusinessException.class, () -> service.adminCreate(badRate));

        // 行业限定未选行业 → 拒绝
        Campaign badScope = campaign("coupon", "ind", null, BigDecimal.ZERO, new BigDecimal("10"), null);
        assertThrows(BusinessException.class, () -> service.adminCreate(badScope));

        // 合法领券活动 → 落库且默认下架
        Campaign good = campaign("coupon", "all", null, new BigDecimal("200"),
                new BigDecimal("30"), null).setStatus(null).setPerUserLimit(null);
        Campaign created = service.adminCreate(good);
        assertEquals(0, created.getStatus());
        assertEquals(1, created.getPerUserLimit());
        verify(campaignMapper).insert(any(Campaign.class));
    }

    @Test
    void adminDeleteBlockedWhenUsersClaimed() {
        when(userCouponMapper.selectCount(any())).thenReturn(3L);
        assertThrows(BusinessException.class, () -> service.adminDelete(1L));
        verify(campaignMapper, never()).deleteById(anyLong());
    }

    // ===== 回归：活动未配置行业（indId=null）时，券包/试算不得因 Map.of().get(null) 抛 NPE =====
    // 旧实现直接 indNames.get(c.getIndId())，industryNames 返回 Map.of()（不可变空 map）时
    // ImmutableCollections$MapN.get(null) 会 Objects.requireNonNull 抛 NPE。

    @Test
    void myCouponsHandlesNullIndustryIdWithoutNpe() {
        UserCoupon uc = myCoupon(0);
        when(userCouponMapper.selectList(any())).thenReturn(List.of(uc));
        Campaign noIndustry = campaign("coupon", "all", null,
                new BigDecimal("100"), new BigDecimal("10"), null).setCampaignId(1L);
        when(campaignMapper.selectBatchIds(any())).thenReturn(List.of(noIndustry));

        List<Map<String, Object>> rows = assertDoesNotThrow(() -> service.myCoupons(7L));

        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("indName"));
        assertEquals("usable", rows.get(0).get("state"));
    }

    @Test
    void quoteCouponsHandlesNullIndustryIdWithoutNpe() {
        UserCoupon uc = myCoupon(0);
        when(userCouponMapper.selectList(any())).thenReturn(List.of(uc));
        Campaign noIndustry = campaign("coupon", "all", null,
                new BigDecimal("100"), new BigDecimal("10"), null).setCampaignId(1L);
        when(campaignMapper.selectBatchIds(any())).thenReturn(List.of(noIndustry));

        List<Map<String, Object>> rows = assertDoesNotThrow(
                () -> service.quoteCoupons(7L, new BigDecimal("200"), 1L));

        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("indName"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) rows.get(0).get("benefit")));
    }
}

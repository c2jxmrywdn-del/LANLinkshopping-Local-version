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

        // 并发防线：先锁活动行，再查每人限领，最后原子扣减
        verify(campaignMapper).lockById(1L);
        verify(campaignMapper).claimOne(1L);
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
        when(userCouponMapper.useOne(11L, 7L, "ORD1")).thenReturn(1);

        BigDecimal benefit = service.applyCouponForOrder(7L, 11L, new BigDecimal("500"),
                1L, new BigDecimal("450"), "ORD1");

        assertEquals(0, new BigDecimal("30").compareTo(benefit));
        // 核销必须走原子条件更新（status 0→1），不能先查后写
        verify(userCouponMapper).useOne(11L, 7L, "ORD1");
    }

    @Test
    void applyCouponRejectsWhenConcurrentOrderWonTheCoupon() {
        when(userCouponMapper.selectById(11L)).thenReturn(myCoupon(0));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("coupon", "all", null,
                new BigDecimal("200"), new BigDecimal("30"), null));
        // 读到未使用，但原子核销时已被并发订单抢先用掉 → 0 行
        when(userCouponMapper.useOne(11L, 7L, "ORD1")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.applyCouponForOrder(7L, 11L, new BigDecimal("500"), 1L, new BigDecimal("450"), "ORD1"));
        assertTrue(ex.getMessage().contains("已被使用"));
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
        when(userCouponMapper.useOne(11L, 7L, "ORD1")).thenReturn(1);

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

    /**
     * B3：回退改为 UpdateWrapper 单条条件 UPDATE——MP 默认 NOT_NULL 字段策略会跳过
     * updateById 写入的 null（usedTime/orderNo 清空不生效），必须显式 SET used_time=NULL/order_no=NULL。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void releaseCouponExplicitlyNullsUsedTimeAndOrderNo() {
        service.releaseCoupon("ORD1");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<UserCoupon>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(userCouponMapper).update(isNull(), cap.capture()); // 无实体参与，纯 wrapper 更新
        com.baomidou.mybatisplus.core.conditions.update.Update w =
                (com.baomidou.mybatisplus.core.conditions.update.Update) cap.getValue();
        String sqlSet = w.getSqlSet();
        assertTrue(sqlSet.contains("status"), "SET 段应含 status 置 0: " + sqlSet);
        assertTrue(sqlSet.contains("used_time"), "SET 段应显式清空 used_time（NOT_NULL 策略绕过）: " + sqlSet);
        assertTrue(sqlSet.contains("order_no"), "SET 段应显式清空 order_no（NOT_NULL 策略绕过）: " + sqlSet);
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<UserCoupon, ?, ?> aw =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<UserCoupon, ?, ?>) cap.getValue();
        // SET 值经参数绑定落库：包含置 0 的 status 与两个 NULL（used_time/order_no）
        assertTrue(aw.getParamNameValuePairs().containsValue(0), "status 应被置 0: " + aw.getParamNameValuePairs());
        assertTrue(aw.getParamNameValuePairs().containsValue(null),
                "used_time/order_no 应显式绑定 NULL: " + aw.getParamNameValuePairs());
    }

    /**
     * 回退范围由 UPDATE 条件锁定：WHERE 必须携带 orderNo 等值 + status=1 过滤，
     * SQL 语义保证只有本单已核销券会被回退（NULL 亦不匹配），
     * "回退到不属于本单的券"在实现上不可能发生（评审 Issue 验证结论：不存在）。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void releaseCouponScopesUpdateToTheGivenOrderOnly() {
        service.releaseCoupon("ORD-A");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<UserCoupon>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(userCouponMapper).update(isNull(), cap.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<UserCoupon, ?, ?> w =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<UserCoupon, ?, ?>) cap.getValue();
        // 更新参数必须同时绑定订单号与状态=1：回退集合在 SQL 层就被限定为本单已核销券
        // 注：MP 条件参数是惰性注册（渲染 SQL 时才进 paramNameValuePairs），先触发渲染再断言
        String sqlSegment = w.getSqlSegment();
        assertTrue(sqlSegment.contains("order_no"), "WHERE 段应含 order_no 等值条件: " + sqlSegment);
        assertTrue(sqlSegment.contains("status"), "WHERE 段应含 status 条件: " + sqlSegment);
        assertTrue(w.getParamNameValuePairs().containsValue("ORD-A"),
                "更新条件应绑定 orderNo 等值过滤: " + w.getParamNameValuePairs());
        assertTrue(w.getParamNameValuePairs().containsValue(1),
                "更新条件应绑定 status=1 过滤: " + w.getParamNameValuePairs());
        // 不再先查后改（B3 前 selectList→updateById 读改写路径移除）
        verify(userCouponMapper, never()).selectList(any());
        verify(userCouponMapper, never()).updateById(any(UserCoupon.class));
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

    /**
     * B4：autoJoinPurchase 查询补 start_time 过滤（未开场活动不参与），与 effectiveCampaigns 同口径。
     * 时间过滤下沉在 SQL 条件里，故对查询 wrapper 的 SQL 片段断言。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void autoJoinPurchaseExcludesNotYetStartedCampaigns() {
        // 纯 mock 环境无 TableInfo 缓存，lambda 列名解析需先初始化实体元信息
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Campaign.class);
        when(campaignMapper.selectList(any())).thenReturn(List.of());

        service.autoJoinPurchase(7L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Campaign>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(campaignMapper).selectList(cap.capture());
        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<Campaign, ?, ?> w =
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<Campaign, ?, ?>) cap.getValue();
        String sqlSegment = w.getSqlSegment();
        assertTrue(sqlSegment.contains("start_time"),
                "查询应含 start_time 过滤（未开场不参与，同 effectiveCampaigns 口径）: " + sqlSegment);
        assertTrue(sqlSegment.contains("end_time"), "查询应含 end_time 过滤: " + sqlSegment);
        verify(participantMapper, never()).insert(any(CampaignParticipant.class));
    }

    // ===== B6：激励参与唯一键 uk_cp_campaign_user 并发兜底（DuplicateKey 幂等返回） =====

    @Test
    void joinIncentiveDuplicateKeyIsIdempotent() {
        Campaign register = campaign("register", "all", null, null, null, null).setRewardPoints(100);
        when(campaignMapper.selectById(1L)).thenReturn(register);
        when(participantMapper.selectCount(any())).thenReturn(0L);
        when(participantMapper.insert(any(CampaignParticipant.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_cp_campaign_user"));

        assertDoesNotThrow(() -> service.joinIncentive(7L, 1L));
        // 幂等返回不重复发分
        verify(membershipService, never()).awardPoints(any(), anyInt(), any(), any());
    }

    @Test
    void autoJoinPurchaseDuplicateKeySkipsAwardButContinues() {
        Campaign p1 = campaign("purchase", "all", null, null, null, null).setCampaignId(1L).setRewardPoints(100);
        Campaign p2 = campaign("purchase", "all", null, null, null, null).setCampaignId(2L).setRewardPoints(50);
        when(campaignMapper.selectList(any())).thenReturn(List.of(p1, p2));
        when(participantMapper.selectCount(any())).thenReturn(0L);
        when(participantMapper.insert(any(CampaignParticipant.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_cp_campaign_user"))
                .thenReturn(1);

        assertDoesNotThrow(() -> service.autoJoinPurchase(7L));

        verify(participantMapper, times(2)).insert(any(CampaignParticipant.class));
        // 撞唯一键的活动跳过发分，后续活动正常参与
        verify(membershipService, never()).awardPoints(eq(7L), eq(100), any(), any());
        verify(membershipService).awardPoints(eq(7L), eq(50), eq("campaign:2"), any());
    }

    @Test
    void merchantJoinDuplicateKeyIsIdempotent() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(9L, 1));
        when(campaignMapper.selectById(1L)).thenReturn(campaign("merchant_incentive", "all", null, null, null, null));
        when(campaignMerchantMapper.insert(any(CampaignMerchant.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_campaign_mer"));

        assertDoesNotThrow(() -> service.merchantJoin(7L, 1L));
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

    // ===== B5：管理端写路径条件更新，绝不写回 claimed_count 等计数列 =====

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminToggleOnlySetsStatusAndUpdateTime() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Campaign.class);
        when(campaignMapper.update(any(), any())).thenReturn(1);

        service.adminToggle(1L, 1);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Campaign>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(campaignMapper).update(isNull(), cap.capture());
        com.baomidou.mybatisplus.core.conditions.update.Update w =
                (com.baomidou.mybatisplus.core.conditions.update.Update) cap.getValue();
        String sqlSet = w.getSqlSet();
        assertTrue(sqlSet.contains("status"), "toggle 应仅 SET status: " + sqlSet);
        assertTrue(sqlSet.contains("update_time"), "toggle 应 SET update_time: " + sqlSet);
        assertFalse(sqlSet.contains("claimed_count"), "toggle 不得写回 claimed_count: " + sqlSet);
        assertFalse(sqlSet.contains("title"), "toggle 不得写回其他列: " + sqlSet);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminUpdateWritesOnlyTargetColumnsAndKeepsCounters() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Campaign.class);
        Campaign stored = new Campaign().setCampaignId(1L)
                .setClaimedCount(88).setCreateTime(LocalDateTime.now());
        when(campaignMapper.selectById(1L)).thenReturn(stored);

        Campaign in = campaign("coupon", "all", null, new BigDecimal("200"), new BigDecimal("30"), null)
                .setClaimedCount(999999); // 恶意/误传计数
        Campaign saved = service.adminUpdate(1L, in);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<Campaign>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(campaignMapper).update(isNull(), cap.capture());
        com.baomidou.mybatisplus.core.conditions.update.Update w =
                (com.baomidou.mybatisplus.core.conditions.update.Update) cap.getValue();
        String sqlSet = w.getSqlSet();
        assertTrue(sqlSet.contains("title"), "更新应 SET title: " + sqlSet);
        assertTrue(sqlSet.contains("update_time"), "更新应 SET update_time: " + sqlSet);
        assertFalse(sqlSet.contains("claimed_count"), "adminUpdate 不得写回 claimed_count: " + sqlSet);
        assertFalse(sqlSet.contains("mer_id"), "adminUpdate 不得写回 mer_id: " + sqlSet);
        assertFalse(sqlSet.contains("prod_ids"), "adminUpdate 不得写回 prod_ids: " + sqlSet);
        assertFalse(sqlSet.contains("review_status"), "adminUpdate 不得写回 review_status: " + sqlSet);
        assertFalse(sqlSet.contains("review_note"), "adminUpdate 不得写回 review_note: " + sqlSet);
        // 响应契约：claimed_count 仍回显真实计数
        assertEquals(88, saved.getClaimedCount());
    }

    // ===== B7：商户促销长度护栏（与 schema 列宽对齐） =====

    @Test
    void adminCreateRejectsOversizeTitleDescriptionAndProdIds() {
        // title > 100 字符（列宽 VARCHAR(100)）
        Campaign longTitle = campaign("full_reduce", "all", null, BigDecimal.TEN, BigDecimal.TEN, null)
                .setTitle("长".repeat(101));
        BusinessException ex1 = assertThrows(BusinessException.class, () -> service.adminCreate(longTitle));
        assertTrue(ex1.getMessage().contains("活动名称"));
        verify(campaignMapper, never()).insert(any(Campaign.class));

        // description > 500 字符（列宽 VARCHAR(500)）
        Campaign longDesc = campaign("full_reduce", "all", null, BigDecimal.TEN, BigDecimal.TEN, null)
                .setDescription("描".repeat(501));
        BusinessException ex2 = assertThrows(BusinessException.class, () -> service.adminCreate(longDesc));
        assertTrue(ex2.getMessage().contains("活动描述"));
        verify(campaignMapper, never()).insert(any(Campaign.class));

        // prodIds 超量（>50 个商品）
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 51; i++) {
            if (i > 1) ids.append(',');
            ids.append(i);
        }
        Campaign tooManyProds = campaign("full_reduce", "all", null, BigDecimal.TEN, BigDecimal.TEN, null)
                .setScope("prod").setProdIds(ids.toString());
        BusinessException ex3 = assertThrows(BusinessException.class, () -> service.adminCreate(tooManyProds));
        assertTrue(ex3.getMessage().contains("50 个"), "应提示商品数量上限: " + ex3.getMessage());
        verify(campaignMapper, never()).insert(any(Campaign.class));

        // prodIds 拼接超长（去重后仅 1 个商品但字符串 > 490 字符，列宽 VARCHAR(500)）
        Campaign longProdIds = campaign("full_reduce", "all", null, BigDecimal.TEN, BigDecimal.TEN, null)
                .setScope("prod").setProdIds("1,".repeat(250));
        BusinessException ex4 = assertThrows(BusinessException.class, () -> service.adminCreate(longProdIds));
        assertTrue(ex4.getMessage().contains("490"), "应提示商品列表长度上限: " + ex4.getMessage());
        verify(campaignMapper, never()).insert(any(Campaign.class));
    }

    // ===== B9：discount_rate 先按 DECIMAL(4,3) 列精度取整再判开区间 (0,1) =====

    @Test
    void discountRateRoundedToColumnScaleBeforeRangeCheck() {
        // 0.9995 按三位小数 HALF_UP 取整为 1.000 → 越界拒绝（防 Java 校验通过而落库被列精度收整）
        Campaign badRate = campaign("discount", "all", null, BigDecimal.ZERO, null, new BigDecimal("0.9995"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.adminCreate(badRate));
        assertTrue(ex.getMessage().contains("三位小数"), "提示应说明可用精度三位小数: " + ex.getMessage());
        verify(campaignMapper, never()).insert(any(Campaign.class));

        // 0.99945 → 取整 0.999 → 合法且按列精度落库
        Campaign okRate = campaign("discount", "all", null, BigDecimal.ZERO, null, new BigDecimal("0.99945"));
        Campaign saved = service.adminCreate(okRate);
        assertEquals(0, new BigDecimal("0.999").compareTo(saved.getDiscountRate()));
        verify(campaignMapper).insert(okRate);
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

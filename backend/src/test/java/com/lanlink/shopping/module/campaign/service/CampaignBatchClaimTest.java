package com.lanlink.shopping.module.campaign.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.entity.UserCoupon;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 领券中心单测（G2 口径）：couponCenter 券池聚合 + batchClaim 批量领取
 * （循环复用 claimCoupon 原子领取：claimOne 条件 UPDATE + lockById 行锁；部分成功可接受）。
 */
class CampaignBatchClaimTest {

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

    private Campaign campaign(String type, Long campaignId) {
        return new Campaign()
                .setCampaignId(campaignId).setTitle("活动" + campaignId).setType(type)
                .setScope("all").setStatus(1)
                .setStartTime(LocalDateTime.now().minusDays(1))
                .setEndTime(LocalDateTime.now().plusDays(1))
                .setCreateTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
    }

    private Campaign coupon(Long campaignId, int perUserLimit, int totalQuota, int claimedCount) {
        return campaign("coupon", campaignId)
                .setThreshold(new BigDecimal("200"))
                .setBenefitAmount(new BigDecimal("30"))
                .setPerUserLimit(perUserLimit).setTotalQuota(totalQuota).setClaimedCount(claimedCount);
    }

    // ===== couponCenter：券池聚合（复用 userOngoing(tab=coupon) 口径） =====

    @Test
    void couponCenterReturnsOnlyCouponCampaignsWithQuotaFields() {
        when(campaignMapper.selectList(any())).thenReturn(List.of(
                coupon(1L, 2, 100, 30),
                campaign("full_reduce", 2L).setThreshold(new BigDecimal("500")).setBenefitAmount(new BigDecimal("50"))));
        UserCoupon mine = new UserCoupon();
        mine.setCampaignId(1L);
        mine.setUserId(7L);
        when(userCouponMapper.selectList(any())).thenReturn(List.of(mine));

        List<Map<String, Object>> rows = service.couponCenter(7L);

        // 促销（full_reduce）不进券池
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(1L, row.get("campaignId"));
        assertEquals(2, row.get("perUserLimit"));
        assertEquals(100, row.get("totalQuota"));
        assertEquals(70L, row.get("remainQuota")); // toUserView 口径：remain 为 long，落 Map 为 Long
        assertEquals(1L, row.get("myCouponCount"));
        assertEquals(false, row.get("limitReached"));
        assertEquals(true, row.get("canClaim"));
    }

    @Test
    void couponCenterAnonymousBrowseKeepsClaimableState() {
        when(campaignMapper.selectList(any())).thenReturn(List.of(coupon(1L, 1, 100, 100)));

        List<Map<String, Object>> rows = service.couponCenter(null);

        assertEquals(1, rows.size());
        // 无个人领取记录：不限领；但余量 0 → 不可领
        assertEquals(0L, rows.get(0).get("myCouponCount"));
        assertEquals(0L, rows.get(0).get("remainQuota"));
        assertEquals(false, rows.get(0).get("canClaim"));
    }

    // ===== batchClaim：逐券原子领取，部分成功可接受 =====

    @Test
    void batchClaimClaimsEachCouponAtomically() {
        when(campaignMapper.selectById(1L)).thenReturn(coupon(1L, 1, 100, 0));
        when(campaignMapper.selectById(2L)).thenReturn(coupon(2L, 1, 100, 0));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(campaignMapper.claimOne(anyLong())).thenReturn(1);

        List<Map<String, Object>> results = service.batchClaim(7L, List.of(1L, 2L));

        assertEquals(2, results.size());
        assertTrue((Boolean) results.get(0).get("ok"));
        assertTrue((Boolean) results.get(1).get("ok"));
        // 单券原子语义逐券保持：先锁活动行，再原子扣减，再落券
        InOrder perCoupon = inOrder(campaignMapper, userCouponMapper);
        perCoupon.verify(campaignMapper).lockById(1L);
        perCoupon.verify(campaignMapper).claimOne(1L);
        perCoupon.verify(userCouponMapper).insert(any(UserCoupon.class));
        perCoupon.verify(campaignMapper).lockById(2L);
        perCoupon.verify(campaignMapper).claimOne(2L);
        perCoupon.verify(userCouponMapper).insert(any(UserCoupon.class));
    }

    @Test
    void batchClaimReturnsPartialSuccessOnExhaustedCoupon() {
        when(campaignMapper.selectById(1L)).thenReturn(coupon(1L, 1, 100, 0));
        when(campaignMapper.selectById(2L)).thenReturn(coupon(2L, 1, 0, 0));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(campaignMapper.claimOne(1L)).thenReturn(1);
        when(campaignMapper.claimOne(2L)).thenReturn(0); // 已抢完（条件 UPDATE 0 行）

        List<Map<String, Object>> results = service.batchClaim(7L, List.of(1L, 2L));

        assertEquals(2, results.size());
        assertTrue((Boolean) results.get(0).get("ok"));
        assertEquals(false, results.get(1).get("ok"));
        assertTrue(String.valueOf(results.get(1).get("reason")).contains("抢完"));
        // 失败券不落券包，成功券不受影响
        verify(userCouponMapper, times(1)).insert(any(UserCoupon.class));
    }

    @Test
    void batchClaimDedupesIdsAndLocksInAscendingOrder() {
        when(campaignMapper.selectById(1L)).thenReturn(coupon(1L, 1, 100, 0));
        when(campaignMapper.selectById(3L)).thenReturn(coupon(3L, 1, 100, 0));
        when(userCouponMapper.selectCount(any())).thenReturn(0L);
        when(campaignMapper.claimOne(anyLong())).thenReturn(1);

        // 乱序 + 重复入参：去重后按升序加锁（固定锁序防并发批量领取交叉死锁）
        List<Map<String, Object>> results = service.batchClaim(7L, List.of(3L, 1L, 1L));

        assertEquals(2, results.size());
        assertEquals(1L, results.get(0).get("campaignId"));
        assertEquals(3L, results.get(1).get("campaignId"));
        InOrder locks = inOrder(campaignMapper);
        locks.verify(campaignMapper).lockById(1L);
        locks.verify(campaignMapper).lockById(3L);
        verify(userCouponMapper, times(2)).insert(any(UserCoupon.class));
    }

    @Test
    void batchClaimReportsPerUserLimitAndNonCouponReasons() {
        when(campaignMapper.selectById(1L)).thenReturn(coupon(1L, 1, 100, 0));
        when(campaignMapper.selectById(2L)).thenReturn(campaign("full_reduce", 2L));
        when(userCouponMapper.selectCount(any())).thenReturn(1L); // 券1已达每人限领上限

        List<Map<String, Object>> results = service.batchClaim(7L, List.of(1L, 2L));

        assertEquals(false, results.get(0).get("ok"));
        assertTrue(String.valueOf(results.get(0).get("reason")).contains("限领"));
        assertEquals(false, results.get(1).get("ok"));
        assertTrue(String.valueOf(results.get(1).get("reason")).contains("不存在"));
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void batchClaimRequiresLoginAndNonEmptySelection() {
        assertThrows(BusinessException.class, () -> service.batchClaim(null, List.of(1L)));
        assertThrows(BusinessException.class, () -> service.batchClaim(7L, null));
        assertThrows(BusinessException.class, () -> service.batchClaim(7L, List.of()));
        verify(campaignMapper, never()).lockById(anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }
}

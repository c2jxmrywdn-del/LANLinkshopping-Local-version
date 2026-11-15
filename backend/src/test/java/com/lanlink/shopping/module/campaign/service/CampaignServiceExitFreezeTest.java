package com.lanlink.shopping.module.campaign.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignMerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.CampaignParticipantMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.module.campaign.mapper.UserCouponMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动写操作在退店流程中的冻结（设计 §5）：报名 / 领奖 / 自建促销三处一律拒绝；
 * 促销上/下架切换仅"上架"方向拒绝（下架放行）。
 */
class CampaignServiceExitFreezeTest {

    private CampaignMapper campaignMapper;
    private CampaignMerchantMapper campaignMerchantMapper;
    private MerchantMapper merchantMapper;
    private CampaignService svc;

    @BeforeEach
    void setup() {
        // 纯单元测试不启动 MyBatis，需手动初始化实体元数据，LambdaQueryWrapper 才能解析列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Merchant.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Campaign.class);
        campaignMapper = mock(CampaignMapper.class);
        campaignMerchantMapper = mock(CampaignMerchantMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        svc = new CampaignService(campaignMapper, mock(UserCouponMapper.class),
                mock(CampaignParticipantMapper.class), campaignMerchantMapper,
                merchantMapper, mock(IndustryMapper.class), mock(MembershipService.class));
    }

    private Merchant merchant(String exitStatus) {
        Merchant m = new Merchant();
        m.setMerId(1L);
        m.setUserId(7L);
        m.setReviewStatus(1);
        m.setExitStatus(exitStatus);
        return m;
    }

    @Test
    void merchantJoin_is_rejected_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("EXIT_REQUESTED"));

        BusinessException e = assertThrows(BusinessException.class, () -> svc.merchantJoin(7L, 100L));
        assertEquals(400, e.getCode());
        verify(campaignMapper, never()).selectById(anyLong());
    }

    @Test
    void merchantClaimReward_is_rejected_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("PUBLIC_NOTICE"));

        assertThrows(BusinessException.class, () -> svc.merchantClaimReward(7L, 100L));
        verify(campaignMerchantMapper, never()).selectOne(any());
    }

    @Test
    void merchantCreate_is_rejected_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("FROZEN"));

        assertThrows(BusinessException.class, () -> svc.merchantCreate(7L, null));
        verify(campaignMapper, never()).insert(any(Campaign.class));
    }

    @Test
    void merchantToggle_up_is_rejected_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("PUBLIC_NOTICE"));
        Campaign c = new Campaign();
        c.setCampaignId(100L);
        c.setMerId(1L);
        c.setStatus(0); // 当前下架 → 目标为上架
        c.setReviewStatus(1); // 已过审：确保唯一拦截来源是退店守卫，而非"未过审"分支
        when(campaignMapper.selectById(100L)).thenReturn(c);

        assertThrows(BusinessException.class, () -> svc.merchantToggle(7L, 100L));
        verify(campaignMapper, never()).update(any(), any());
    }

    @Test
    void merchantToggle_down_is_allowed_while_exiting() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant("FROZEN"));
        Campaign c = new Campaign();
        c.setCampaignId(100L);
        c.setMerId(1L);
        c.setStatus(1); // 当前上架 → 目标为下架
        when(campaignMapper.selectById(100L)).thenReturn(c);
        when(campaignMapper.update(any(), any())).thenReturn(1);

        svc.merchantToggle(7L, 100L);

        // 下架放行：退店中仍允许商户把自己的活动撤下来
        verify(campaignMapper).update(any(), any());
    }

    @Test
    void active_merchant_join_proceeds_past_the_guard() {
        when(merchantMapper.selectOne(any())).thenReturn(merchant(null));
        when(campaignMapper.selectById(100L)).thenReturn(null);

        // 越过冻结门禁后走到业务校验（活动不存在），证明守卫未误伤正常商户
        BusinessException e = assertThrows(BusinessException.class, () -> svc.merchantJoin(7L, 100L));
        assertEquals("活动不存在或已结束", e.getMessage());
    }
}

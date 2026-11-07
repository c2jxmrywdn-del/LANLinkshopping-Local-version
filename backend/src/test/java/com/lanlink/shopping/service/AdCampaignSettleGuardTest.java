package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.AdCampaign;
import com.lanlink.shopping.mapper.AdCampaignMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P0-2 广告结算防双退单测：settle 以条件 UPDATE 抢占结算权（WHERE settled IS NULL），
 * 抢占失败方直接返回最新已结算行、绝不重复退钱包——覆盖到期定时任务与手动终止并发、
 * 以及并发 terminate 的窗口。
 */
class AdCampaignSettleGuardTest {

    private AdCampaignMapper campaignMapper;
    private MerchantMapper merchantMapper;
    private ProductMapper productMapper;
    private WalletService walletService;
    private AdCampaignService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(AdCampaignMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        productMapper = mock(ProductMapper.class);
        walletService = mock(WalletService.class);
        service = new AdCampaignService(campaignMapper, merchantMapper, productMapper, walletService);
    }

    private AdCampaign unsettled(long id) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setUserId(7L);
        c.setStatus("active");
        c.setTotalBudget(new BigDecimal("100.00"));
        c.setSpent(new BigDecimal("30.00"));
        c.setSettled(null);
        return c;
    }

    private AdCampaign settled(long id, String remain) {
        AdCampaign c = unsettled(id);
        c.setStatus("ended");
        c.setSettled(new BigDecimal(remain));
        return c;
    }

    @Test
    @DisplayName("抢占成功方按行内 settled 金额退钱包一次")
    void claimWinnerRefundsWalletOnce() {
        when(campaignMapper.claimSettle(11L, "manual")).thenReturn(1);
        when(campaignMapper.selectById(11L)).thenReturn(settled(11L, "70.00"));

        AdCampaign result = service.settle(unsettled(11L), "manual");

        assertEquals("ended", result.getStatus());
        assertEquals(new BigDecimal("70.00"), result.getSettled());
        verify(walletService, times(1)).refundToWallet(eq(7L), eq(new BigDecimal("70.00")),
                eq("AD-11"), anyString());
    }

    @Test
    @DisplayName("并发落败方（affected=0）返回最新已结算行且不再退钱包：定时任务与手动终止并发只退一次")
    void concurrentLoserNeverRefundsAgain() {
        when(campaignMapper.claimSettle(11L, "manual")).thenReturn(0);
        when(campaignMapper.selectById(11L)).thenReturn(settled(11L, "70.00"));

        AdCampaign result = service.settle(unsettled(11L), "manual");

        assertEquals("ended", result.getStatus());
        assertEquals(new BigDecimal("70.00"), result.getSettled());
        verify(walletService, never()).refundToWallet(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("剩余为 0（预算耗尽）也落结算字段：settled=0 非空，幂等不再结算")
    void zeroRemainStillSettlesWithoutRefund() {
        when(campaignMapper.claimSettle(11L, "budget_exhausted")).thenReturn(1);
        when(campaignMapper.selectById(11L)).thenReturn(settled(11L, "0.00"));

        AdCampaign result = service.settle(unsettled(11L), "budget_exhausted");

        assertEquals("ended", result.getStatus());
        assertEquals(new BigDecimal("0.00"), result.getSettled());
        verify(walletService, never()).refundToWallet(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("已结算的行调用 settle 直接幂等返回，不触发抢占与退款")
    void alreadySettledRowIsIdempotent() {
        AdCampaign settled = settled(11L, "70.00");

        AdCampaign result = service.settle(settled, "expired");

        assertSame(settled, result);
        verify(campaignMapper, never()).claimSettle(anyLong(), anyString());
        verify(walletService, never()).refundToWallet(anyLong(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("到期定时任务逐单结算：每个抢占成功的计划各退一次，无计划重复退款")
    void expireCheckSettlesEachExpiredCampaignOnce() {
        AdCampaign a = unsettled(11L);
        AdCampaign b = unsettled(12L);
        when(campaignMapper.expiredCampaigns()).thenReturn(List.of(a, b));
        when(campaignMapper.claimSettle(11L, "expired")).thenReturn(1);
        when(campaignMapper.claimSettle(12L, "expired")).thenReturn(1);
        when(campaignMapper.selectById(11L)).thenReturn(settled(11L, "70.00"));
        when(campaignMapper.selectById(12L)).thenReturn(settled(12L, "40.00"));

        service.expireCheck();

        verify(walletService, times(1)).refundToWallet(eq(7L), eq(new BigDecimal("70.00")), eq("AD-11"), anyString());
        verify(walletService, times(1)).refundToWallet(eq(7L), eq(new BigDecimal("40.00")), eq("AD-12"), anyString());
    }

    @Test
    @DisplayName("手动终止走同一抢占路径：状态守卫通过后仍以 affected 行数决定是否退款")
    void terminateGoesThroughClaim() {
        AdCampaign c = unsettled(11L);
        c.setStatus("paused");
        when(campaignMapper.claimSettle(11L, "manual")).thenReturn(1);
        // 第 1 次 selectById（mustOwn）返回暂停中的计划，第 2 次（settle 抢占后回读）返回已结算行
        when(campaignMapper.selectById(11L)).thenReturn(c, settled(11L, "70.00"));

        AdCampaign result = service.terminate(7L, 11L);

        assertEquals("ended", result.getStatus());
        verify(walletService, times(1)).refundToWallet(eq(7L), eq(new BigDecimal("70.00")), eq("AD-11"), anyString());
        verify(campaignMapper, never()).updateById(any(AdCampaign.class));
    }
}

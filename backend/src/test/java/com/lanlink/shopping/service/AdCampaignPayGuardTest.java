package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.AdCampaign;
import com.lanlink.shopping.mapper.AdCampaignMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 广告计划支付防重复扣款（R01）。
 *
 * <p>缺陷形态：原 {@code pay()} 是「selectById 判状态 → 扣钱包 → updateById」，
 * 并发双击时两个线程都读到 pending_payment，各扣一次钱包，商户被扣两倍预算。
 *
 * <p>修复形态：先做 {@code pending_payment → active} 条件更新抢占，只有抢到 1 行的线程才扣款。
 * 这里直接断言「抢占失败时钱包零调用」，这是资金正确性的可执行证据。
 */
class AdCampaignPayGuardTest {

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

    private AdCampaign pending(long id, long userId, String budget) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setUserId(userId);
        c.setStatus("pending_payment");
        c.setTotalBudget(new BigDecimal(budget));
        c.setDurationDays(7);
        c.setProdId(1400L);
        return c;
    }

    @Test
    @DisplayName("抢占成功后扣款一次，返回库内最新状态")
    void paysOnceWhenClaimSucceeds() {
        AdCampaign before = pending(77L, 5L, "100.00");
        AdCampaign after = pending(77L, 5L, "100.00");
        after.setStatus("active");
        when(campaignMapper.selectById(77L)).thenReturn(before, after);
        when(campaignMapper.activateFromPending(77L)).thenReturn(1);

        AdCampaign result = service.pay(5L, 77L);

        verify(walletService, times(1)).payFromWallet(5L, new BigDecimal("100.00"), "AD-77");
        assertEquals("active", result.getStatus());
    }

    @Test
    @DisplayName("抢占失败（并发双击的第二下）必须一分钱都不扣")
    void rejectsWithoutChargingWhenClaimLoses() {
        when(campaignMapper.selectById(77L)).thenReturn(pending(77L, 5L, "100.00"));
        when(campaignMapper.activateFromPending(77L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(5L, 77L));

        assertEquals(409, ex.getCode());
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("余额不足时异常上抛，由事务回滚状态抢占")
    void propagatesInsufficientBalance() {
        when(campaignMapper.selectById(77L)).thenReturn(pending(77L, 5L, "100.00"));
        when(campaignMapper.activateFromPending(77L)).thenReturn(1);
        doThrow(new BusinessException("钱包余额不足，请充值或更换支付方式"))
                .when(walletService).payFromWallet(anyLong(), any(), anyString());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.pay(5L, 77L));

        assertTrue(ex.getMessage().contains("余额不足"));
    }

    @Test
    @DisplayName("非待支付状态在抢占前就被拒绝")
    void rejectsNonPendingBeforeClaim() {
        AdCampaign active = pending(77L, 5L, "100.00");
        active.setStatus("active");
        when(campaignMapper.selectById(77L)).thenReturn(active);

        assertThrows(BusinessException.class, () -> service.pay(5L, 77L));

        verify(campaignMapper, never()).activateFromPending(anyLong());
        verifyNoInteractions(walletService);
    }
}

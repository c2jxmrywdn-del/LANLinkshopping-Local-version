package com.lanlink.shopping.payment.wallet;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.payment.wallet.entity.Wallet;
import com.lanlink.shopping.payment.wallet.entity.WalletLog;
import com.lanlink.shopping.payment.wallet.mapper.WalletLogMapper;
import com.lanlink.shopping.payment.wallet.mapper.WalletMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 钱包服务单测：金额校验 / 充值 / 余额支付（条件更新防透支）/ 退款入账
 */
class WalletServiceTest {

    private WalletMapper walletMapper;
    private WalletLogMapper logMapper;
    private WalletService service;

    private Wallet wallet(String balance) {
        Wallet w = new Wallet();
        w.setId(1L);
        w.setUserId(2L);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    @BeforeEach
    void setUp() {
        walletMapper = mock(WalletMapper.class);
        logMapper = mock(WalletLogMapper.class);
        service = new WalletService(walletMapper, logMapper);
    }

    // ===== 充值 =====

    @Test
    void rechargeRejectsInvalidAmount() {
        assertThrows(BusinessException.class, () -> service.recharge(2L, BigDecimal.ZERO, null));
        assertThrows(BusinessException.class, () -> service.recharge(2L, new BigDecimal("-1"), null));
        // 三位小数
        assertThrows(BusinessException.class, () -> service.recharge(2L, new BigDecimal("1.234"), null));
    }

    @Test
    void rechargeRejectsOverLimit() {
        assertThrows(BusinessException.class, () -> service.recharge(2L, new BigDecimal("50000.01"), null));
    }

    @Test
    void rechargeCreditsAndWritesLog() {
        when(walletMapper.selectOne(any())).thenReturn(wallet("100.00"));
        when(walletMapper.credit(eq(2L), any())).thenReturn(1);
        Wallet after = service.recharge(2L, new BigDecimal("50.00"), null);
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getBalance()));
        verify(walletMapper).credit(eq(2L), eq(new BigDecimal("50.00")));
        verify(logMapper).insert(argThat((WalletLog l) ->
                "recharge".equals(l.getChangeType())
                        && new BigDecimal("50.00").compareTo(l.getAmount()) == 0
                        && new BigDecimal("100.00").compareTo(l.getBalanceAfter()) == 0));
    }

    // ===== 余额支付 =====

    @Test
    void payRejectsWhenInsufficient() {
        when(walletMapper.selectOne(any())).thenReturn(wallet("30.00"));
        // 条件更新未命中（余额不足/并发竞争）
        when(walletMapper.deduct(eq(2L), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.payFromWallet(2L, new BigDecimal("50.00"), "NO1"));
        verify(logMapper, never()).insert(any(WalletLog.class));
    }

    @Test
    void payDeductsAndWritesNegativeLog() {
        when(walletMapper.selectOne(any())).thenReturn(wallet("50.00"));
        when(walletMapper.deduct(eq(2L), any())).thenReturn(1);
        WalletLog wl = service.payFromWallet(2L, new BigDecimal("50.00"), "NO1");
        assertNotNull(wl);
        verify(walletMapper).deduct(eq(2L), eq(new BigDecimal("50.00")));
        verify(logMapper).insert(argThat((WalletLog l) ->
                "pay".equals(l.getChangeType())
                        && new BigDecimal("-50.00").compareTo(l.getAmount()) == 0
                        && "NO1".equals(l.getRefOrderNo())));
    }

    // ===== 退款入账 =====

    @Test
    void refundCreditsWallet() {
        when(walletMapper.selectOne(any())).thenReturn(wallet("20.00"));
        when(walletMapper.credit(eq(2L), any())).thenReturn(1);
        WalletLog wl = service.refundToWallet(2L, new BigDecimal("30.00"), "NO1", null);
        assertNotNull(wl);
        verify(walletMapper).credit(eq(2L), eq(new BigDecimal("30.00")));
        verify(logMapper).insert(argThat((WalletLog l) ->
                "refund".equals(l.getChangeType())
                        && new BigDecimal("30.00").compareTo(l.getAmount()) == 0));
    }

    // ===== 建卡 =====

    @Test
    void getOrCreateInsertsWhenMissing() {
        when(walletMapper.selectOne(any())).thenReturn(null);
        Wallet w = service.getOrCreate(2L);
        assertNotNull(w);
        verify(walletMapper).insert(any(Wallet.class));
    }
}

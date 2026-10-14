package com.lanlink.shopping.module.membership.service;

import com.lanlink.shopping.module.membership.constant.MemberRule;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.entity.MemberPointLog;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 积分抵现单测（规则：1000 积分 = 1 元，抵扣最小单位 1 元，单笔上限 10%）。
 * 边界覆盖：积分不足抵扣 / 积分刚好足额 / 积分超额抵扣 / 不足 1 元零头不参与抵扣 /
 *          单笔 10% 上限截断（上限非整元时向下取整到整元）/ 取消返还幂等。
 */
class MembershipRedeemTest {

    private MemberCardMapper cardMapper;
    private MemberLevelMapper levelMapper;
    private MemberPointLogMapper logMapper;
    private MembershipService service;

    private MemberCard card(int points) {
        MemberCard c = new MemberCard();
        c.setCardId(1L); c.setUserId(2L); c.setLevelId(1L);
        c.setGrowth(points); c.setPoints(points);
        return c;
    }

    @BeforeEach
    void setUp() {
        cardMapper = mock(MemberCardMapper.class);
        levelMapper = mock(MemberLevelMapper.class);
        logMapper = mock(MemberPointLogMapper.class);
        service = new MembershipService(cardMapper, levelMapper, logMapper);
    }

    // ===== MemberRule 基础换算 =====

    @Test
    void ruleRatioAndMinimumUnit() {
        assertEquals(1000, MemberRule.REDEEM_POINTS_PER_YUAN, "兑换比例应为 1000 积分 = 1 元");
        // 整元抵扣：不足 1000 的零头不参与抵扣
        assertEquals(0, MemberRule.pointsToAmount(0).compareTo(BigDecimal.ZERO));
        assertEquals(0, MemberRule.pointsToAmount(999).compareTo(BigDecimal.ZERO), "999 积分不足 1 元不抵扣");
        assertEquals(0, MemberRule.pointsToAmount(1000).compareTo(BigDecimal.ONE), "1000 积分抵 1 元");
        assertEquals(0, MemberRule.pointsToAmount(1500).compareTo(BigDecimal.ONE), "1500 积分按整元只抵 1 元");
        assertEquals(0, MemberRule.pointsToAmount(2000).compareTo(new BigDecimal("2")), "2000 积分抵 2 元");
        // 归整到 1000 整数倍
        assertEquals(0, MemberRule.usablePoints(0));
        assertEquals(0, MemberRule.usablePoints(999), "999 归整为 0");
        assertEquals(1000, MemberRule.usablePoints(1000));
        assertEquals(1000, MemberRule.usablePoints(1500), "1500 归整为 1000，500 零头不抵");
        assertEquals(3000, MemberRule.usablePoints(3000));
    }

    // ===== 试算 redeemQuote =====

    @Test
    void quoteNormalRedeem() {
        when(cardMapper.selectOne(any())).thenReturn(card(5000));
        // 请求 2500 积分：零头 500 不抵 → 实际 2000 积分 = 2 元；订单 1000 元上限 100 元不受限
        var q = service.redeemQuote(2L, 2500, new BigDecimal("1000"));
        assertEquals(2000, q.get("points"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) q.get("amount")));
        assertEquals(5000, q.get("balance"));
        assertEquals(0, new BigDecimal("998").compareTo((BigDecimal) q.get("payable")), "实付 = 1000 - 2");
    }

    @Test
    void quoteExactFullUse() {
        // 积分刚好足额：请求 = 余额 = 3000 → 抵 3 元，实付 497
        when(cardMapper.selectOne(any())).thenReturn(card(3000));
        var q = service.redeemQuote(2L, 3000, new BigDecimal("500"));
        assertEquals(3000, q.get("points"));
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) q.get("amount")));
        assertEquals(0, new BigDecimal("497").compareTo((BigDecimal) q.get("payable")));
    }

    @Test
    void quoteInsufficientBalanceBelowOneYuan() {
        // 积分不足抵扣：余额 800 不足 1 元（1000 分）→ 0 积分、0 元，不抛错
        when(cardMapper.selectOne(any())).thenReturn(card(800));
        var q = service.redeemQuote(2L, 5000, new BigDecimal("1000"));
        assertEquals(0, q.get("points"));
        assertEquals(0, ((BigDecimal) q.get("amount")).signum());
        assertEquals(800, q.get("balance"));
    }

    @Test
    void quoteBalanceExactlyOneYuan() {
        // 余额刚好 1000 = 1 元 → 可抵 1 元
        when(cardMapper.selectOne(any())).thenReturn(card(1000));
        var q = service.redeemQuote(2L, 1000, new BigDecimal("1000"));
        assertEquals(1000, q.get("points"));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) q.get("amount")));
    }

    @Test
    void quoteExcessRequestCappedByBalance() {
        // 积分超额抵扣：请求 999999 超出余额 4000 → 按余额 4000 抵 4 元
        when(cardMapper.selectOne(any())).thenReturn(card(4000));
        var q = service.redeemQuote(2L, 999999, new BigDecimal("1000"));
        assertEquals(4000, q.get("points"));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) q.get("amount")));
    }

    @Test
    void quoteCappedByPercentLimit() {
        // 单笔 10% 上限：订单 200 元上限 20 元（=20000 积分）；请求 30000 → 截断为 20000 积分 = 20 元
        when(cardMapper.selectOne(any())).thenReturn(card(500000));
        var q = service.redeemQuote(2L, 30000, new BigDecimal("200"));
        assertEquals(20000, q.get("points"));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) q.get("amount")));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) q.get("maxAmount")));
        assertEquals(20000, q.get("maxPoints"), "本单可抵最大积分 = 上限金额 × 1000");
    }

    @Test
    void quoteCapFloorsToWholeYuan() {
        // 上限非整元向下取整：订单 105 元 10% = 10.5 元 → 上限 10 元（10000 积分）
        when(cardMapper.selectOne(any())).thenReturn(card(500000));
        var q = service.redeemQuote(2L, 500000, new BigDecimal("105"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) q.get("maxAmount")), "上限向下取整到整元");
        assertEquals(10000, q.get("maxPoints"));
        assertEquals(10000, q.get("points"));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) q.get("amount")));
    }

    @Test
    void quoteZeroRequest() {
        when(cardMapper.selectOne(any())).thenReturn(card(5000));
        var q = service.redeemQuote(2L, 0, new BigDecimal("1000"));
        assertEquals(0, q.get("points"));
        assertEquals(0, ((BigDecimal) q.get("amount")).signum());
    }

    // ===== 扣减 redeemPoints =====

    @Test
    void redeemDeductsPointsAndWritesLog() {
        MemberCard c = card(5000);
        when(cardMapper.selectOne(any())).thenReturn(c);
        BigDecimal amount = service.redeemPoints(2L, 2000, new BigDecimal("1000"), "ORD-1");
        assertEquals(0, new BigDecimal("2").compareTo(amount), "2000 积分抵 2 元");
        assertEquals(3000, c.getPoints(), "积分应从 5000 扣至 3000");
        verify(cardMapper).updateById(c);
        verify(logMapper).insert(argThat((MemberPointLog l) -> MemberRule.CHANGE_REDEEM.equals(l.getChangeType())
                && l.getChangeVal() == -2000 && "ORD-1".equals(l.getRefOrderNo())));
    }

    @Test
    void redeemInsufficientPointsReturnsZeroWithoutDeduct() {
        // 积分不足抵扣（< 1000 分）：返回 0、不扣积分、不写流水
        MemberCard c = card(500);
        when(cardMapper.selectOne(any())).thenReturn(c);
        BigDecimal amount = service.redeemPoints(2L, 500, new BigDecimal("1000"), "ORD-2");
        assertEquals(0, amount.signum(), "不足 1 元零头不参与抵扣");
        assertEquals(500, c.getPoints(), "积分不应变化");
        verify(cardMapper, never()).updateById(any(MemberCard.class));
        verify(logMapper, never()).insert(any(MemberPointLog.class));
    }

    @Test
    void redeemOddRemainderFloorsToWholeYuan() {
        // 1500 积分：零头 500 不抵，仅扣 1000 积分抵 1 元
        MemberCard c = card(1500);
        when(cardMapper.selectOne(any())).thenReturn(c);
        BigDecimal amount = service.redeemPoints(2L, 1500, new BigDecimal("1000"), "ORD-3");
        assertEquals(0, BigDecimal.ONE.compareTo(amount));
        assertEquals(500, c.getPoints(), "仅扣 1000 积分，500 零头保留");
        verify(logMapper).insert(argThat((MemberPointLog l) -> l.getChangeVal() == -1000));
    }

    @Test
    void redeemExcessRequestUsesBalanceOnly() {
        // 超额请求按余额截断：余额 3000，请求 999999 → 扣 3000 抵 3 元
        MemberCard c = card(3000);
        when(cardMapper.selectOne(any())).thenReturn(c);
        BigDecimal amount = service.redeemPoints(2L, 999999, new BigDecimal("1000"), "ORD-4");
        assertEquals(0, new BigDecimal("3").compareTo(amount));
        assertEquals(0, c.getPoints());
    }

    // ===== 返还 refundPoints =====

    @Test
    void refundReturnsPointsOnce() {
        when(cardMapper.selectOne(any())).thenReturn(card(4000));
        MemberPointLog redeem = new MemberPointLog();
        redeem.setChangeType(MemberRule.CHANGE_REDEEM);
        redeem.setChangeVal(-1000);
        redeem.setRefOrderNo("ORD-1");
        when(logMapper.selectList(any())).thenReturn(List.of(redeem));
        when(logMapper.selectCount(any())).thenReturn(0L);

        int refunded = service.refundPoints(2L, "ORD-1");
        assertEquals(1000, refunded);
        verify(logMapper).insert(argThat((MemberPointLog l) -> MemberRule.CHANGE_REFUND.equals(l.getChangeType())
                && l.getChangeVal() == 1000));
    }

    @Test
    void refundIdempotentWhenAlreadyRefunded() {
        when(logMapper.selectCount(any())).thenReturn(1L);
        assertEquals(0, service.refundPoints(2L, "ORD-1"), "已返还订单不应重复返还");
        verify(logMapper, never()).insert(any(MemberPointLog.class));
    }

    @Test
    void refundZeroWhenNoRedeemLog() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.refundPoints(2L, "ORD-X"));
        verify(logMapper, never()).insert(any(MemberPointLog.class));
    }

    // ===== 退款扣回 reverseEarn =====

    @Test
    void reverseEarnDeductsAndWritesNegativeLog() {
        when(cardMapper.selectOne(any())).thenReturn(card(500));
        MemberPointLog earn = new MemberPointLog();
        earn.setChangeType("earn_consume");
        earn.setChangeVal(100);
        earn.setRefOrderNo("ORD-1");
        when(logMapper.selectList(any())).thenReturn(List.of(earn));
        when(logMapper.selectCount(any())).thenReturn(0L);

        int back = service.reverseEarn(2L, "ORD-1");
        assertEquals(100, back);
        verify(cardMapper).updateById(argThat((MemberCard c) -> c.getPoints() == 400));
        verify(logMapper).insert(argThat((MemberPointLog l) ->
                MemberRule.CHANGE_EARN_REVERSAL.equals(l.getChangeType()) && l.getChangeVal() == -100));
    }

    @Test
    void reverseEarnIdempotent() {
        when(logMapper.selectCount(any())).thenReturn(1L);
        assertEquals(0, service.reverseEarn(2L, "ORD-1"));
        verify(logMapper, never()).insert(any(MemberPointLog.class));
    }

    @Test
    void reverseEarnZeroWhenNoEarnLog() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.reverseEarn(2L, "ORD-X"));
        verify(logMapper, never()).insert(any(MemberPointLog.class));
    }

    @Test
    void reverseEarnClampsAtZeroBalance() {
        when(cardMapper.selectOne(any())).thenReturn(card(30));
        MemberPointLog earn = new MemberPointLog();
        earn.setChangeType("earn_consume");
        earn.setChangeVal(100);
        when(logMapper.selectList(any())).thenReturn(List.of(earn));
        when(logMapper.selectCount(any())).thenReturn(0L);

        assertEquals(100, service.reverseEarn(2L, "ORD-1"));
        verify(cardMapper).updateById(argThat((MemberCard c) -> c.getPoints() == 0));
    }

    /** 供 argThat 类型推断使用的辅助（MemberLevel） */
    @SuppressWarnings("unused")
    private static MemberLevel level(Long id, int min) {
        MemberLevel l = new MemberLevel();
        l.setLevelId(id); l.setMinGrowth(min); l.setDiscountRate(BigDecimal.ONE);
        return l;
    }
}

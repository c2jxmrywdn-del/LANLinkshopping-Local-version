package com.lanlink.shopping.module.membership.service;

import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 会员系统单测：建卡 / 等级判定 / 积分累计 / 等级折扣率
 */
class MembershipServiceTest {

    private MemberCardMapper cardMapper;
    private MemberLevelMapper levelMapper;
    private MemberPointLogMapper logMapper;
    private MembershipService service;

    private MemberLevel base() {
        MemberLevel l = new MemberLevel();
        l.setLevelId(1L); l.setLevelName("普通会员"); l.setMinGrowth(0);
        l.setDiscountRate(new BigDecimal("1.000")); l.setSort(1);
        return l;
    }

    @BeforeEach
    void setUp() {
        cardMapper = mock(MemberCardMapper.class);
        levelMapper = mock(MemberLevelMapper.class);
        logMapper = mock(MemberPointLogMapper.class);
        service = new MembershipService(cardMapper, levelMapper, logMapper);
        when(levelMapper.selectOne(any())).thenReturn(base());
    }

    @Test
    void createCardWhenMissing() {
        when(cardMapper.selectOne(any())).thenReturn(null);
        MemberCard card = service.getOrCreateCard(2L);
        assertNotNull(card);
        assertEquals(2L, card.getUserId());
        assertEquals(1L, card.getLevelId());
        assertEquals(0, card.getGrowth());
    }

    @Test
    void levelMatchedByGrowth() {
        // 真实库按 min_growth DESC 返回，这里按同序构造
        List<MemberLevel> levels = List.of(
                level(3L, "黄金会员", 5000),
                level(2L, "白银会员", 1000),
                level(1L, "普通会员", 0));
        when(levelMapper.selectList(any())).thenReturn(levels);
        assertEquals(3L, service.levelForGrowth(6000), "成长值6000 → 黄金");
        assertEquals(2L, service.levelForGrowth(1500), "成长值1500 → 白银");
        assertEquals(1L, service.levelForGrowth(300), "成长值300 → 普通");
    }

    @Test
    void earnGrowthAddsPointsAndLog() {
        when(cardMapper.selectOne(any())).thenReturn(null); // 自动建卡
        when(levelMapper.selectList(any())).thenReturn(List.of(level(1L, "普通会员", 0)));

        service.earnGrowth(2L, new BigDecimal("1250"), "TEST-ORDER-1", 1);

        verify(cardMapper, times(1)).insert(any(MemberCard.class));      // 建卡
        verify(cardMapper, times(1)).updateById(any(MemberCard.class));  // 更新成长值/积分
        verify(logMapper, times(1)).insert(any(com.lanlink.shopping.module.membership.entity.MemberPointLog.class)); // 积分流水
    }

    @Test
    void vipEarnsDoublePoints() {
        when(cardMapper.selectOne(any())).thenReturn(null);
        when(levelMapper.selectList(any())).thenReturn(List.of(level(1L, "普通会员", 0)));
        // VIP multiplier=2：1250 元 → 2500 积分
        service.earnGrowth(2L, new BigDecimal("1250"), "TEST-ORDER-2", 2);
        verify(logMapper, times(1)).insert(argThat((com.lanlink.shopping.module.membership.entity.MemberPointLog l) ->
                l.getChangeVal() == 2500 && l.getRemark().contains("VIP双倍")));
    }

    @Test
    void levelDiscountRateDefaultOne() {
        MemberCard card = new MemberCard();
        card.setCardId(1L); card.setUserId(2L); card.setLevelId(1L); card.setGrowth(0); card.setPoints(0);
        when(cardMapper.selectOne(any())).thenReturn(card);
        when(levelMapper.selectById(1L)).thenReturn(level(1L, "普通会员", 0));
        assertEquals(0, BigDecimal.ONE.compareTo(service.levelDiscountRate(2L)));
    }

    private MemberLevel level(Long id, String name, int min) {
        MemberLevel l = new MemberLevel();
        l.setLevelId(id); l.setLevelName(name); l.setMinGrowth(min);
        l.setDiscountRate(new BigDecimal("1.000")); l.setSort((int) (long) id);
        return l;
    }
}

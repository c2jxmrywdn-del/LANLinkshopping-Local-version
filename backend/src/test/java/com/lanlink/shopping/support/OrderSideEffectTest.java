package com.lanlink.shopping.support;

import com.lanlink.shopping.integration.event.OrderNotificationEvent;
import com.lanlink.shopping.integration.listener.OrderNotificationListener;
import com.lanlink.shopping.module.membership.entity.MemberCard;
import com.lanlink.shopping.module.membership.entity.MemberPointLog;
import com.lanlink.shopping.module.membership.mapper.MemberCardMapper;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.MemberPointLogMapper;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.membership.service.SubscriptionService;
import com.lanlink.shopping.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 订单副作用：通知走 AFTER_COMMIT 监听器；会员积分按 orderNo 幂等。
 */
class OrderSideEffectTest {

    private MessageService messageService;
    private MemberCardMapper cardMapper;
    private MemberLevelMapper levelMapper;
    private MemberPointLogMapper pointLogMapper;
    private MembershipService membershipService;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 TableInfo 缓存，earnGrowth 原子累加（lambdaUpdate.set）的列名解析需先初始化实体元信息
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                MemberCard.class);
        messageService = mock(MessageService.class);
        cardMapper = mock(MemberCardMapper.class);
        levelMapper = mock(MemberLevelMapper.class);
        pointLogMapper = mock(MemberPointLogMapper.class);
        membershipService = new MembershipService(cardMapper, levelMapper, pointLogMapper,
                mock(SubscriptionService.class));
    }

    @Test
    void notificationListenerSendsMessage() {
        OrderNotificationListener listener = new OrderNotificationListener(messageService);
        listener.onNotification(new OrderNotificationEvent(1L, "支付成功", "订单已支付", "ORD-001"));
        verify(messageService).send(1L, "order", "支付成功", "订单已支付", "ORD-001");
    }

    @Test
    void notificationFailureNeverEscalates() {
        OrderNotificationListener listener = new OrderNotificationListener(messageService);
        doThrow(new RuntimeException("mq down")).when(messageService).send(any(), any(), any(), any(), any());
        assertDoesNotThrow(() -> listener.onNotification(
                new OrderNotificationEvent(1L, "支付成功", "订单已支付", "ORD-002")));
    }

    @Test
    void earnGrowthSkipsDuplicateOrder() {
        when(pointLogMapper.selectCount(any())).thenReturn(1L);

        membershipService.earnGrowth(1L, new BigDecimal("100"), "ORD-003", 1);

        verify(cardMapper, never()).updateById(any(MemberCard.class));
        verify(pointLogMapper, never()).insert(any(MemberPointLog.class));
    }

    @Test
    void earnGrowthProcessesFirstTimeOrder() {
        when(pointLogMapper.selectCount(any())).thenReturn(0L);
        MemberCard card = new MemberCard();
        card.setCardId(1L);
        card.setUserId(1L);
        // 已处于基础等级：无订阅时 levelForGrowth 回落 1L，等级无变化，不触发第二次 update
        card.setLevelId(1L);
        card.setGrowth(0);
        card.setPoints(0);
        when(cardMapper.selectOne(any())).thenReturn(card);
        when(levelMapper.selectOne(any())).thenReturn(null);

        membershipService.earnGrowth(1L, new BigDecimal("100"), "ORD-004", 1);

        verify(cardMapper).update(isNull(), any());
        verify(pointLogMapper).insert(any(MemberPointLog.class));
    }
}

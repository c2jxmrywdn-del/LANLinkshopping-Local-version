package com.lanlink.shopping.module.membership.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.membership.entity.SubscriptionOrder;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.mapper.SubscriptionOrderMapper;
import com.lanlink.shopping.module.membership.mapper.SubscriptionPlanMapper;
import com.lanlink.shopping.module.membership.mapper.UserSubscriptionMapper;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.service.SequenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订阅系统单测（P1 闭环）：purchase 两段式（创建待支付订阅单→渠道分发）、
 * 支付回调驱动生效/顺延（续费=原到期日起顺延，失效续订=新起算，apply 状态机幂等）、
 * D3 cancel 语义（到期失效+撤销自动续费+保留历史）、订阅订单分页。
 */
class SubscriptionServiceTest {

    private SubscriptionPlanMapper planMapper;
    private UserSubscriptionMapper subMapper;
    private SubscriptionOrderMapper orderMapper;
    private AuditService auditService;
    private SequenceService sequenceService;
    private MessageService messageService;
    private PaymentService paymentService;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        // 纯 mock 环境无 TableInfo 缓存，条件更新（lambdaUpdate.set）的列名解析需先初始化实体元信息
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserSubscription.class);
        planMapper = mock(SubscriptionPlanMapper.class);
        subMapper = mock(UserSubscriptionMapper.class);
        orderMapper = mock(SubscriptionOrderMapper.class);
        auditService = mock(AuditService.class);
        sequenceService = mock(SequenceService.class);
        messageService = mock(MessageService.class);
        paymentService = mock(PaymentService.class);
        service = new SubscriptionService(planMapper, subMapper, orderMapper, auditService,
                sequenceService, messageService, paymentService);
        when(sequenceService.next(anyString())).thenReturn(1L);
        when(subMapper.update(any(), any())).thenReturn(1);
    }

    private SubscriptionPlan yearlyPlan() {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanId(4L);
        p.setPlanName("年度订阅");
        p.setPlanCode("yearly");
        // 价格整数元口径：8888 = ¥8,888（禁止按分解读）
        p.setPriceCents(8888);
        p.setDurationDays(365);
        p.setAutoRenew(0);
        p.setEnabled(1);
        return p;
    }

    private UserSubscription activeSub(long subId, long userId, long planId, LocalDateTime endTime) {
        UserSubscription s = new UserSubscription();
        s.setSubId(subId);
        s.setUserId(userId);
        s.setPlanId(planId);
        s.setStartTime(endTime.minusDays(365));
        s.setEndTime(endTime);
        s.setStatus("active");
        s.setAutoRenew(1);
        return s;
    }

    private SubscriptionOrder subOrder(Long renewSubId, int retryCount) {
        SubscriptionOrder o = new SubscriptionOrder();
        o.setOrderNo("SUB2026110600000001");
        o.setUserId(7L);
        o.setPlanId(4L);
        o.setAmountCents(8888);
        o.setDurationDays(365);
        o.setPayStatus(0);
        o.setApplyStatus(0);
        o.setRenewSubId(renewSubId);
        o.setRetryCount(retryCount);
        return o;
    }

    // ===== P1-2 purchase 两段式 =====

    @Test
    void purchaseCreatesPendingOrderThenDispatchesChannel() {
        when(planMapper.selectById(4L)).thenReturn(yearlyPlan());
        PayCreateVO vo = new PayCreateVO();
        vo.setOrderNo("SUB2026110600000001");
        vo.setChannel("mock");
        vo.setMock(true);
        when(paymentService.create(anyString(), eq(7L), eq("mock"))).thenReturn(vo);

        PayCreateVO out = service.purchase(7L, 4L, "mock", null);

        assertEquals("SUB2026110600000001", out.getOrderNo());
        ArgumentCaptor<SubscriptionOrder> cap = ArgumentCaptor.forClass(SubscriptionOrder.class);
        verify(orderMapper).insert(cap.capture());
        SubscriptionOrder inserted = cap.getValue();
        assertEquals("SUB", inserted.getOrderNo().substring(0, 3), "订阅单号必须以 SUB 命名空间前缀开头");
        assertEquals(7L, inserted.getUserId());
        assertEquals(8888, inserted.getAmountCents(), "金额快照为整数元（8888=¥8,888）");
        assertEquals(365, inserted.getDurationDays());
        assertEquals(0, inserted.getPayStatus());
        assertEquals(0, inserted.getApplyStatus());
        // 两段式：下单本身不得直接生效订阅（生效由支付回调驱动）
        verify(subMapper, never()).insert(any(UserSubscription.class));
        verify(subMapper, never()).updateById(any(UserSubscription.class));
    }

    @Test
    void purchaseRejectsDisabledPlan() {
        SubscriptionPlan p = yearlyPlan();
        p.setEnabled(0);
        when(planMapper.selectById(4L)).thenReturn(p);
        assertThrows(BusinessException.class, () -> service.purchase(7L, 4L, "mock", null));
        verify(orderMapper, never()).insert(any(SubscriptionOrder.class));
    }

    // ===== P1-3 支付回调驱动生效/顺延 =====

    @Test
    void paidOrderWithoutSubscriptionActivatesNewSub() {
        SubscriptionOrder o = subOrder(null, 0);
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);
        when(orderMapper.markAppliedAtomic("SUB2026110600000001")).thenReturn(1);
        when(subMapper.selectList(any())).thenReturn(List.of()); // 无生效订阅、无宽限期内记录

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        ArgumentCaptor<UserSubscription> cap = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subMapper).insert(cap.capture());
        UserSubscription sub = cap.getValue();
        assertEquals("active", sub.getStatus());
        // 新起算：start=now，end=start+duration_days（365 天）
        assertTrue(sub.getEndTime().isAfter(sub.getStartTime().plusDays(364)));
        assertTrue(sub.getEndTime().isBefore(sub.getStartTime().plusDays(366)));
        verify(messageService).send(eq(7L), eq("order"), eq("订阅开通成功"), anyString(), eq("SUB2026110600000001"));
        verify(orderMapper).markAppliedAtomic("SUB2026110600000001");
    }

    @Test
    void paidOrderRenewalExtendsFromCurrentEndTime() {
        SubscriptionOrder o = subOrder(null, 0);
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);
        when(orderMapper.markAppliedAtomic("SUB2026110600000001")).thenReturn(1);
        LocalDateTime end = LocalDateTime.now().plusDays(10);
        when(subMapper.selectList(any())).thenReturn(List.of(activeSub(11L, 7L, 4L, end))); // 生效中订阅

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        ArgumentCaptor<UserSubscription> cap = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subMapper).updateById(cap.capture());
        UserSubscription sub = cap.getValue();
        assertEquals(11L, sub.getSubId());
        // 续费 = 原到期日起顺延 duration_days
        assertEquals(end.plusDays(365), sub.getEndTime());
        assertEquals("active", sub.getStatus());
        verify(subMapper, never()).insert(any(UserSubscription.class));
        verify(messageService).send(eq(7L), eq("order"), eq("订阅续费成功"), anyString(), anyString());
    }

    @Test
    void paidOrderWithinGraceExtendsFromOriginalEndTime() {
        SubscriptionOrder o = subOrder(null, 0);
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);
        when(orderMapper.markAppliedAtomic("SUB2026110600000001")).thenReturn(1);
        // 无生效订阅；最近一条订阅 2 天前到期（宽限期 3 天内）→ 从原到期日起顺延（保持连续）
        when(subMapper.selectList(any()))
                .thenReturn(List.of())                                            // findActive：空
                .thenReturn(List.of(activeSub(11L, 7L, 4L, LocalDateTime.now().minusDays(2))));

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        ArgumentCaptor<UserSubscription> cap = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subMapper).updateById(cap.capture());
        assertTrue(cap.getValue().getEndTime().isAfter(LocalDateTime.now().plusDays(360)), "宽限期内续费应自原到期日顺延为生效");
        assertEquals("active", cap.getValue().getStatus());
    }

    @Test
    void paidOrderAfterGraceStartsFreshSubscription() {
        SubscriptionOrder o = subOrder(null, 0);
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);
        when(orderMapper.markAppliedAtomic("SUB2026110600000001")).thenReturn(1);
        // 无生效订阅；宽限期查询为空（最近订阅已超出宽限期窗口，窗口过滤在 SQL 侧）→ 失效续订新起算
        when(subMapper.selectList(any())).thenReturn(List.of());

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        ArgumentCaptor<UserSubscription> cap = ArgumentCaptor.forClass(UserSubscription.class);
        verify(subMapper).insert(cap.capture());
        assertEquals("active", cap.getValue().getStatus());
        assertTrue(cap.getValue().getStartTime().isAfter(LocalDateTime.now().minusMinutes(1)), "新起算：start=now");
        verify(subMapper, never()).updateById(any(UserSubscription.class));
    }

    @Test
    void paidOrderIsIdempotentViaApplyGate() {
        SubscriptionOrder o = subOrder(null, 0);
        o.setApplyStatus(1); // 已生效过
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        // 回调重放：apply 闸关闭，不重复顺延/新建
        verify(subMapper, never()).insert(any(UserSubscription.class));
        verify(subMapper, never()).updateById(any(UserSubscription.class));
        verify(orderMapper, never()).markAppliedAtomic(anyString());
    }

    @Test
    void paidOrderLosesRaceOnApplyGate() {
        SubscriptionOrder o = subOrder(null, 0);
        when(orderMapper.selectById("SUB2026110600000001")).thenReturn(o);
        when(orderMapper.markAppliedAtomic("SUB2026110600000001")).thenReturn(0); // 并发被抢占

        service.onSubscriptionOrderPaid("SUB2026110600000001");

        verify(subMapper, never()).insert(any(UserSubscription.class));
        verify(subMapper, never()).updateById(any(UserSubscription.class));
    }

    // ===== P1-5/D3 cancel 语义 =====

    @Test
    void cancelRevokesAutoRenewButKeepsSubscriptionActiveUntilExpiry() {
        LocalDateTime end = LocalDateTime.now().plusDays(10);
        // currentActive = expireOld 的 stale 查询（空）→ findActive（生效订阅）
        when(subMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of(activeSub(11L, 7L, 4L, end)));

        UserSubscription out = service.cancel(7L, null);

        // 权益保留至到期日：status 仍为 active（到期失效由惰性过期完成），历史记录不删除
        assertEquals("active", out.getStatus());
        assertEquals(end, out.getEndTime());
        assertEquals(0, out.getAutoRenew().intValue(), "取消 = 撤销自动续费");
        verify(subMapper).update(isNull(), any()); // auto_renew 条件更新（状态机）
        verify(orderMapper).cancelPendingByRenewSub(11L); // 名下待支付续费单一并取消
        verify(subMapper, never()).updateById(any(UserSubscription.class)); // 不再立即翻转状态
    }

    @Test
    void cancelWithoutActiveSubscriptionReturnsNull() {
        when(subMapper.selectList(any())).thenReturn(List.of());
        assertNull(service.cancel(7L, null));
    }

    // ===== P1-4 自动续费扣款 =====

    @Test
    void autoRenewOnceChargesViaMockChannelAndConfirms() {
        when(planMapper.selectById(4L)).thenReturn(yearlyPlan());
        when(orderMapper.selectList(any())).thenReturn(List.of()); // 无待支付续费单 → 新建
        PayCreateVO vo = new PayCreateVO();
        vo.setOrderNo("SUB2026110600000001");
        vo.setChannel("mock");
        vo.setMock(true);
        when(paymentService.create(anyString(), eq(7L), eq("mock"))).thenReturn(vo);
        // mockConfirm 为外部协作 mock：以 selectById 连续返回模拟「确认后订单已支付」
        SubscriptionOrder created = subOrder(null, 0);
        SubscriptionOrder paid = subOrder(null, 0);
        paid.setPayStatus(1);
        when(orderMapper.selectById(anyString())).thenReturn(created, paid);
        UserSubscription sub = activeSub(11L, 7L, 4L, LocalDateTime.now().plusHours(2));

        assertTrue(service.autoRenewOnce(sub));
        verify(paymentService).create(anyString(), eq(7L), eq("mock")); // 扣款走 mock/沙箱渠道
        verify(paymentService).mockConfirm(anyString(), eq(7L));
    }

    @Test
    void autoRenewOnceCountsRetryOnChargeFailure() {
        when(planMapper.selectById(4L)).thenReturn(yearlyPlan());
        when(orderMapper.selectList(any())).thenReturn(List.of(subOrder(11L, 1))); // 已有待支付续费单
        when(paymentService.create(anyString(), eq(7L), eq("mock")))
                .thenThrow(new BusinessException("模拟支付未启用"));
        UserSubscription sub = activeSub(11L, 7L, 4L, LocalDateTime.now().plusHours(2));

        assertFalse(service.autoRenewOnce(sub));
        verify(orderMapper).incrementRetry("SUB2026110600000001");
    }

    @Test
    void failRenewalCancelsPendingOrderAndNotifiesDowngrade() {
        SubscriptionOrder pending = subOrder(11L, 3);
        UserSubscription sub = activeSub(11L, 7L, 4L, LocalDateTime.now().minusDays(1));
        when(messageService.countByRelatedNo(eq(7L), anyString())).thenReturn(0L);

        service.failRenewal(sub, pending);

        verify(orderMapper).markCancelledAtomic("SUB2026110600000001"); // 待支付续费单取消（pay_status 0→2）
        verify(subMapper, times(2)).update(isNull(), any());            // auto_renew→0 + 到期置 cancelled（条件更新）
        verify(messageService).send(eq(7L), eq("order"), eq("自动续费未成功"), anyString(), anyString());
    }

    // ===== P1-5 订阅订单分页 =====

    @Test
    void ordersReturnsPagedView() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<SubscriptionOrder> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        page.setRecords(List.of(subOrder(null, 0)));
        when(orderMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> out = service.orders(7L, 1, 10);

        assertEquals(1L, ((Number) out.get("total")).longValue());
        assertEquals(1, ((List<?>) out.get("records")).size());
    }

    @Test
    void ordersRejectsAnonymousUser() {
        assertThrows(BusinessException.class, () -> service.orders(null, 1, 10));
    }
}

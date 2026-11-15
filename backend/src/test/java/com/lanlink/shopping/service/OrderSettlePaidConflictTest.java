package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.risk.service.RiskEventService;
import com.lanlink.shopping.payment.conflict.dto.SettlePaidResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B：{@link OrderService#settlePaid} 三态裁决回归。
 *
 * <p>「改前失败」证据：旧实现（boolean）在 markPaidAtomic 命中 0 行时直接 {@code return false}
 * （旧 OrderService:718），调用方无法区分"重复回调"和"订单已关闭却收到付款"，于是各渠道入口
 * 固定记 success 并 ACK → 静默吞掉资金事件、无挂账。本测试锁定修复后的契约：
 * 已关单 → {@link SettlePaidResult.Outcome#ORDER_CONFLICT}，且<b>不</b>发布支付成功事件、
 * <b>不</b>回填渠道流水；重复回调 → ALREADY_PAID 且可区分同单第二笔流水（secondCharge）。
 */
class OrderSettlePaidConflictTest {

    private OrderMapper orderMapper;
    private UserMapper userMapper;
    private IdentityService identityService;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        userMapper = mock(UserMapper.class);
        identityService = mock(IdentityService.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new OrderService(
                mock(CartMapper.class), mock(ProductMapper.class), orderMapper,
                mock(OrderItemMapper.class), userMapper, mock(MessageService.class),
                identityService, auditService, mock(PricingFacade.class), eventPublisher,
                mock(MembershipService.class), mock(CreditTermService.class),
                mock(IdempotentService.class), mock(TamperGuardService.class),
                mock(CampaignService.class), mock(RiskEventService.class));
    }

    private static Order order(int payStatus, int orderStatus) {
        Order o = new Order();
        o.setOrderNo("ORD1");
        o.setUserId(9L);
        o.setPayStatus(payStatus);
        o.setOrderStatus(orderStatus);
        o.setTotalAmount(new BigDecimal("88.80"));
        o.setPayChannel("wechat");
        return o;
    }

    // ===== 核心：关单后收到付款 = ORDER_CONFLICT（旧实现是静默 false） =====

    @Test
    void closedOrderReceivesChannelPayment_returnsOrderConflictWithoutPaidSideEffects() {
        Order closed = order(0, 3);
        when(orderMapper.selectById("ORD1")).thenReturn(closed);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);

        SettlePaidResult result = service.settlePaid("ORD1", "wechat", "TXN1");

        assertEquals(SettlePaidResult.Outcome.ORDER_CONFLICT, result.outcome(),
                "订单已关闭却收到付款必须返回 ORDER_CONFLICT，不能像旧实现那样返回 false");
        assertEquals(3, result.order().getOrderStatus());
        assertEquals("TXN1", result.transactionId());
        assertFalse(result.secondCharge());
        // 冲突不是支付成功：不得发订单支付成功事件/积分事件，也不得回填渠道流水
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(eventPublisher, never()).publishEvent(any(org.springframework.context.ApplicationEvent.class));
        verify(orderMapper, never()).bindPaidChannel(anyString(), anyString(), anyString());
    }

    @Test
    void settlePaidMissingOrderStillThrows() {
        when(orderMapper.selectById("MISS")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.settlePaid("MISS", "wechat", "T"));
    }

    // ===== 正常结算 / 重复回调 =====

    @Test
    void firstSettlementIsSettledAndPublishesPaidEvents() {
        Order open = order(0, 0);
        Order paid = order(1, 0);
        when(orderMapper.selectById("ORD1")).thenReturn(open, paid);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(1);
        when(userMapper.selectById(9L)).thenReturn(null);

        SettlePaidResult result = service.settlePaid("ORD1", "wechat", "TXN1");

        assertEquals(SettlePaidResult.Outcome.SETTLED, result.outcome());
        assertTrue(result.settled());
        verify(orderMapper).bindPaidChannel("ORD1", "wechat", "TXN1");
        // ApplicationEventPublisher 有两个重载（Object / ApplicationEvent），分别验证：通知事件 + OrderPaidEvent
        verify(eventPublisher).publishEvent(any(Object.class));
        verify(eventPublisher).publishEvent(any(org.springframework.context.ApplicationEvent.class));
    }

    @Test
    void duplicateCallbackSameTxn_isIdempotentAckWithoutSecondCharge() {
        Order paid = order(1, 0);
        paid.setTransactionId("TXN1");
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);

        SettlePaidResult result = service.settlePaid("ORD1", "wechat", "TXN1");

        assertEquals(SettlePaidResult.Outcome.ALREADY_PAID, result.outcome());
        assertFalse(result.secondCharge(), "同一流水号重发是真重复回调");
        verify(eventPublisher, never()).publishEvent(any());
        verify(orderMapper, never()).bindPaidChannel(anyString(), anyString(), anyString());
    }

    @Test
    void duplicateCallbackDifferentTxn_flagsSecondCharge() {
        Order paid = order(1, 0);
        paid.setTransactionId("TXN1");
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);

        SettlePaidResult result = service.settlePaid("ORD1", "wechat", "TXN2");

        assertEquals(SettlePaidResult.Outcome.ALREADY_PAID, result.outcome());
        assertTrue(result.secondCharge(), "同单第二笔渠道流水必须标记，供门面挂 duplicate_txn");
    }

    @Test
    void duplicateCallbackWithMissingStoredTxn_backfillsChannelTxn() {
        Order paid = order(1, 0); // 历史数据 transaction_id 为空（旧实现丢流水号的场景）
        when(orderMapper.selectById("ORD1")).thenReturn(paid);
        when(orderMapper.markPaidAtomic("ORD1")).thenReturn(0);

        SettlePaidResult result = service.settlePaid("ORD1", "wechat", "TXN1");

        assertEquals(SettlePaidResult.Outcome.ALREADY_PAID, result.outcome());
        assertFalse(result.secondCharge());
        verify(orderMapper).bindPaidChannel("ORD1", "wechat", "TXN1");
    }
}

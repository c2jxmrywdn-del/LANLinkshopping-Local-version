package com.lanlink.shopping.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.UserIdentity;
import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.integration.listener.CampaignOrderListener;
import com.lanlink.shopping.integration.listener.MembershipOrderListener;
import com.lanlink.shopping.mapper.CreditBillMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.membership.constant.MemberRule;
import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.service.AuditService;
import com.lanlink.shopping.service.IdentityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单支付 MQ 流单测（out/storage-mq-plan.md 任务 B5，不起 broker）：
 * 拓扑声明（队列/绑定/DLQ 参数）、桥接发布器（AFTER_COMMIT 投递 + messageId 链路）、
 * 消费者接线（membership/campaign 双队列）、D2 双倍积分判据（multiplierOf 统一）、
 * 载荷真伪校验（security-hardening-plan.md P1-6.1，SG-61 伪造载荷四负例）。
 */
class OrderPaidMqFlowTest {

    // ==================== 拓扑声明（B5） ====================

    @Test
    void orderPaidTopologyDeclaresQueuesExchangesAndBindings() {
        Declarables declarables = new RabbitConfig(mock(Environment.class)).orderPaidTopology();
        List<Declarable> all = List.copyOf(declarables.getDeclarables());

        Queue membership = byName(all, RabbitConfig.ORDER_PAID_MEMBERSHIP_QUEUE, Queue.class);
        Queue campaign = byName(all, RabbitConfig.ORDER_PAID_CAMPAIGN_QUEUE, Queue.class);
        Queue dlq = byName(all, RabbitConfig.ORDER_PAID_DLQ, Queue.class);
        org.springframework.amqp.core.Exchange orderExchange = byName(all, RabbitConfig.ORDER_EXCHANGE,
                org.springframework.amqp.core.Exchange.class);
        org.springframework.amqp.core.Exchange dlxExchange = byName(all, RabbitConfig.DLX_EXCHANGE,
                org.springframework.amqp.core.Exchange.class);

        assertBinding(all, membership, RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY);
        assertBinding(all, campaign, RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY);
        assertBinding(all, dlq, RabbitConfig.DLX_EXCHANGE, RabbitConfig.ORDER_PAID_DLQ_ROUTING_KEY);
        assertEquals(RabbitConfig.ORDER_EXCHANGE, orderExchange.getName());
        assertEquals(RabbitConfig.DLX_EXCHANGE, dlxExchange.getName());
    }

    @Test
    void membershipAndCampaignQueuesAreBoundedAndDlqHasTtl() {
        Declarables declarables = new RabbitConfig(mock(Environment.class)).orderPaidTopology();
        List<Declarable> all = List.copyOf(declarables.getDeclarables());

        for (String name : List.of(RabbitConfig.ORDER_PAID_MEMBERSHIP_QUEUE, RabbitConfig.ORDER_PAID_CAMPAIGN_QUEUE)) {
            Queue q = byName(all, name, Queue.class);
            assertEquals("10000", String.valueOf(q.getArguments().get("x-max-length")), name + " 必须有界");
            assertEquals("reject-publish", String.valueOf(q.getArguments().get("x-overflow")),
                    name + " 积压必须反压发布方");
        }
        Queue dlq = byName(all, RabbitConfig.ORDER_PAID_DLQ, Queue.class);
        assertEquals("10000", String.valueOf(dlq.getArguments().get("x-max-length")));
        assertEquals("604800000", String.valueOf(dlq.getArguments().get("x-message-ttl")), "DLQ TTL 7 天");
        assertEquals("classic", String.valueOf(dlq.getArguments().get("x-queue-type")), "DLQ 保持 classic（basic.get 重放）");
    }

    private <T extends Declarable> T byName(List<Declarable> all, String name, Class<T> type) {
        return all.stream().filter(type::isInstance).map(type::cast)
                .filter(d -> name.equals(nameOf(d)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少声明: " + name));
    }

    private String nameOf(Declarable d) {
        if (d instanceof Queue q) {
            return q.getName();
        }
        if (d instanceof org.springframework.amqp.core.Exchange e) {
            return e.getName();
        }
        return "";
    }

    private void assertBinding(List<Declarable> all, Queue destination, String exchange, String routingKey) {
        List<Binding> bindings = all.stream().filter(Binding.class::isInstance).map(Binding.class::cast).toList();
        assertTrue(bindings.stream().anyMatch(b ->
                        destination.getName().equals(b.getDestination())
                                && exchange.equals(b.getExchange())
                                && routingKey.equals(b.getRoutingKey())
                                && b.isDestinationQueue()),
                "缺少绑定: " + destination.getName() + " → " + exchange + "/" + routingKey);
    }

    // ==================== 事件通道互斥（G12 防双通道双计） ====================

    @Test
    void eventListenersAreMutuallyExclusiveWithMqConsumers() {
        // P0 防线：原有 Spring 事件监听器必须在 havingValue="event" 时才生效，防双通道双计
        ConditionalOnProperty memCond = MembershipOrderListener.class.getAnnotation(ConditionalOnProperty.class);
        assertArrayEquals(new String[]{"app.mq.order-flow"}, memCond.name());
        assertEquals("event", memCond.havingValue());
        assertFalse(memCond.matchIfMissing(), "默认 missing 时不走 event 通道");

        ConditionalOnProperty campCond = CampaignOrderListener.class.getAnnotation(ConditionalOnProperty.class);
        assertArrayEquals(new String[]{"app.mq.order-flow"}, campCond.name());
        assertEquals("event", campCond.havingValue());
        assertFalse(campCond.matchIfMissing(), "默认 missing 时不走 event 通道");

        // 桥接发布器与消费者：默认 (mq) 模式注册
        ConditionalOnProperty bridgeCond = OrderPaidBridgeListener.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("mq", bridgeCond.havingValue());
        assertTrue(bridgeCond.matchIfMissing(), "默认走 mq 通道");
        ConditionalOnProperty memConsumerCond = MembershipOrderMqConsumer.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("mq", memConsumerCond.havingValue());
        assertTrue(memConsumerCond.matchIfMissing(), "默认走 mq 通道");
    }

    @Test
    void bridgeListenerConsumesOrderPaidEventAfterCommit() throws Exception {
        Method m = OrderPaidBridgeListener.class.getMethod("onOrderPaid", OrderPaidEvent.class);
        TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
        assertEquals(TransactionPhase.AFTER_COMMIT, tel.phase(), "事务提交后才投递（R1：防订单回滚但消息已发）");
        assertTrue(tel.fallbackExecution(), "非事务入口（pay）同步回退即时投递");
    }

    // ==================== 桥接发布器（B3） ====================

    @Test
    void bridgeListenerPublishesMessageWithOrderNoAsMessageId() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OrderOutboxMapper outbox = mock(OrderOutboxMapper.class);
        OrderPaidBridgeListener bridge = new OrderPaidBridgeListener(rabbitTemplate, outbox);

        bridge.onOrderPaid(new OrderPaidEvent(this, 7L, "ORD-MQ-001", new BigDecimal("66.00")));

        ArgumentCaptor<OrderPaidMessage> msgCap = ArgumentCaptor.forClass(OrderPaidMessage.class);
        ArgumentCaptor<MessagePostProcessor> procCap = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ORDER_PAID_ROUTING_KEY), msgCap.capture(), procCap.capture());
        assertEquals(7L, msgCap.getValue().userId());
        assertEquals("ORD-MQ-001", msgCap.getValue().orderNo());
        assertEquals(0, new BigDecimal("66.00").compareTo(msgCap.getValue().amount()));
        // messageId=orderNo：链路追踪 + DLQ 重放定位（G10/G11）
        Message message = procCap.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertEquals("ORD-MQ-001", message.getMessageProperties().getMessageId());
        verify(outbox).markSentByKey("ORD-MQ-001");
    }

    @Test
    void bridgeListenerSwallowsDeliveryFailureForOrderFlowIsolation() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OrderOutboxMapper outbox = mock(OrderOutboxMapper.class);
        doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
        OrderPaidBridgeListener bridge = new OrderPaidBridgeListener(rabbitTemplate, outbox);

        // 隔离原则：投递失败仅记日志不上抛（事件在 AFTER_COMMIT 抛出会穿透支付入口），留待 outbox 补投
        bridge.onOrderPaid(new OrderPaidEvent(this, 7L, "ORD-MQ-002", BigDecimal.ONE));
        verify(outbox, never()).markSentByKey(any());
    }

    // ==================== 消费者（B4，G9/G10 幂等重放） ====================

    @Test
    void consumersListenOnDeclaredQueues() throws Exception {
        RabbitListener membership = MembershipOrderMqConsumer.class
                .getMethod("onOrderPaid", OrderPaidMessage.class).getAnnotation(RabbitListener.class);
        assertArrayEquals(new String[]{RabbitConfig.ORDER_PAID_MEMBERSHIP_QUEUE}, membership.queues());
        RabbitListener campaign = CampaignOrderMqConsumer.class
                .getMethod("onOrderPaid", OrderPaidMessage.class).getAnnotation(RabbitListener.class);
        assertArrayEquals(new String[]{RabbitConfig.ORDER_PAID_CAMPAIGN_QUEUE}, campaign.queues());
    }

    @Test
    void membershipConsumerMapsVipMultiplier() {
        UserMapper userMapper = mock(UserMapper.class);
        MembershipService membershipService = mock(MembershipService.class);
        User user = new User();
        user.setRoleId(1L);
        when(userMapper.selectById(7L)).thenReturn(user);
        // D2 判据收敛后：倍数由 MembershipService.multiplierOf（身份 VIP ∪ 有效订阅）统一给出
        when(membershipService.multiplierOf(7L, 1L)).thenReturn(MemberRule.VIP_EARN_MULTIPLIER);
        MembershipOrderMqConsumer consumer =
                new MembershipOrderMqConsumer(membershipService, userMapper,
                        mock(OrderPaidMessageValidator.class));

        consumer.onOrderPaid(new OrderPaidMessage(7L, "ORD-MQ-004", new BigDecimal("66.00")));

        verify(membershipService).earnGrowth(7L, new BigDecimal("66.00"), "ORD-MQ-004", MemberRule.VIP_EARN_MULTIPLIER);
    }

    @Test
    void membershipConsumerUsesMultiplierOneForNonVip() {
        UserMapper userMapper = mock(UserMapper.class);
        MembershipService membershipService = mock(MembershipService.class);
        User user = new User();
        user.setRoleId(1L);
        when(userMapper.selectById(8L)).thenReturn(user);
        when(membershipService.multiplierOf(8L, 1L)).thenReturn(1);
        MembershipOrderMqConsumer consumer =
                new MembershipOrderMqConsumer(membershipService, userMapper,
                        mock(OrderPaidMessageValidator.class));

        consumer.onOrderPaid(new OrderPaidMessage(8L, "ORD-MQ-005", new BigDecimal("10.00")));

        verify(membershipService).earnGrowth(8L, new BigDecimal("10.00"), "ORD-MQ-005", 1);
    }

    @Test
    void membershipConsumerPropagatesFailureForRetryAndDlq() {
        MembershipService membershipService = mock(MembershipService.class);
        doThrow(new RuntimeException("db down")).when(membershipService)
                .earnGrowth(any(), any(), any(), anyInt());
        MembershipOrderMqConsumer consumer = new MembershipOrderMqConsumer(membershipService,
                mock(UserMapper.class), mock(OrderPaidMessageValidator.class));

        // 异常上抛（不吞）→ spring-retry 3 次 → default-requeue-rejected=false → DLX（G11 前置）
        assertThrows(RuntimeException.class, () -> consumer.onOrderPaid(
                new OrderPaidMessage(7L, "ORD-MQ-006", BigDecimal.ONE)));
    }

    @Test
    void campaignConsumerDelegatesAutoJoin() {
        CampaignService campaignService = mock(CampaignService.class);
        CampaignOrderMqConsumer consumer = new CampaignOrderMqConsumer(campaignService,
                mock(OrderPaidMessageValidator.class));

        consumer.onOrderPaid(new OrderPaidMessage(9L, "ORD-MQ-007", new BigDecimal("30.00")));

        // 幂等由 uk_cp_campaign_user 唯一键兜底（R2）：同一 userId 重投只会产生一条参与记录
        consumer.onOrderPaid(new OrderPaidMessage(9L, "ORD-MQ-007", new BigDecimal("30.00")));
        verify(campaignService, org.mockito.Mockito.times(2)).autoJoinPurchase(9L);
    }

    @Test
    void campaignConsumerPropagatesFailureForRetryAndDlq() {
        CampaignService campaignService = mock(CampaignService.class);
        doThrow(new RuntimeException("db down")).when(campaignService).autoJoinPurchase(any());
        CampaignOrderMqConsumer consumer = new CampaignOrderMqConsumer(campaignService,
                mock(OrderPaidMessageValidator.class));

        assertThrows(RuntimeException.class, () -> consumer.onOrderPaid(
                new OrderPaidMessage(9L, "ORD-MQ-008", BigDecimal.ONE)));
    }

    // ==================== 载荷真伪校验（security-hardening-plan.md P1-6.1，SG-61 四负例） ====================

    /** 真实反查逻辑（OrderMapper/CreditBillMapper mock + 真实 AuditService mock） */
    private OrderPaidMessageValidator realValidator(OrderMapper orderMapper, CreditBillMapper billMapper) {
        return new OrderPaidMessageValidator(orderMapper, billMapper, mock(AuditService.class));
    }

    private Order paidOrder(long userId, String amount) {
        Order o = new Order();
        o.setOrderNo("ORD-SG-001");
        o.setUserId(userId);
        o.setTotalAmount(new BigDecimal(amount));
        o.setPayStatus(1);
        return o;
    }

    @Test
    void validatorAcceptsLegitOrderPayload() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(orderMapper.selectByOrderNo("ORD-SG-001")).thenReturn(paidOrder(7L, "66.00"));
        OrderPaidMessageValidator validator = realValidator(orderMapper, mock(CreditBillMapper.class));

        // 真实订单 + 归属一致 + 金额一致 + 已支付 → 放行入账
        validator.requireLegitOrderPaid(new OrderPaidMessage(7L, "ORD-SG-001", new BigDecimal("66.00")));
    }

    @Test
    void validatorRejectsForgedOrderNo() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        CreditBillMapper billMapper = mock(CreditBillMapper.class);
        when(orderMapper.selectByOrderNo("FAKE-001")).thenReturn(null);
        when(billMapper.selectOne(any())).thenReturn(null);
        OrderPaidMessageValidator validator = realValidator(orderMapper, billMapper);

        // 伪造全新 orderNo：t_order/t_credit_bill 均命中不了 → 拒绝入账（防伪造铸积分）
        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, "FAKE-001", new BigDecimal("66.00"))));
    }

    @Test
    void validatorRejectsOwnerMismatch() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(orderMapper.selectByOrderNo("ORD-SG-001")).thenReturn(paidOrder(7L, "66.00"));
        OrderPaidMessageValidator validator = realValidator(orderMapper, mock(CreditBillMapper.class));

        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(8L, "ORD-SG-001", new BigDecimal("66.00"))));
    }

    @Test
    void validatorRejectsAmountMismatch() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        when(orderMapper.selectByOrderNo("ORD-SG-001")).thenReturn(paidOrder(7L, "66.00"));
        OrderPaidMessageValidator validator = realValidator(orderMapper, mock(CreditBillMapper.class));

        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, "ORD-SG-001", new BigDecimal("660.00"))));
    }

    @Test
    void validatorRejectsUnpaidOrder() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        Order unpaid = paidOrder(7L, "66.00");
        unpaid.setPayStatus(0);
        when(orderMapper.selectByOrderNo("ORD-SG-001")).thenReturn(unpaid);
        OrderPaidMessageValidator validator = realValidator(orderMapper, mock(CreditBillMapper.class));

        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, "ORD-SG-001", new BigDecimal("66.00"))));
    }

    @Test
    void validatorRejectsEmptyOrderNo() {
        OrderPaidMessageValidator validator = realValidator(mock(OrderMapper.class), mock(CreditBillMapper.class));
        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, " ", new BigDecimal("66.00"))));
    }

    @Test
    void validatorFallsBackToPaidCreditBillForSettlementOrders() {
        // 账期回退分支：物流结算单（CreditTermService.settleBill 发布）无 t_order 行，
        // 命中真实账单（同 userId + 金额一致 + PAID）→ 放行
        OrderMapper orderMapper = mock(OrderMapper.class);
        CreditBillMapper billMapper = mock(CreditBillMapper.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CreditBill.class);
        when(orderMapper.selectByOrderNo("SETTLE-001")).thenReturn(null);
        CreditBill bill = new CreditBill();
        bill.setOrderNo("SETTLE-001");
        bill.setUserId(7L);
        bill.setAmount(new BigDecimal("300.00"));
        bill.setStatus("PAID");
        when(billMapper.selectOne(any())).thenReturn(bill);
        OrderPaidMessageValidator validator = realValidator(orderMapper, billMapper);

        validator.requireLegitOrderPaid(new OrderPaidMessage(7L, "SETTLE-001", new BigDecimal("300.00")));

        // 账单金额不一致 → 拒绝
        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, "SETTLE-001", new BigDecimal("30.00"))));
    }

    @Test
    void validatorAuditTrailRecordsRejectionBeforeThrowing() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        AuditService auditService = mock(AuditService.class);
        when(orderMapper.selectByOrderNo("FAKE-002")).thenReturn(null);
        CreditBillMapper billMapper = mock(CreditBillMapper.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                CreditBill.class);
        when(billMapper.selectOne(any())).thenReturn(null);
        OrderPaidMessageValidator validator = new OrderPaidMessageValidator(orderMapper, billMapper, auditService);

        assertThrows(MessageIntegrityException.class, () -> validator.requireLegitOrderPaid(
                new OrderPaidMessage(7L, "FAKE-002", new BigDecimal("66.00"))));

        // 拒绝路径先审计（MQ_CONSUME_REJECTED，request=null 旁路写）再抛异常，DLQ 取证有迹可循
        verify(auditService).record(eq(7L), eq("MQ_CONSUME_REJECTED"), argThat(d -> d.contains("FAKE-002")), eq(null));
    }
}

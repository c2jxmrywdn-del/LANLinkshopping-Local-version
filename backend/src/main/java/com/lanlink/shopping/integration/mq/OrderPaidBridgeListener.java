package com.lanlink.shopping.integration.mq;

import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单支付事件 → RabbitMQ 桥接发布器（out/storage-mq-plan.md 任务 B3，OrderService 零改动）。
 *
 * 桥接方式：AFTER_COMMIT 监听既有 Spring 事件 OrderPaidEvent（发布点 OrderService.java:349/:379，未改动）：
 *  - settlePaid() 事务入口：事务提交后才投递，规避"订单回滚但 MQ 消息已发"的双写不一致（R1）；
 *  - pay() 非事务入口：fallbackExecution=true 同步回退即时投递，与既有
 *    MembershipOrderListener/CampaignOrderListener 的事务边界语义完全一致。
 *
 * messageId=orderNo：链路追踪 + DLQ 重放定位（G10/G11）。
 *
 * 隔离原则：投递失败仅记 ERROR 日志、不上抛——事件在 AFTER_COMMIT 阶段抛出会穿透到支付入口，
 * 造成"订单已支付但接口报错"；与既有事件监听器"营销副作用不得影响订单主流程"的隔离纪律一致。
 * broker 不可用导致的丢消息由 ERROR 日志暴露，P2 可升级 outbox 中继（plan §2.2-B3）。
 *
 * 开关：app.mq.order-flow=event 时本类不注册，通道整体回退 Spring 事件（G12）。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class OrderPaidBridgeListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidBridgeListener.class);

    private final RabbitTemplate rabbitTemplate;
    private final OrderOutboxMapper outbox;

    public OrderPaidBridgeListener(RabbitTemplate rabbitTemplate, OrderOutboxMapper outbox) {
        this.rabbitTemplate = rabbitTemplate;
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY,
                    new OrderPaidMessage(event.getUserId(), event.getOrderNo(), event.getAmount()),
                    m -> {
                        m.getMessageProperties().setMessageId(event.getOrderNo());
                        return m;
                    });
            outbox.markSentByKey(event.getOrderNo());
            log.info("[mq] 订单 {} 支付事件已投递 MQ userId={}", event.getOrderNo(), event.getUserId());
        } catch (Exception e) {
            // 写箱由 OrderOutboxEnqueuer 在同一业务事务内完成，这里失败只需不结案，worker 会补投
            log.error("[mq] 订单 {} 支付事件投递 MQ 失败，留待 outbox 补投（支付主流程不受影响）", event.getOrderNo(), e);
        }
    }
}

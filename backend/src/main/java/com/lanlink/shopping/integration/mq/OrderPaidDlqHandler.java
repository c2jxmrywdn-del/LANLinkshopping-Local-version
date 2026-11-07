package com.lanlink.shopping.integration.mq;

import com.lanlink.shopping.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 订单支付流死信处理器（out/storage-mq-plan.md 任务 B4/B5，门禁 G11）。
 *
 * 设计决策：不做 @RabbitListener 自动消费——死信必须停在 q.order.paid.dlq 供人工排查
 * （G11 判定：毒载荷重试耗尽后 DLQ messages ≥ 1），因此只提供运维显式调用的
 * depth/replay/purge 能力（对应 scripts/mq/replay-dlq.ps1 的应用内等价操作）。
 *
 * 重放安全（R2）：两个业务消费者分别按 orderNo（MemberPointLog 查重）与
 * uk_cp_campaign_user 唯一键幂等，同一死信重放不产生重复副作用。
 *
 * 开关：app.mq.order-flow=event 时本类不注册（Spring 事件通道无死信拓扑）。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class OrderPaidDlqHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidDlqHandler.class);

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;

    public OrderPaidDlqHandler(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 当前死信积压条数；队列不存在返回 -1。 */
    public int depth() {
        QueueInformation info = amqpAdmin.getQueueInfo(RabbitConfig.ORDER_PAID_DLQ);
        return info == null ? -1 : info.getMessageCount();
    }

    /**
     * 重放至多 max 条死信回原交换机（lanlink.order / order.paid），返回实际重放条数。
     * 原始消息体与头部（含 x-death）原样透传；真正的毒载荷会被再次弹回 DLQ，不造成无限循环。
     */
    public int replay(int max) {
        int replayed = 0;
        while (replayed < max) {
            Message message = rabbitTemplate.receive(RabbitConfig.ORDER_PAID_DLQ);
            if (message == null) {
                break;
            }
            rabbitTemplate.send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY, message);
            replayed++;
        }
        log.info("[mq][dlq] 死信重放 {} 条 → {}/{}", replayed,
                RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY);
        return replayed;
    }

    /** 清空（丢弃）死信队列，返回清除条数。 */
    public int purge() {
        int purged = amqpAdmin.purgeQueue(RabbitConfig.ORDER_PAID_DLQ);
        log.info("[mq][dlq] 死信队列 {} 已清空 {} 条", RabbitConfig.ORDER_PAID_DLQ, purged);
        return purged;
    }
}

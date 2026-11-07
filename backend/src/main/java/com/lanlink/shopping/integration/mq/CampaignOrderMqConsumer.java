package com.lanlink.shopping.integration.mq;

import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费有礼 MQ 消费者（out/storage-mq-plan.md 任务 B4，门禁 G9/G10）。
 *
 * 消费 q.order.paid.campaign → 先经 {@link OrderPaidMessageValidator} 反查 t_order/t_credit_bill
 * 确认载荷真伪（security-hardening-plan.md P1-6.1：autoJoinPurchase 按 userId 参与活动，
 * 更必须先证明该订单真实存在且已支付）→ 复用 CampaignService.autoJoinPurchase(userId)：
 * 参与幂等由 uk_cp_campaign_user(campaign_id, user_id) 唯一键 + DuplicateKeyException 兜底（R2），
 * at-least-once 重复投递/重放不产生重复参与记录（G10）。
 *
 * 失败策略（R3）：业务异常上抛 → spring-retry 3 次 → default-requeue-rejected=false →
 * 经 lanlink.dlx 进 q.order.paid.dlq（G11）。
 *
 * 开关：app.mq.order-flow=event 时本类不注册（消费回退 Spring 事件通道）。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class CampaignOrderMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(CampaignOrderMqConsumer.class);

    private final CampaignService campaignService;
    private final OrderPaidMessageValidator validator;

    public CampaignOrderMqConsumer(CampaignService campaignService, OrderPaidMessageValidator validator) {
        this.campaignService = campaignService;
        this.validator = validator;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_PAID_CAMPAIGN_QUEUE)
    public void onOrderPaid(OrderPaidMessage message) {
        // 入账前反查真实订单（SG-61）：不符即 MessageIntegrityException → 重试耗尽 → DLQ
        validator.requireLegitOrderPaid(message);
        campaignService.autoJoinPurchase(message.userId());
        log.info("[mq][campaign] 订单 {} 消费活动自动参与完成 userId={}", message.orderNo(), message.userId());
    }
}

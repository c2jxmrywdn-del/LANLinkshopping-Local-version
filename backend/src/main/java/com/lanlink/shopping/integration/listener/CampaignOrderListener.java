package com.lanlink.shopping.integration.listener;

import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 活动系统 × 订单系统 事件桥接。
 * 监听订单支付事件 → 「消费有礼」活动自动记录参与（幂等：campaign+user 唯一）。
 * AFTER_COMMIT 消费：订单回滚时不再产生营销副作用；独立事务 + 异常隔离，不影响支付主流程。
 *
 * <p>互斥开关：app.mq.order-flow=event 时生效；默认 (mq) 模式下由 {@code CampaignOrderMqConsumer} 承载，
 * 避免双通道重复执行。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "event")
public class CampaignOrderListener {

    private static final Logger log = LoggerFactory.getLogger(CampaignOrderListener.class);

    private final CampaignService campaignService;

    public CampaignOrderListener(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            campaignService.autoJoinPurchase(event.getUserId());
        } catch (Exception e) {
            log.error("[campaign] 消费活动自动参与失败 orderNo={}", event.getOrderNo(), e);
        }
    }
}

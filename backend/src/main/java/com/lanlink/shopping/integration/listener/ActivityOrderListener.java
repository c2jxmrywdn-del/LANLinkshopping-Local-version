package com.lanlink.shopping.integration.listener;

import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.module.activity.service.ActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动系统 × 订单系统 事件桥接。
 * 监听订单支付事件 → 「消费有礼」活动自动记录参与（幂等：activity+user 唯一）。
 * 独立事务 + 异常隔离：不影响订单支付主流程。
 */
@Component
public class ActivityOrderListener {

    private static final Logger log = LoggerFactory.getLogger(ActivityOrderListener.class);

    private final ActivityService activityService;

    public ActivityOrderListener(ActivityService activityService) {
        this.activityService = activityService;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            activityService.autoJoinPurchase(event.getUserId());
        } catch (Exception e) {
            log.error("[activity] 消费活动自动参与失败 orderNo={}", event.getOrderNo(), e);
        }
    }
}

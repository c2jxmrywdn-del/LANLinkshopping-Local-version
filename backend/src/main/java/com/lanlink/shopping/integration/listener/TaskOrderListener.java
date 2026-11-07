package com.lanlink.shopping.integration.listener;

import com.lanlink.shopping.integration.event.OrderPaidEvent;
import com.lanlink.shopping.module.task.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 任务系统 × 订单系统 事件桥接（F3 首单任务）。
 * 监听订单支付事件 → 发放首单有礼（first_order）。
 * AFTER_COMMIT 消费：订单回滚不再产生任务副作用；REQUIRES_NEW + 异常吞掉，保证核心交易一致性
 * （模板同 MembershipOrderListener.java:40-55）。
 * 幂等：任务流水 uk_task_user_key_ref(user_id, task_key, ref_no=orderNo) 唯一键防重，
 * 奖励积分经 awardPoints 固定引用号 task:first_order:{userId} 幂等落账，重复事件只计一次分。
 */
@Component
public class TaskOrderListener {

    private static final Logger log = LoggerFactory.getLogger(TaskOrderListener.class);

    private final TaskService taskService;

    public TaskOrderListener(TaskService taskService) {
        this.taskService = taskService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderPaid(OrderPaidEvent event) {
        try {
            taskService.completeFirstOrder(event.getUserId(), event.getOrderNo());
            log.info("[task] 订单 {} 支付成功，首单任务已处理 userId={}", event.getOrderNo(), event.getUserId());
        } catch (Exception e) {
            // 隔离原则：营销侧异常不得影响订单主流程，仅记录
            log.error("[task] 首单任务发放失败 orderNo={}", event.getOrderNo(), e);
        }
    }
}

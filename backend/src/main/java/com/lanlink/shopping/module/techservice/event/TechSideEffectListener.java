package com.lanlink.shopping.module.techservice.event;

import com.lanlink.shopping.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 技术服务副作用监听：业务事务提交后再写站内消息。
 *
 * <ul>
 *   <li>AFTER_COMMIT：业务回滚时不会产生通知；</li>
 *   <li>REQUIRES_NEW：消息写入独立事务，失败不回滚已提交的业务；</li>
 *   <li>吞异常并记录日志：通知失败不应影响用户主流程。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "event")
public class TechSideEffectListener {

    private static final Logger log = LoggerFactory.getLogger(TechSideEffectListener.class);

    private final MessageService messageService;

    public TechSideEffectListener(MessageService messageService) {
        this.messageService = messageService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSideEffect(TechSideEffectEvent event) {
        if (event == null || event.userId() == null) return;
        try {
            messageService.send(event.userId(), "TECH", event.title(), event.content(), event.relatedNo());
        } catch (Exception e) {
            log.warn("[tech-notify] message send failed: {}", e.getMessage());
        }
    }
}

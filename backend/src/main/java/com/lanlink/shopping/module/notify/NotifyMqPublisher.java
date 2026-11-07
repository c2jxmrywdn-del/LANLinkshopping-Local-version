package com.lanlink.shopping.module.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 快车道：事务提交后立即投递，成功就把箱里的行结案。
 *
 * <p>可靠性不靠这里——行是 NotifyOutboxEnqueuer 在业务事务内写的；本类失败只需"不结案"，
 * worker 会按退避重投。所以这里绝不再写新行，否则同一件事会出现两行、发两封信。
 */
@Component
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "mq", matchIfMissing = true)
public class NotifyMqPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotifyMqPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final NotifyOutboxMapper outbox;

    public NotifyMqPublisher(RabbitTemplate rabbit, ObjectMapper objectMapper, NotifyOutboxMapper outbox) {
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void relay(TechSideEffectEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        NotifyPayload payload = NotifyPayload.ofTech(event);
        try {
            rabbit.convertAndSend(NotifyRabbitConfig.EXCHANGE, NotifyRabbitConfig.ROUTING_KEY,
                    objectMapper.writeValueAsString(payload));
            outbox.markSentByKey(payload.messageKey());
        } catch (Exception e) {
            log.warn("[notify] 即时投递失败，留待 worker 重投 key={}", payload.messageKey(), e);
        }
    }
}

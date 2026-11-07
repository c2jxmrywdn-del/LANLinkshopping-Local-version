package com.lanlink.shopping.module.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 通知消费：在同一事务里先占去重键，再写站内信。
 *
 * <p>「同一事务」是这句话的重点：占键成功、发信抛异常时必须连键一起回滚，否则消息重投时
 * 会被自己的键当成重复直接吞掉——箱承诺的是至少一次，那样就变成至多一次，通知永久消失。
 * 载荷解析不了的消息显式 reject 且不重回队列——否则一条毒消息会把消费者打成无限重投，
 * 并把真正的通知堵在后面；它应该停在 q.notify.dlq 等人看。
 */
@Component
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "mq", matchIfMissing = true)
public class NotifyMqConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotifyMqConsumer.class);

    private final NotifyDedupeMapper dedupeMapper;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    public NotifyMqConsumer(NotifyDedupeMapper dedupeMapper, MessageService messageService,
                            ObjectMapper objectMapper) {
        this.dedupeMapper = dedupeMapper;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = NotifyRabbitConfig.QUEUE)
    @Transactional
    public void consume(String body) {
        NotifyPayload payload;
        try {
            payload = objectMapper.readValue(body, NotifyPayload.class);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("通知载荷不可解析", e);
        }

        NotifyDedupe claim = new NotifyDedupe();
        claim.setMessageKey(payload.messageKey());
        claim.setConsumedAt(LocalDateTime.now());
        try {
            dedupeMapper.insert(claim);
        } catch (DuplicateKeyException already) {
            log.debug("[notify] 重复投递已折叠 key={}", payload.messageKey());
            return;
        }

        messageService.send(payload.userId(), payload.type(), payload.title(),
                payload.content(), payload.relatedNo());
    }
}

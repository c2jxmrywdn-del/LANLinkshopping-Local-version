package com.lanlink.shopping.module.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 把暂存的投递失败重新推向 MQ；成功即结案，超阈值转 DEAD 等人工处理。
 *
 * <p>只跟 mq 通道同生共死：事件通道下没有交换机声明，扫到历史遗留行会把它推成死态，
 * 白白烧掉一条本可补投的通知。
 */
@Component
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "mq", matchIfMissing = true)
public class NotifyOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotifyOutboxWorker.class);
    private static final int BATCH_SIZE = 20;

    private final RabbitTemplate rabbit;
    private final NotifyOutboxMapper outbox;

    public NotifyOutboxWorker(RabbitTemplate rabbit, NotifyOutboxMapper outbox) {
        this.rabbit = rabbit;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${app.mq.notify-outbox-interval-ms:60000}",
            initialDelayString = "${app.mq.notify-outbox-initial-delay-ms:30000}")
    public void scheduledDrain() {
        int sent = drain(LocalDateTime.now());
        if (sent > 0) {
            log.info("[notify] 重试补发 {} 条通知", sent);
        }
    }

    int drain(LocalDateTime now) {
        int sent = 0;
        for (NotifyOutbox row : outbox.claimDue(now, BATCH_SIZE)) {
            try {
                rabbit.convertAndSend(NotifyRabbitConfig.EXCHANGE, NotifyRabbitConfig.ROUTING_KEY,
                        row.getPayload());
                outbox.markSent(row.getId());
                sent++;
            } catch (RuntimeException e) {
                int next = row.getRetryCount() + 1;
                if (NotifyOutboxPolicy.exhausted(next)) {
                    // 转死态而不是继续重投：反复失败的载荷通常是毒消息，留着只会堵队列
                    outbox.markDead(row.getId());
                    log.error("[notify] 通知重投超限转死态 id={} key={} retry={}",
                            row.getId(), row.getMessageKey(), next);
                } else {
                    outbox.markRetry(row.getId(), next,
                            now.plusSeconds(NotifyOutboxPolicy.backoffSeconds(next)));
                }
            }
        }
        return sent;
    }
}

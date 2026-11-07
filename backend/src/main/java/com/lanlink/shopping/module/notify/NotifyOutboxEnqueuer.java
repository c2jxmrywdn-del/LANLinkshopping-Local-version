package com.lanlink.shopping.module.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 严格 Outbox 的写箱侧：普通 {@link EventListener}，因此运行在业务事务内部。
 *
 * <p>与 AFTER_COMMIT 直发的区别才是关键：写箱与业务写库同事务，业务回滚则箱里没有这一行
 * （不会发出没发生的通知）；业务提交成功则这一行必然存在，进程哪怕在投递前崩溃，
 * worker 也会补投。这就是"至少一次"的来处，快车道只负责把延迟压下去。
 */
@Component
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "mq", matchIfMissing = true)
public class NotifyOutboxEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(NotifyOutboxEnqueuer.class);

    private final NotifyOutboxMapper outbox;
    private final ObjectMapper objectMapper;

    public NotifyOutboxEnqueuer(NotifyOutboxMapper outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void enqueue(TechSideEffectEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        NotifyPayload payload = NotifyPayload.ofTech(event);
        try {
            NotifyOutbox row = new NotifyOutbox();
            row.setMessageKey(payload.messageKey());
            row.setPayload(objectMapper.writeValueAsString(payload));
            row.setStatus(NotifyOutbox.STATUS_PENDING);
            row.setRetryCount(0);
            row.setNextRetryAt(LocalDateTime.now());
            outbox.insert(row);
        } catch (DuplicateKeyException same) {
            // 业务重试会用同一个键再写一次：同一件事，保留既有那行由 worker 继续处理
            log.debug("[notify] 重复入箱已折叠 key={}", payload.messageKey());
        } catch (Exception e) {
            log.error("[notify] 写箱失败 userId={} relatedNo={}", event.userId(), event.relatedNo(), e);
        }
    }
}

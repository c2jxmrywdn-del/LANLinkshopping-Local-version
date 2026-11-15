package com.lanlink.shopping.support.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 投递器：把「待解析」任务推给 MQ，失败按指数退避重试。
 *
 * <p>每 5 秒拉 20 条 due 任务，认领用 CAS UPDATE（{@code locked_at IS NULL OR 超过 5 分钟}）：
 * 多实例部署时只有一个实例能拿到同一条任务；实例崩溃留下的锁定 5 分钟后自动可被接管。
 *
 * <p>退避 30s × 2^n，上限 10 分钟：短故障（MQ 抖动）快速恢复，长故障（broker 挂掉）
 * 不再把日志刷爆，同时保证任务不会丢——状态一直是 0。
 */
@Component
@ConditionalOnProperty(name = "app.mq.support-media-flow", havingValue = "mq", matchIfMissing = true)
public class SupportMediaOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaOutboxWorker.class);

    private static final int BATCH_SIZE = 20;
    private static final long BASE_BACKOFF_SECONDS = 30L;
    private static final long MAX_BACKOFF_SECONDS = 600L;

    private final SupportMediaRepository repository;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public SupportMediaOutboxWorker(SupportMediaRepository repository, RabbitTemplate rabbit, ObjectMapper json) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.json = json;
    }

    /** 载荷：消费端只需要定位对象与类型，不重复携带用户信息 */
    public record ParseTask(long mediaId, String mediaNo, String kind, String objectKey) {
    }

    @Scheduled(fixedDelayString = "${app.support-media.outbox-interval-ms:5000}",
            initialDelayString = "${app.support-media.outbox-initial-delay-ms:15000}")
    public void scheduledPump() {
        int sent = pump();
        if (sent > 0) {
            log.debug("[support-media] outbox 投递 {} 条", sent);
        }
    }

    int pump() {
        List<SupportMediaRepository.OutboxTask> due = repository.findDueOutbox(BATCH_SIZE);
        int sent = 0;
        for (SupportMediaRepository.OutboxTask task : due) {
            if (!repository.lockOutbox(task.outboxId())) {
                continue;
            }
            try {
                String payload = json.writeValueAsString(
                        new ParseTask(task.mediaId(), task.mediaNo(), task.kind(), task.objectKey()));
                rabbit.convertAndSend(SupportMediaRabbitConfig.EXCHANGE,
                        SupportMediaRabbitConfig.ROUTING_KEY, payload);
                repository.markOutboxSent(task.outboxId());
                sent++;
            } catch (Exception e) {
                repository.markOutboxFailed(task.outboxId(), nextRetryAt(task.retryCount()), e.getMessage());
                log.warn("[support-media] 解析任务投递失败 mediaNo={} retry={} err={}",
                        task.mediaNo(), task.retryCount() + 1, e.getMessage());
            }
        }
        return sent;
    }

    /** 30s × 2^n，上限 10 分钟；用「本轮之前已失败次数」作为 n，首次失败即 30s 后重试 */
    static LocalDateTime nextRetryAt(int retryCount) {
        int exponent = Math.min(Math.max(retryCount, 0), 10);
        long seconds = Math.min(BASE_BACKOFF_SECONDS << exponent, MAX_BACKOFF_SECONDS);
        return LocalDateTime.now().plusSeconds(seconds);
    }
}

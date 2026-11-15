package com.lanlink.shopping.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.config.RabbitConfig;
import com.lanlink.shopping.module.notify.NotifyOutboxPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 订单事件 outbox 补投：只处理投递失败/进程中断遗留的 PENDING 行。 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class OrderOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxWorker.class);
    private static final int BATCH_SIZE = 20;

    private final RabbitTemplate rabbit;
    private final OrderOutboxMapper outbox;
    private final ObjectMapper objectMapper;

    public OrderOutboxWorker(RabbitTemplate rabbit, OrderOutboxMapper outbox,
                             ObjectMapper objectMapper) {
        this.rabbit = rabbit;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.mq.order-outbox-interval-ms:60000}",
            initialDelayString = "${app.mq.order-outbox-initial-delay-ms:30000}")
    public void scheduledDrain() {
        int sent = drain(LocalDateTime.now());
        if (sent > 0) {
            log.info("[order-outbox] 补发 {} 条订单支付事件", sent);
        }
    }

    /**
     * 补发待投递事件。
     *
     * <p>事务边界：认领（{@code FOR UPDATE SKIP LOCKED}）必须在事务内，否则行锁随语句结束释放，
     * 多副本仍会重复认领。这里整体加 {@code @Transactional}，让「认领 → 发送 → 标记」
     * 在同一个事务里完成；发送失败抛异常时整批回滚，行保持 status=0 由下一轮重试
     * （已发出的那几条由消费者幂等兜底，不会重复发奖）。
     */
    @org.springframework.transaction.annotation.Transactional
    int drain(LocalDateTime now) {
        int sent = 0;
        for (OrderOutbox row : outbox.claimDue(now, BATCH_SIZE)) {
            try {
                OrderPaidMessage message = objectMapper.readValue(row.getPayload(), OrderPaidMessage.class);
                rabbit.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY,
                        message, m -> {
                            m.getMessageProperties().setMessageId(message.orderNo());
                            return m;
                        });
                outbox.markSent(row.getId());
                sent++;
            } catch (RuntimeException e) {
                // retryCount 是 Integer：历史行可能为 NULL，直接拆箱会 NPE，
                // 而 NPE 发生在 catch 块内部不会被同级的 catch(Exception) 捕获，会中断整批循环。
                int next = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
                if (NotifyOutboxPolicy.exhausted(next)) {
                    outbox.markDead(row.getId());
                    log.error("[order-outbox] 重投超限转死态 id={} orderNo={} retry={}",
                            row.getId(), row.getMessageKey(), next);
                } else {
                    outbox.markRetry(row.getId(), next,
                            now.plusSeconds(NotifyOutboxPolicy.backoffSeconds(next)));
                }
            } catch (Exception e) {
                log.error("[order-outbox] 载荷解析失败 id={} orderNo={}", row.getId(), row.getMessageKey(), e);
                outbox.markDead(row.getId());
            }
        }
        return sent;
    }
}

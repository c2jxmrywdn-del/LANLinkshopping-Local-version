package com.lanlink.shopping.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.integration.event.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 订单支付事件写箱：普通 {@link EventListener}，与业务写库同事务。
 * 业务回滚不会留下“没发生的订单”；业务提交后即使投递失败，worker 也能补投。
 */
@Component
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class OrderOutboxEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(OrderOutboxEnqueuer.class);

    private final OrderOutboxMapper outbox;
    private final ObjectMapper objectMapper;

    public OrderOutboxEnqueuer(OrderOutboxMapper outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void enqueue(OrderPaidEvent event) {
        if (event == null || event.getOrderNo() == null || event.getOrderNo().isBlank()) {
            return;
        }
        try {
            OrderOutbox row = new OrderOutbox();
            row.setMessageKey(event.getOrderNo());
            row.setPayload(objectMapper.writeValueAsString(new OrderPaidMessage(
                    event.getUserId(), event.getOrderNo(), event.getAmount())));
            row.setStatus(OrderOutbox.STATUS_PENDING);
            row.setRetryCount(0);
            row.setNextRetryAt(LocalDateTime.now());
            outbox.insert(row);
        } catch (DuplicateKeyException same) {
            log.debug("[order-outbox] 重复入箱已折叠 orderNo={}", event.getOrderNo());
        } catch (Exception e) {
            log.error("[order-outbox] 写箱失败 orderNo={}", event.getOrderNo(), e);
        }
    }
}

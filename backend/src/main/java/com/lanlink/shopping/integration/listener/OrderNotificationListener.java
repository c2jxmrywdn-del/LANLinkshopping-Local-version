package com.lanlink.shopping.integration.listener;

import com.lanlink.shopping.integration.event.OrderNotificationEvent;
import com.lanlink.shopping.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单通知监听：业务事务提交后写站内消息。
 * fallbackExecution=true 兼容非事务入口（如 user-initiated pay()），保持原有即时通知体验。
 */
@Component
public class OrderNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationListener.class);

    private final MessageService messageService;

    public OrderNotificationListener(MessageService messageService) {
        this.messageService = messageService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNotification(OrderNotificationEvent event) {
        if (event == null || event.userId() == null) return;
        try {
            messageService.send(event.userId(), "order", event.title(), event.content(), event.relatedNo());
        } catch (Exception e) {
            log.warn("[order-notify] message send failed orderNo={}: {}", event.relatedNo(), e.getMessage());
        }
    }
}

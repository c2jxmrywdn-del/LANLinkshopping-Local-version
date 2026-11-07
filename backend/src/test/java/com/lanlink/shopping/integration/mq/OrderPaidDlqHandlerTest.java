package com.lanlink.shopping.integration.mq;

import com.lanlink.shopping.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信处理器单测（out/storage-mq-plan.md 任务 B5，门禁 G11）：积压查看 / 重放回原交换机 / 清空。
 */
class OrderPaidDlqHandlerTest {

    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final OrderPaidDlqHandler handler = new OrderPaidDlqHandler(amqpAdmin, rabbitTemplate);

    @Test
    void depthReportsQueueMessageCount() {
        when(amqpAdmin.getQueueInfo(RabbitConfig.ORDER_PAID_DLQ))
                .thenReturn(new QueueInformation(RabbitConfig.ORDER_PAID_DLQ, 5, 0));
        assertEquals(5, handler.depth());
    }

    @Test
    void depthReturnsMinusOneWhenQueueMissing() {
        when(amqpAdmin.getQueueInfo(anyString())).thenReturn(null);
        assertEquals(-1, handler.depth());
    }

    @Test
    void replayRepublishesDeadLettersToOriginalExchange() {
        Message dead = new Message("{\"userId\":7,\"orderNo\":\"ORD-DLQ-1\"}".getBytes(), new MessageProperties());
        when(rabbitTemplate.receive(RabbitConfig.ORDER_PAID_DLQ)).thenReturn(dead, (Message) null);

        int replayed = handler.replay(10);

        assertEquals(1, replayed);
        verify(rabbitTemplate).send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY, dead);
    }

    @Test
    void replayStopsAtRequestedMax() {
        Message dead = new Message("x".getBytes(), new MessageProperties());
        when(rabbitTemplate.receive(anyString())).thenReturn(dead);

        int replayed = handler.replay(3);

        assertEquals(3, replayed);
        verify(rabbitTemplate, times(3))
                .send(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_PAID_ROUTING_KEY, dead);
    }

    @Test
    void replayWithoutDeadLettersReturnsZero() {
        when(rabbitTemplate.receive(anyString())).thenReturn(null);
        assertEquals(0, handler.replay(10));
    }

    @Test
    void purgeClearsQueueViaAdmin() {
        when(amqpAdmin.purgeQueue(RabbitConfig.ORDER_PAID_DLQ)).thenReturn(4);
        assertEquals(4, handler.purge());
    }
}

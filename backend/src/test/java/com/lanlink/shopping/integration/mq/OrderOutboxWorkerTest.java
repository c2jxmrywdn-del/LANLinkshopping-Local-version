package com.lanlink.shopping.integration.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderOutboxWorkerTest {

    @Test
    void drainRepublishesAndMarksSent() {
        OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OrderOutbox row = new OrderOutbox();
        row.setId(1L);
        row.setMessageKey("ORD-1");
        row.setPayload("{\"userId\":7,\"orderNo\":\"ORD-1\",\"amount\":10.00}");
        row.setRetryCount(0);
        when(mapper.claimDue(any(), eq(20))).thenReturn(List.of(row));

        OrderOutboxWorker worker = new OrderOutboxWorker(rabbit, mapper, objectMapper);
        assertEquals(1, worker.drain(LocalDateTime.now()));
        verify(rabbit).convertAndSend(eq(RabbitConfig.ORDER_EXCHANGE),
                eq(RabbitConfig.ORDER_PAID_ROUTING_KEY), any(OrderPaidMessage.class),
                any(MessagePostProcessor.class));
        verify(mapper).markSent(1L);
    }

    @Test
    void drainSchedulesRetryWhenBrokerFails() {
        OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();

        OrderOutbox row = new OrderOutbox();
        row.setId(2L);
        row.setMessageKey("ORD-2");
        row.setPayload("{\"userId\":7,\"orderNo\":\"ORD-2\",\"amount\":10.00}");
        row.setRetryCount(0);
        when(mapper.claimDue(any(), eq(20))).thenReturn(List.of(row));
        doThrow(new RuntimeException("broker down")).when(rabbit)
                .convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));
        when(mapper.markRetry(any(), any(Integer.class), any())).thenReturn(1);

        OrderOutboxWorker worker = new OrderOutboxWorker(rabbit, mapper, objectMapper);
        assertEquals(0, worker.drain(LocalDateTime.now()));
        verify(mapper).markRetry(eq(2L), eq(1), any());
    }
}

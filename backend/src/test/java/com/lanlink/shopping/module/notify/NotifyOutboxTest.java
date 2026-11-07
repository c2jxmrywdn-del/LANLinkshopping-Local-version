package com.lanlink.shopping.module.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递失败后的重试落箱。
 *
 * <p>只 log.error 等于承认"broker 抖一下就永久丢通知"。这里把失败的载荷连同去重键落库，
 * 由 worker 退避重投；超过阈值转 DEAD 并告警，避免无限重投吃掉队列。
 * <p>边界说明：本机制覆盖"提交后投递失败"，不覆盖"提交成功但进程在投递前崩溃"——
 * 那需要与业务同事务的严格 Outbox，已单独记任务，不在这里假装已经解决。
 */
class NotifyOutboxTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final NotifyOutboxMapper outbox = mock(NotifyOutboxMapper.class);
    private final ObjectMapper json = new ObjectMapper();

    private final TechSideEffectEvent event =
            new TechSideEffectEvent(1001L, "专家审核通过", "恭喜通过审核", "TE20261104001");

    @Test
    void successfulRelayClosesTheBoxedRowAndNeverCreatesOne() {
        new NotifyMqPublisher(rabbit, json, outbox).relay(event);

        verify(rabbit).convertAndSend(eq(NotifyRabbitConfig.EXCHANGE),
                eq(NotifyRabbitConfig.ROUTING_KEY), any(Object.class));
        verify(outbox).markSentByKey(NotifyPayload.techKey(event));
        verify(outbox, never()).insert(any(NotifyOutbox.class));
    }

    @Test
    void relayFailureLeavesTheBoxedRowForTheWorker() {
        org.mockito.Mockito.doThrow(new AmqpException("broker 不可达"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        new NotifyMqPublisher(rabbit, json, outbox).relay(event);

        // 行是业务事务内写的，快车道失败只需"不结案"，交给 worker 重投；
        // 这里若再 insert，就会出现同一件事两行、发两封信
        verify(outbox, never()).markSentByKey(anyString());
        verify(outbox, never()).insert(any(NotifyOutbox.class));
    }

    @Test
    void workerRepublishesPendingRowAndMarksItSent() {
        NotifyOutbox row = pendingRow(0);
        when(outbox.claimDue(any(LocalDateTime.class), anyInt())).thenReturn(java.util.List.of(row));

        int sent = new NotifyOutboxWorker(rabbit, outbox).drain(LocalDateTime.now());

        assertEquals(1, sent);
        verify(rabbit).convertAndSend(eq(NotifyRabbitConfig.EXCHANGE),
                eq(NotifyRabbitConfig.ROUTING_KEY), eq(row.getPayload()));
        verify(outbox).markSent(row.getId());
    }

    @Test
    void workerBumpsRetryAndSchedulesBackoffOnFailure() {
        NotifyOutbox row = pendingRow(2);
        when(outbox.claimDue(any(LocalDateTime.class), anyInt())).thenReturn(java.util.List.of(row));
        org.mockito.Mockito.doThrow(new AmqpException("还是不通"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        int sent = new NotifyOutboxWorker(rabbit, outbox).drain(LocalDateTime.now());

        assertEquals(0, sent);
        verify(outbox).markRetry(eq(row.getId()), eq(3), any(LocalDateTime.class));
    }

    @Test
    void workerDeadLettersAfterMaxRetries() {
        NotifyOutbox row = pendingRow(NotifyOutboxPolicy.MAX_RETRY - 1);
        when(outbox.claimDue(any(LocalDateTime.class), anyInt())).thenReturn(java.util.List.of(row));
        org.mockito.Mockito.doThrow(new AmqpException("还是不通"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        new NotifyOutboxWorker(rabbit, outbox).drain(LocalDateTime.now());

        verify(outbox).markDead(row.getId());
        verify(outbox, never()).markRetry(any(), anyInt(), any());
    }

    @Test
    void backoffGrowsExponentiallyWithinCap() {
        assertEquals(30, NotifyOutboxPolicy.backoffSeconds(0));
        assertEquals(60, NotifyOutboxPolicy.backoffSeconds(1));
        assertEquals(120, NotifyOutboxPolicy.backoffSeconds(2));
        assertTrue(NotifyOutboxPolicy.backoffSeconds(12) <= 3600,
                "退避必须有上限，否则一次故障后要等几天才重试");
    }

    private NotifyOutbox pendingRow(int retry) {
        NotifyOutbox row = new NotifyOutbox();
        row.setId(7L);
        row.setMessageKey(NotifyPayload.techKey(event));
        row.setPayload("{\"userId\":1001,\"messageKey\":\"k\"}");
        row.setStatus(NotifyOutbox.STATUS_PENDING);
        row.setRetryCount(retry);
        return row;
    }
}

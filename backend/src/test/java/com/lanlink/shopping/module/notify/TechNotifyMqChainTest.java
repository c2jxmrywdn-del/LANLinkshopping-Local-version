package com.lanlink.shopping.module.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.service.MessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 技术服务系统通知的 MQ 链路。
 *
 * <p>原来站内消息是在 AFTER_COMMIT 里直接调用 MessageService：进程内、无重试、失败只落一行日志，
 * 应用重启即永久丢失。这里改成"提交后投递 → 消费落库 + 去重 → 失败进死信"，
 * 并且与施工方的订单链路（lanlink.order / q.order.paid.*）完全隔离：自己的交换机与自己的 DLX，
 * 因为对方 order-flow 关掉时 lanlink.dlx 可能压根不存在，借用会让我的死信无处投递。
 */
class TechNotifyMqChainTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final NotifyDedupeMapper dedupe = mock(NotifyDedupeMapper.class);
    private final MessageService messageService = mock(MessageService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publisherSendsToNotifyExchangeWithDedupeKey() throws Exception {
        new NotifyMqPublisher(rabbit, json, mock(NotifyOutboxMapper.class)).relay(
                new TechSideEffectEvent(1001L, "专家审核通过", "恭喜通过审核", "TE20261104001"));

        var captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq("lanlink.notify"), eq("notify.message"), captor.capture());
        Map<String, Object> payload = json.readValue(captor.getValue(), Map.class);
        assertEquals(1001, ((Number) payload.get("userId")).intValue());
        assertEquals("TECH", payload.get("type"));
        assertEquals("TE20261104001", payload.get("relatedNo"));
        assertNotNull(payload.get("messageKey"), "必须带去重键，否则重投会重复发信");
        // 投递方不再直接写站内信：否则 MQ 一开就变成双通道重复发送
        verify(messageService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void publisherSkipsEventsWithoutRecipient() {
        new NotifyMqPublisher(rabbit, json, mock(NotifyOutboxMapper.class)).relay(new TechSideEffectEvent(null, "t", "c", "n"));

        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void consumerSendsStationMessageOncePerKey() {
        NotifyMqConsumer consumer = new NotifyMqConsumer(dedupe, messageService, json);
        String body = "{\"userId\":1001,\"type\":\"TECH\",\"title\":\"专家审核通过\","
                + "\"content\":\"恭喜通过审核\",\"relatedNo\":\"TE20261104001\",\"messageKey\":\"k1\"}";

        consumer.consume(body);

        verify(messageService).send(1001L, "TECH", "专家审核通过", "恭喜通过审核", "TE20261104001");
    }

    @Test
    void duplicateDeliveryIsSwallowedWithoutSecondMessage() {
        doThrow(new DuplicateKeyException("dup")).when(dedupe).insert(any(NotifyDedupe.class));
        NotifyMqConsumer consumer = new NotifyMqConsumer(dedupe, messageService, json);

        consumer.consume("{\"userId\":1001,\"type\":\"TECH\",\"title\":\"t\",\"content\":\"c\","
                + "\"relatedNo\":\"TE1\",\"messageKey\":\"k1\"}");

        verify(messageService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void malformedPayloadGoesToDeadLetterInsteadOfVanishing() {
        NotifyMqConsumer consumer = new NotifyMqConsumer(dedupe, messageService, json);

        // 毒消息必须显式拒绝且不重回队列，否则会无限重投把消费者打死
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume("{not-json"));
        verify(messageService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void consumerRunsDedupeAndSendInOneTransaction() throws Exception {
        // 去重键与站内信必须在同一事务里：先占键再发信，发信炸了要连键一起回滚，
        // 否则重投被自己的键吞掉，一条通知永久消失（箱设计是至少一次，这里不能变成至多一次）。
        // 单测用 mock 看不到事务边界，只能钉住这个接线约束。
        Transactional tx = NotifyMqConsumer.class.getMethod("consume", String.class)
                .getAnnotation(Transactional.class);
        assertNotNull(tx, "consume 必须带 @Transactional，否则发送失败会留下已占用的去重键");
    }

    @Test
    void outboxWorkerOnlyRunsOnTheMqChannel() {
        // 事件通道下没有 Enqueuer，worker 却仍会扫箱；一旦扫到历史遗留行，
        // 它会往一个根本没声明的交换机投递，把可重投的行推成死态。
        ConditionalOnProperty gate = NotifyOutboxWorker.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(gate, "worker 必须与 mq 通道同生共死");
        assertEquals("app.mq.notify-flow", gate.name()[0]);
        assertEquals("mq", gate.havingValue());
        assertTrue(gate.matchIfMissing(), "缺省必须是 mq，与其它四个 Bean 的缺省保持一致");
    }

    @Test
    void topologyIsOwnExchangeOwnDlxAndDurableQueues() {
        NotifyRabbitConfig config = new NotifyRabbitConfig();

        Queue main = config.notifyMessageQueue();
        Queue dead = config.notifyDlq();
        assertEquals("q.notify.message", main.getName());
        assertEquals("q.notify.dlq", dead.getName());
        assertTrue(main.isDurable(), "通知队列必须持久化，否则 broker 重启即丢");
        assertEquals("lanlink.notify.dlx",
                main.getArguments().get("x-dead-letter-exchange"), "死信要落到自己的 DLX");
        assertEquals("notify.dlq", main.getArguments().get("x-dead-letter-routing-key"));
        assertFalse(((DirectExchange) config.notifyExchange()).isDurable() == false,
                "交换机需持久化");

        Binding toQueue = config.notifyBinding();
        assertEquals("notify.message", toQueue.getRoutingKey());
        assertEquals(DestinationType.QUEUE, toQueue.getDestinationType());
        assertNotNull(config.notifyDlx());
        Binding toDlq = config.notifyDlqBinding();
        assertEquals("notify.dlq", toDlq.getRoutingKey());
        assertEquals("lanlink.notify.dlx", ((DirectExchange) config.notifyDlx()).getName(),
                "死信交换机必须自建：施工方订单链路用 lanlink.dlx，它把 order-flow 切到 event 时不会声明，"
                        + "借用会让我的死信静默丢失");
    }
}

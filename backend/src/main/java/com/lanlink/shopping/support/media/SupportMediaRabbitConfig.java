package com.lanlink.shopping.support.media;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客服媒体解析链路的 AMQP 拓扑（P0 只建拓扑，消费端在 P1 实现）。
 *
 * <p>沿用附件链路的范式：独立交换机 + 独立死信链，不借用 lanlink.dlx——那条链路可被
 * app.mq.order-flow 关掉，借用会让本链路的死信静默无处投递。开关为 app.mq.support-media-flow。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mq.support-media-flow", havingValue = "mq", matchIfMissing = true)
public class SupportMediaRabbitConfig {

    public static final String EXCHANGE = "lanlink.support.media";
    public static final String DLX = "lanlink.support.media.dlx";
    public static final String ROUTING_KEY = "support.media.parse";
    public static final String DLQ_ROUTING_KEY = "support.media.dlq";
    public static final String QUEUE = "q.support.media.parse";
    public static final String DLQ = "q.support.media.dlq";

    @Bean
    public DirectExchange supportMediaExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange supportMediaDlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue supportMediaParseQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue supportMediaDlq() {
        // DLQ 显式 classic：quorum 队列上 basic.get 重放受限，运维要能逐条取出来分析
        return QueueBuilder.durable(DLQ)
                .withArgument("x-queue-type", "classic")
                .build();
    }

    @Bean
    public Binding supportMediaBinding() {
        return BindingBuilder.bind(supportMediaParseQueue()).to(supportMediaExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding supportMediaDlqBinding() {
        return BindingBuilder.bind(supportMediaDlq()).to(supportMediaDlx()).with(DLQ_ROUTING_KEY);
    }
}

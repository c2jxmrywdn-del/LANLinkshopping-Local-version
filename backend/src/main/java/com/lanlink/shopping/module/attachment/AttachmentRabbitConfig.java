package com.lanlink.shopping.module.attachment;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 附件图片异步处理的 AMQP 拓扑（上传只入队，缩略图在消费侧生成）。
 *
 * <p>与订单、通知两条链路一样自带交换机与死信链，不借用 lanlink.dlx / lanlink.notify.dlx：
 * 那两条链路各自可被关掉（order-flow / notify-flow 切 event 时根本不声明它们的 DLX），
 * 借用会让本链路的死信静默无处投递。开关独立为 app.mq.attachment-flow。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mq.attachment-flow", havingValue = "mq", matchIfMissing = true)
public class AttachmentRabbitConfig {

    public static final String EXCHANGE = "lanlink.attachment";
    public static final String DLX = "lanlink.attachment.dlx";
    public static final String ROUTING_KEY = "attachment.process";
    public static final String DLQ_ROUTING_KEY = "attachment.dlq";
    public static final String QUEUE = "q.attachment.process";
    public static final String DLQ = "q.attachment.dlq";

    @Bean
    public DirectExchange attachmentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange attachmentDlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue attachmentProcessQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue attachmentDlq() {
        // DLQ 显式 classic：quorum 队列上的 basic.get 重放受限，运维脚本要能逐条取
        return QueueBuilder.durable(DLQ)
                .withArgument("x-queue-type", "classic")
                .build();
    }

    @Bean
    public Binding attachmentBinding() {
        return BindingBuilder.bind(attachmentProcessQueue()).to(attachmentExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding attachmentDlqBinding() {
        return BindingBuilder.bind(attachmentDlq()).to(attachmentDlx()).with(DLQ_ROUTING_KEY);
    }
}

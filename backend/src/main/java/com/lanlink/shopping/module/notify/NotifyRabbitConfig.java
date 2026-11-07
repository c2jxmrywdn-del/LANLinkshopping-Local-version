package com.lanlink.shopping.module.notify;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 系统通知的 AMQP 拓扑。
 *
 * <p>与并行施工方的订单链路（lanlink.order / lanlink.dlx / q.order.paid.*）刻意不同名且互不引用：
 * 对方把 app.mq.order-flow 切到 event 时不会声明 lanlink.dlx，若借用它，本链路的死信会无处投递、
 * 静默消失。这里自带交换机与死信链，开关是独立的 app.mq.notify-flow。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mq.notify-flow", havingValue = "mq", matchIfMissing = true)
public class NotifyRabbitConfig {

    public static final String EXCHANGE = "lanlink.notify";
    public static final String DLX = "lanlink.notify.dlx";
    public static final String ROUTING_KEY = "notify.message";
    public static final String DLQ_ROUTING_KEY = "notify.dlq";
    public static final String QUEUE = "q.notify.message";
    public static final String DLQ = "q.notify.dlq";

    @Bean
    public DirectExchange notifyExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange notifyDlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue notifyMessageQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue notifyDlq() {
        // DLQ 显式 classic：OrderPaidDlqHandler 等运维工具依赖 basic.get 重放
        return QueueBuilder.durable(DLQ)
                .withArgument("x-queue-type", "classic")
                .build();
    }

    @Bean
    public Binding notifyBinding() {
        return BindingBuilder.bind(notifyMessageQueue()).to(notifyExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding notifyDlqBinding() {
        return BindingBuilder.bind(notifyDlq()).to(notifyDlx()).with(DLQ_ROUTING_KEY);
    }
}

package com.lanlink.shopping.config;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * RabbitMQ 订单支付流拓扑声明（out/storage-mq-plan.md 任务 B2，门禁 G8）。
 *
 * 拓扑（4.1 三节点集群 + quorum）：
 *   lanlink.order(topic) ──order.paid──► q.order.paid.membership ─┐
 *                        └─order.paid──► q.order.paid.campaign  ─┤ 消费重试耗尽
 *                                                                  ▼
 *   lanlink.dlx(direct) ◄── x-dead-letter-exchange ────────────────┘
 *          └─order.paid.dlq──► q.order.paid.dlq（两条消费队列共享死信队列）
 *
 * 队列类型：主队列不显式写 x-queue-type，由 broker 的 default_queue_type=quorum 生效；
 * 死信队列显式 classic，保留 OrderPaidDlqHandler 的 basic.get 重放能力。
 *
 * 队列有界化（security-hardening-plan.md P1-6.2，SR-7 防灌爆）：
 *   - 主队列 x-max-length=10000 + overflow=reject-publish：配合 publisher-confirm，
 *     积压经 basic.nack 反馈发布方，不让已认证发布者无界写盘；
 *   - DLQ x-max-length=10000 + x-message-ttl=7 天：死信投递是 best-effort，
 *     保持默认 drop-head（丢最旧保最新证据），TTL 到期自动释放磁盘。
 *
 *   迁移注记：arguments 变更对已存在的同名队列是 PRECONDITION_FAILED（RabbitMQ 等价性检查）。
 *   旧队列无界参数 → 部署本版前需一次性 delete_queue（q.order.paid.* 、 q.notify.* 、 q.attachment.*
 *   同参队列同理），demo 环境队列无留存价值，重建由 RabbitAdmin 自动完成。
 *
 * 消费端失败策略（application.yml spring.rabbitmq.listener.simple）：业务异常上抛
 * → spring-retry 3 次（1s/2s/4s）→ default-requeue-rejected=false 不回队列
 * → 经 lanlink.dlx 进 DLQ（R3）。quorum 默认 delivery-limit=20，与进程内 3 次重试不冲突。
 */
@Configuration
@ConditionalOnProperty(name = "app.mq.order-flow", havingValue = "mq", matchIfMissing = true)
public class RabbitConfig {

    /** 订单域 topic 交换机 */
    public static final String ORDER_EXCHANGE = "lanlink.order";
    /** 订单支付 routing key */
    public static final String ORDER_PAID_ROUTING_KEY = "order.paid";
    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "lanlink.dlx";
    /** 死信 routing key（两条消费队列统一路由到此） */
    public static final String ORDER_PAID_DLQ_ROUTING_KEY = "order.paid.dlq";
    /** 共享死信队列 */
    public static final String ORDER_PAID_DLQ = "q.order.paid.dlq";
    /** 会员积分消费者队列 */
    public static final String ORDER_PAID_MEMBERSHIP_QUEUE = "q.order.paid.membership";
    /** 消费有礼消费者队列 */
    public static final String ORDER_PAID_CAMPAIGN_QUEUE = "q.order.paid.campaign";
    /** 主队列/DLQ 消息条数上限（SG-62 断言口径） */
    public static final String MAX_LENGTH = "10000";
    /** DLQ 消息 TTL：7 天（毫秒），到期自动释放磁盘 */
    public static final String DLQ_MESSAGE_TTL = "604800000";

    private final Environment env;

    public RabbitConfig(Environment env) {
        this.env = env;
    }

    /**
     * 凭据 fail-fast（security-hardening-plan.md P1-1.3）：order-flow=mq 生效路径上
     * username/password 空白即拒绝启动——现状空串是 fail-closed 但静默连不上，
     * 显式化后缺失凭据在启动期就报错，而不是拖到第一次连接超时。
     * 仅挂在本配置类（event 回退模式无 broker，不误伤）。
     */
    @PostConstruct
    void requireMqCredentials() {
        String user = env.getProperty("spring.rabbitmq.username", "");
        String password = env.getProperty("spring.rabbitmq.password", "");
        if (!StringUtils.hasText(user) || !StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "RabbitMQ 凭据缺失（RABBITMQ_USER/RABBITMQ_PASSWORD 为空）而 app.mq.order-flow=mq，"
                            + "拒绝启动。请在 .env 配置凭据，或设 LANLINK_MQ_ORDER_FLOW=event 回退 Spring 事件通道。");
        }
    }

    @Bean
    public Declarables orderPaidTopology() {
        TopicExchange orderExchange = new TopicExchange(ORDER_EXCHANGE);
        DirectExchange dlxExchange = new DirectExchange(DLX_EXCHANGE);

        Queue membershipQueue = QueueBuilder.durable(ORDER_PAID_MEMBERSHIP_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ORDER_PAID_DLQ_ROUTING_KEY)
                .maxLength(Integer.parseInt(MAX_LENGTH))
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
        Queue campaignQueue = QueueBuilder.durable(ORDER_PAID_CAMPAIGN_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ORDER_PAID_DLQ_ROUTING_KEY)
                .maxLength(Integer.parseInt(MAX_LENGTH))
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
        Queue dlq = QueueBuilder.durable(ORDER_PAID_DLQ)
                .withArgument("x-queue-type", "classic")
                .withArgument("x-max-length", Integer.parseInt(MAX_LENGTH))
                .withArgument("x-message-ttl", Integer.parseInt(DLQ_MESSAGE_TTL))
                .build();

        return new Declarables(
                orderExchange,
                dlxExchange,
                membershipQueue,
                campaignQueue,
                dlq,
                BindingBuilder.bind(membershipQueue).to(orderExchange).with(ORDER_PAID_ROUTING_KEY),
                BindingBuilder.bind(campaignQueue).to(orderExchange).with(ORDER_PAID_ROUTING_KEY),
                BindingBuilder.bind(dlq).to(dlxExchange).with(ORDER_PAID_DLQ_ROUTING_KEY));
    }

    /**
     * JSON 消息转换器：发布侧与 @RabbitListener 消费侧共用（Boot 自动装配进 RabbitTemplate
     * 与 SimpleRabbitListenerContainerFactory）。消费类型推断优先监听方法签名（INFERRED），
     * 避免 __TypeId__ 头部路径下的跨类反序列化风险；信任包收敛到本项目 + JDK 常用包。
     */
    @Bean
    public Jackson2JsonMessageConverter orderPaidMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(DefaultJackson2JavaTypeMapper.TypePrecedence.INFERRED);
        typeMapper.setTrustedPackages("com.lanlink.shopping", "java.util", "java.lang");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}

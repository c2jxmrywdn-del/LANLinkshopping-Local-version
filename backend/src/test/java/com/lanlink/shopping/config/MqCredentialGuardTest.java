package com.lanlink.shopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RabbitMQ 凭据 fail-fast（security-hardening-plan.md P1-1.3，SG-12 配套）。
 *
 * order-flow=mq（默认）生效路径上 spring.rabbitmq.username/password 空白必须拒绝启动——
 * 现状空串是 fail-closed 但静默连不上（拖到首次连接超时才报错），显式化后缺失凭据启动期即报。
 * event 回退模式无 broker，不受影响（守卫挂在 RabbitConfig 的条件化配置类上）。
 */
class MqCredentialGuardTest {

    @Test
    void blankCredentialsRefuseToStartUnderMqFlow() {
        new ApplicationContextRunner()
                .withPropertyValues("app.mq.order-flow=mq")
                .withUserConfiguration(RabbitConfig.class)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "凭据空白应拒绝启动");
                    // @PostConstruct 异常被 ApplicationContextRunner 包成 BeanCreationException，取根因断言
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null && root.getCause() != root) {
                        root = root.getCause();
                    }
                    assertTrue(root instanceof IllegalStateException,
                            "应抛 IllegalStateException，实际: " + context.getStartupFailure());
                    assertTrue(String.valueOf(root.getMessage()).contains("RABBITMQ_USER"),
                            "错误信息应指向缺失的 env 变量组");
                });
    }

    @Test
    void configuredCredentialsStartAndDeclareTopology() {
        new ApplicationContextRunner()
                .withPropertyValues("app.mq.order-flow=mq",
                        "spring.rabbitmq.username=lanlink-mq", "spring.rabbitmq.password=secret")
                .withUserConfiguration(RabbitConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "凭据齐全应正常启动: " + context.getStartupFailure());
                    assertTrue(context.containsBean("orderPaidTopology"), "拓扑声明 Bean 应在位");
                    assertTrue(context.getBean(Declarables.class) != null);
                });
    }

    @Test
    void eventFallbackModeDoesNotRequireCredentials() {
        new ApplicationContextRunner()
                .withPropertyValues("app.mq.order-flow=event")
                .withUserConfiguration(RabbitConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "event 回退模式不加载 RabbitConfig，凭据无需校验");
                    assertTrue(context.getBeansOfType(RabbitConfig.class).isEmpty(),
                            "event 模式下 RabbitConfig 不应注册");
                });
    }
}

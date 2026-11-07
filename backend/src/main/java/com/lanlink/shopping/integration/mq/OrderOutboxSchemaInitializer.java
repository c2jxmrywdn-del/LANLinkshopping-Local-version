package com.lanlink.shopping.integration.mq;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 订单 outbox 幂等建表；与通知 outbox 独立，故障与清理互不影响。 */
@Component
@Order(2)
public class OrderOutboxSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public OrderOutboxSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_order_outbox (
              id BIGINT NOT NULL AUTO_INCREMENT,
              message_key VARCHAR(64) NOT NULL,
              payload TEXT NOT NULL,
              status TINYINT NOT NULL DEFAULT 0 COMMENT '0待投 1已投 2死态',
              retry_count INT NOT NULL DEFAULT 0,
              next_retry_at DATETIME NOT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_order_outbox_key (message_key),
              KEY idx_order_outbox_due (status, next_retry_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='订单支付事件投递箱（broker 不可用时补投）'
            """);
    }
}

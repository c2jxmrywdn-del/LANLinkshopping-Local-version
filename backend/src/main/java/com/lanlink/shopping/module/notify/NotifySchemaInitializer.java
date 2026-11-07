package com.lanlink.shopping.module.notify;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 通知去重表幂等建表。首次建库口径由 sql/schema.sql 负责，两处 DDL 必须一致。 */
@Component
@Order(1)
public class NotifySchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public NotifySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_notify_dedupe (
              id BIGINT NOT NULL AUTO_INCREMENT,
              message_key VARCHAR(64) NOT NULL,
              consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_notify_message_key (message_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='通知消费去重（at-least-once 投递下防重复发信）'
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_notify_outbox (
              id BIGINT NOT NULL AUTO_INCREMENT,
              message_key VARCHAR(64) NOT NULL,
              payload TEXT NOT NULL,
              status TINYINT NOT NULL DEFAULT 0 COMMENT '0待投 1已投 2死态',
              retry_count INT NOT NULL DEFAULT 0,
              next_retry_at DATETIME NOT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_notify_outbox_key (message_key),
              KEY idx_notify_outbox_due (status, next_retry_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='通知投递重试箱（提交后投递失败时暂存，worker 退避重投）'
            """);
    }
}

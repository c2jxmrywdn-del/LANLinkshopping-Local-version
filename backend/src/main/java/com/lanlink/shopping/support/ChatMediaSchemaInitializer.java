package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 聊天媒体消息列迁移：给 t_merchant_chat_msg 幂等补 5 列（msg_type/media_url/media_name/media_size/voice_seconds），
 * 存量库无需手工升级；首次建库由 sql/schema.sql 全量 DDL 负责。表不存在（如测试环境/未初始化）则跳过。
 */
@Component
@Order(1)
public class ChatMediaSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    public ChatMediaSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override
    public void run(ApplicationArguments args) {
        Long tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_merchant_chat_msg'",
                Long.class);
        if (tables == null || tables == 0) return; // 表不存在则跳过（首次建库口径走 schema.sql）
        addColumnIfMissing("t_merchant_chat_msg", "msg_type", "VARCHAR(16) NOT NULL DEFAULT 'text'");
        addColumnIfMissing("t_merchant_chat_msg", "media_url", "VARCHAR(300) NULL");
        addColumnIfMissing("t_merchant_chat_msg", "media_name", "VARCHAR(200) NULL");
        addColumnIfMissing("t_merchant_chat_msg", "media_size", "BIGINT NULL");
        addColumnIfMissing("t_merchant_chat_msg", "voice_seconds", "INT NULL");
    }

    /** 幂等加列：information_schema 检查后才 ALTER（MySQL 无 ADD COLUMN IF NOT EXISTS） */
    private void addColumnIfMissing(String table, String column, String definition) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}

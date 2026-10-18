package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class CustomerCommunicationSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    public CustomerCommunicationSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_conversation (
              id BIGINT NOT NULL AUTO_INCREMENT, visitor_token CHAR(36) NOT NULL,
              conversation_no VARCHAR(40) NOT NULL, customer_label VARCHAR(80) NOT NULL DEFAULT '网站访客',
              channel VARCHAR(20) NOT NULL DEFAULT 'web', subject VARCHAR(160) NOT NULL DEFAULT '专属客服咨询',
              category VARCHAR(32) NOT NULL DEFAULT '其他', status VARCHAR(24) NOT NULL DEFAULT 'open', priority VARCHAR(16) NOT NULL DEFAULT 'normal',
              assigned_admin_user_id BIGINT NULL, assigned_admin_name VARCHAR(80) NULL, unread_count INT NOT NULL DEFAULT 0,
              last_message_preview VARCHAR(500) NULL, last_message_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (id),
              UNIQUE KEY ux_support_visitor_token (visitor_token), UNIQUE KEY ux_support_conversation_no (conversation_no),
              KEY idx_support_status_last (status, last_message_at), KEY idx_support_assignee_status (assigned_admin_user_id, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_message (
              id BIGINT NOT NULL AUTO_INCREMENT, conversation_id BIGINT NOT NULL, sender_type VARCHAR(16) NOT NULL,
              sender_user_id BIGINT NULL, sender_name VARCHAR(80) NOT NULL, content TEXT NOT NULL, internal_note TINYINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (id), KEY idx_support_message_conversation (conversation_id, id), KEY idx_support_message_sender (sender_type, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_learning_candidate (
              id BIGINT NOT NULL AUTO_INCREMENT, conversation_id BIGINT NOT NULL, source_question_message_id BIGINT NOT NULL,
              source_answer_message_id BIGINT NOT NULL, entry_id VARCHAR(64) NOT NULL, question_text VARCHAR(300) NOT NULL,
              keywords_json TEXT NOT NULL, answer_text VARCHAR(1000) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'pending',
              reviewer_user_id BIGINT NULL, reviewer_name VARCHAR(80) NULL, review_note VARCHAR(500) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at DATETIME NULL, published_at DATETIME NULL,
              PRIMARY KEY (id), UNIQUE KEY ux_support_candidate_entry (entry_id), KEY idx_support_candidate_status (status, created_at), KEY idx_support_candidate_source (conversation_id, source_question_message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_ai_api_key (
              id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(80) NOT NULL, key_prefix VARCHAR(16) NOT NULL,
              key_hash VARCHAR(128) NOT NULL, provider VARCHAR(40) NOT NULL, permissions VARCHAR(500) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'active', expires_at DATETIME NULL, created_by BIGINT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, disabled_at DATETIME NULL, last_used_at DATETIME NULL,
              PRIMARY KEY (id), UNIQUE KEY ux_ai_key_hash (key_hash), KEY idx_ai_key_status_expiry (status, expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        // 出站模型渠道配置（后台可管，密钥 AES-GCM 加密存储、脱敏回显）
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_outbound_model_config (
              id BIGINT NOT NULL AUTO_INCREMENT, channel VARCHAR(20) NOT NULL,
              base_url VARCHAR(200) NOT NULL, api_key_cipher TEXT NOT NULL, api_key_prefix VARCHAR(16) NOT NULL,
              enabled TINYINT NOT NULL DEFAULT 1, updated_by BIGINT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id), UNIQUE KEY ux_outbound_channel (channel)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        // 功能开关（如 intent_external_enabled：外部模型可选意图增强）
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_feature_toggle (
              toggle_key VARCHAR(64) NOT NULL, enabled TINYINT NOT NULL DEFAULT 0,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (toggle_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        // 意图理解日志（本地/外部分类来源统计）
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_intent_log (
              id BIGINT NOT NULL AUTO_INCREMENT, question_hash CHAR(64) NOT NULL, intent VARCHAR(40) NOT NULL,
              source VARCHAR(16) NOT NULL DEFAULT 'local', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id), KEY idx_intent_log_time (created_at, intent)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        // 候选表迁移：自动学习候选无会话上下文（conversation/message id 改可空）、来源与问题哈希去重
        jdbcTemplate.execute("ALTER TABLE t_support_learning_candidate "
                + "MODIFY COLUMN conversation_id BIGINT NULL, "
                + "MODIFY COLUMN source_question_message_id BIGINT NULL, "
                + "MODIFY COLUMN source_answer_message_id BIGINT NULL");
        addColumnIfMissing("t_support_learning_candidate", "source", "VARCHAR(16) NOT NULL DEFAULT 'manual'");
        addColumnIfMissing("t_support_learning_candidate", "question_hash", "CHAR(64) NULL");
        addColumnIfMissing("t_outbound_model_config", "model_name", "VARCHAR(64) NULL");
        if (!indexExists("t_support_learning_candidate", "ux_candidate_qhash")) {
            jdbcTemplate.execute("ALTER TABLE t_support_learning_candidate "
                    + "ADD UNIQUE KEY ux_candidate_qhash (question_hash, status)");
        }
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

    private boolean indexExists(String table, String indexName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, table, indexName);
        return count != null && count > 0;
    }
}

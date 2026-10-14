package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent, additive schema bootstrap for the private support workspace.
 * No existing tables or business records are modified or deleted.
 */
@Component
@Order(1)
public class CustomerCommunicationSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public CustomerCommunicationSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_conversation (
              id BIGINT NOT NULL AUTO_INCREMENT,
              visitor_token CHAR(36) NOT NULL,
              conversation_no VARCHAR(40) NOT NULL,
              customer_label VARCHAR(80) NOT NULL DEFAULT '网站访客',
              channel VARCHAR(20) NOT NULL DEFAULT 'web',
              subject VARCHAR(160) NOT NULL DEFAULT '专属客服咨询',
              category VARCHAR(32) NOT NULL DEFAULT '其他',
              status VARCHAR(24) NOT NULL DEFAULT 'open',
              priority VARCHAR(16) NOT NULL DEFAULT 'normal',
              assigned_admin_user_id BIGINT NULL,
              assigned_admin_name VARCHAR(80) NULL,
              unread_count INT NOT NULL DEFAULT 0,
              last_message_preview VARCHAR(500) NULL,
              last_message_at DATETIME NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_support_visitor_token (visitor_token),
              UNIQUE KEY ux_support_conversation_no (conversation_no),
              KEY idx_support_status_last (status, last_message_at),
              KEY idx_support_assignee_status (assigned_admin_user_id, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_message (
              id BIGINT NOT NULL AUTO_INCREMENT,
              conversation_id BIGINT NOT NULL,
              sender_type VARCHAR(16) NOT NULL,
              sender_user_id BIGINT NULL,
              sender_name VARCHAR(80) NOT NULL,
              content TEXT NOT NULL,
              internal_note TINYINT NOT NULL DEFAULT 0,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              KEY idx_support_message_conversation (conversation_id, id),
              KEY idx_support_message_sender (sender_type, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_support_learning_candidate (
              id BIGINT NOT NULL AUTO_INCREMENT,
              conversation_id BIGINT NOT NULL,
              source_question_message_id BIGINT NOT NULL,
              source_answer_message_id BIGINT NOT NULL,
              entry_id VARCHAR(64) NOT NULL,
              question_text VARCHAR(300) NOT NULL,
              keywords_json TEXT NOT NULL,
              answer_text VARCHAR(1000) NOT NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'pending',
              reviewer_user_id BIGINT NULL,
              reviewer_name VARCHAR(80) NULL,
              review_note VARCHAR(500) NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              reviewed_at DATETIME NULL,
              published_at DATETIME NULL,
              PRIMARY KEY (id),
              UNIQUE KEY ux_support_candidate_entry (entry_id),
              KEY idx_support_candidate_status (status, created_at),
              KEY idx_support_candidate_source (conversation_id, source_question_message_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
    }
}

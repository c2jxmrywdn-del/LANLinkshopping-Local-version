package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 业务材料附件表幂等建表：商户资质与技术服务材料共用一张表，存量库无需手工升级。
 * 首次建库口径由 sql/schema.sql 全量 DDL 负责，两处 DDL 必须一致。
 */
@Component
@Order(1)
public class AttachmentSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public AttachmentSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_attachment (
              attachment_id BIGINT NOT NULL AUTO_INCREMENT,
              attach_no VARCHAR(40) NOT NULL,
              biz_type VARCHAR(32) NOT NULL,
              biz_id BIGINT NULL,
              owner_user_id BIGINT NOT NULL,
              file_name VARCHAR(200) NOT NULL,
              object_key VARCHAR(255) NOT NULL,
              content_type VARCHAR(64) NOT NULL,
              file_size BIGINT NOT NULL DEFAULT 0,
              sha256 CHAR(64) NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
              visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
              thumb_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                COMMENT 'PENDING 待处理 / READY 已生成 / SKIPPED 非图片 / FAILED 待重试',
              thumb_key VARCHAR(255) NULL,
              thumb_attempts INT NOT NULL DEFAULT 0,
              width INT NULL,
              height INT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (attachment_id),
              UNIQUE KEY ux_attachment_no (attach_no),
              UNIQUE KEY ux_attachment_object_key (object_key),
              KEY idx_attachment_owner_status (owner_user_id, status),
              KEY idx_attachment_biz (biz_type, biz_id, status),
              KEY idx_attachment_status_create (status, create_time),
              KEY idx_attachment_thumb_scan (thumb_status, attachment_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
              COMMENT='业务材料附件（商户资质/技术服务材料），库内只存 object key'
            """);

        // 存量库补派生列：MySQL 无 ADD COLUMN IF NOT EXISTS，必须先查 information_schema
        addColumnIfMissing("t_attachment", "thumb_status", "VARCHAR(16) NOT NULL DEFAULT 'PENDING'");
        addColumnIfMissing("t_attachment", "thumb_key", "VARCHAR(255) NULL");
        addColumnIfMissing("t_attachment", "thumb_attempts", "INT NOT NULL DEFAULT 0");
        addColumnIfMissing("t_attachment", "width", "INT NULL");
        addColumnIfMissing("t_attachment", "height", "INT NULL");
        addIndexIfMissing("t_attachment", "idx_attachment_thumb_scan", "(thumb_status, attachment_id)");
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

    /** 幂等加索引：补列扫描按 thumb_status 走，缺索引时扫描会退化成全表 */
    private void addIndexIfMissing(String table, String index, String columns) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD KEY " + index + " " + columns);
        }
    }
}

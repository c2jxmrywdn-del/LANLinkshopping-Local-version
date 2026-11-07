package com.lanlink.shopping.module.techservice.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P1 表结构：机构交付任务、履约评价、中试资源与预约（幂等建表）。
 * 新增四张表，并为既有机构表幂等补充 rating 列；不删除、不改写既有列。
 */
@Component
@Order(1)
public class TechP1SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public TechP1SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_org_task (
              task_id BIGINT NOT NULL AUTO_INCREMENT,
              task_no VARCHAR(40) NOT NULL,
              org_id BIGINT NOT NULL,
              project_id BIGINT NULL,
              assignee_user_id BIGINT NULL,
              task_type VARCHAR(24) NOT NULL DEFAULT 'EVALUATION',
              title VARCHAR(200) NOT NULL,
              scope VARCHAR(1000) NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',
              due_date DATE NULL,
              deliverable_text VARCHAR(2000) NULL,
              deliverable_hash CHAR(64) NULL,
              submitted_at DATETIME NULL,
              reviewed_at DATETIME NULL,
              review_note VARCHAR(500) NULL,
              created_by BIGINT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (task_id),
              UNIQUE KEY ux_tech_org_task_no (task_no),
              KEY idx_tech_org_task_assignee (assignee_user_id, status),
              KEY idx_tech_org_task_org (org_id, status),
              KEY idx_tech_org_task_project (project_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_review (
              review_id BIGINT NOT NULL AUTO_INCREMENT,
              review_no VARCHAR(40) NOT NULL,
              target_type VARCHAR(16) NOT NULL,
              target_id BIGINT NOT NULL,
              project_id BIGINT NOT NULL,
              reviewer_user_id BIGINT NOT NULL,
              score_response INT NOT NULL DEFAULT 5,
              score_professional INT NOT NULL DEFAULT 5,
              score_delivery INT NOT NULL DEFAULT 5,
              score_communication INT NOT NULL DEFAULT 5,
              score_result INT NOT NULL DEFAULT 5,
              overall DECIMAL(3,2) NOT NULL DEFAULT 0,
              comment VARCHAR(500) NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (review_id),
              UNIQUE KEY ux_tech_review_once (project_id, target_type, target_id, reviewer_user_id),
              KEY idx_tech_review_target (target_type, target_id),
              KEY idx_tech_review_reviewer (reviewer_user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_pilot_resource (
              resource_id BIGINT NOT NULL AUTO_INCREMENT,
              name VARCHAR(120) NOT NULL,
              org_name VARCHAR(120) NOT NULL,
              region VARCHAR(40) NULL,
              resource_type VARCHAR(24) NOT NULL DEFAULT 'PILOT',
              capabilities VARCHAR(500) NULL,
              equipment VARCHAR(500) NULL,
              price_mode VARCHAR(16) NOT NULL DEFAULT 'QUOTE',
              price DECIMAL(12,2) NULL,
              contact VARCHAR(80) NULL,
              status INT NOT NULL DEFAULT 1,
              sort INT NOT NULL DEFAULT 0,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (resource_id),
              KEY idx_tech_pilot_type_status (resource_type, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_pilot_booking (
              booking_id BIGINT NOT NULL AUTO_INCREMENT,
              booking_no VARCHAR(40) NOT NULL,
              resource_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              project_id BIGINT NULL,
              requirement VARCHAR(1000) NOT NULL,
              scheduled_date DATE NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
              admin_note VARCHAR(500) NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (booking_id),
              UNIQUE KEY ux_tech_pilot_booking_no (booking_no),
              KEY idx_tech_pilot_booking_user (user_id, status),
              KEY idx_tech_pilot_booking_resource (resource_id, status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        // 机构评分列（P1 履约评价聚合用），幂等补充
        addColumnIfMissing("t_tech_organization", "rating", "DECIMAL(3,2) NOT NULL DEFAULT 0");
    }

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

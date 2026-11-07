package com.lanlink.shopping.module.techservice.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P0 合同 / 服务费 / 资金监管 / 验收 表结构（幂等建表，启动自动迁移）。
 * 只新增表，不修改既有表结构。
 */
@Component
@Order(2)
public class TechP0SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public TechP0SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_contract (
              id BIGINT NOT NULL AUTO_INCREMENT,
              contract_no VARCHAR(40) NOT NULL,
              project_id BIGINT NULL,
              deal_id BIGINT NULL,
              template_code VARCHAR(32) NOT NULL,
              contract_type VARCHAR(24) NOT NULL,
              title VARCHAR(200) NOT NULL,
              party_a VARCHAR(120) NOT NULL,
              party_b VARCHAR(120) NOT NULL,
              amount DECIMAL(14,2) NOT NULL DEFAULT 0,
              commission_rate DECIMAL(5,2) NOT NULL DEFAULT 0,
              status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
              file_hash CHAR(64) NULL,
              sign_provider VARCHAR(40) NULL,
              sign_ref VARCHAR(64) NULL,
              signed_at DATETIME NULL,
              created_by BIGINT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_tech_contract_no (contract_no),
              KEY idx_tech_contract_project (project_id),
              KEY idx_tech_contract_creator_status (created_by, status),
              KEY idx_tech_contract_deal (deal_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_service_fee (
              id BIGINT NOT NULL AUTO_INCREMENT,
              fee_no VARCHAR(40) NOT NULL,
              contract_id BIGINT NOT NULL,
              contract_no VARCHAR(40) NOT NULL,
              project_id BIGINT NULL,
              payer_user_id BIGINT NOT NULL,
              fee_type VARCHAR(24) NOT NULL,
              amount DECIMAL(14,2) NOT NULL DEFAULT 0,
              status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
              pay_channel VARCHAR(32) NULL,
              external_ref VARCHAR(64) NULL,
              invoice_no VARCHAR(64) NULL,
              paid_at DATETIME NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_tech_service_fee_no (fee_no),
              KEY idx_tech_fee_payer_status (payer_user_id, status),
              KEY idx_tech_fee_contract (contract_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_escrow_ledger (
              id BIGINT NOT NULL AUTO_INCREMENT,
              escrow_no VARCHAR(40) NOT NULL,
              deal_id BIGINT NULL,
              contract_id BIGINT NULL,
              amount DECIMAL(14,2) NOT NULL DEFAULT 0,
              direction VARCHAR(8) NOT NULL,
              provider VARCHAR(64) NOT NULL,
              external_ref VARCHAR(64) NULL,
              status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
              occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_tech_escrow_no (escrow_no),
              KEY idx_tech_escrow_deal (deal_id),
              KEY idx_tech_escrow_contract (contract_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS t_tech_acceptance (
              id BIGINT NOT NULL AUTO_INCREMENT,
              acceptance_no VARCHAR(40) NOT NULL,
              project_id BIGINT NOT NULL,
              contract_id BIGINT NULL,
              result VARCHAR(16) NOT NULL,
              score INT NULL,
              note VARCHAR(500) NULL,
              evidence_hash CHAR(64) NULL,
              accepted_by BIGINT NULL,
              create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY ux_tech_acceptance_no (acceptance_no),
              KEY idx_tech_acceptance_project (project_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

        // 兼容既有建表：把通用主键 id 重命名为实体映射列（MyBatis-Plus 字段名 contract_id 等）
        ensureEntityIdColumn("t_tech_contract", "contract_id");
        ensureEntityIdColumn("t_tech_service_fee", "fee_id");
        ensureEntityIdColumn("t_tech_escrow_ledger", "ledger_id");
        ensureEntityIdColumn("t_tech_acceptance", "acceptance_id");
    }

    /** 旧版本以 id 建表；启动时幂等迁移为主键业务名，避免字段映射失败 */
    private void ensureEntityIdColumn(String table, String target) {
        if (!columnExists(table, target) && columnExists(table, "id")) {
            jdbcTemplate.execute("ALTER TABLE " + table + " CHANGE COLUMN id " + target
                    + " BIGINT NOT NULL AUTO_INCREMENT");
        }
    }

    private boolean columnExists(String table, String column) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class, table, column);
        return count != null && count > 0;
    }
}

package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商品自动审核表结构迁移（存量库幂等升级）：
 *  - t_product 补审核来源/自动评分/自动审核时间三列；
 *  - t_product_review_config 自动审核开关/阈值配置表；
 *  - t_product_review_record 自动审核留痕表。
 * 首次建库由 sql/schema.sql 全量 DDL 负责；t_product 不存在（测试环境/未初始化）时整体跳过。
 */
@Component
@Order(3)
public class ProductReviewSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ProductReviewSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long productTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product'",
                Long.class);
        if (productTables == null || productTables == 0) return;

        addColumnIfMissing("t_product", "review_source",
                "VARCHAR(16) DEFAULT 'MANUAL' COMMENT '审核来源 MANUAL人工 AUTO自动'");
        addColumnIfMissing("t_product", "auto_score", "INT NULL COMMENT '自动审核评分 0-100'");
        addColumnIfMissing("t_product", "auto_reviewed_at", "DATETIME NULL COMMENT '最近自动审核时间'");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_review_config (
                  config_key   VARCHAR(64) PRIMARY KEY,
                  config_value VARCHAR(255) NOT NULL,
                  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB COMMENT='商品自动审核配置'
                """);
        jdbcTemplate.update("INSERT IGNORE INTO t_product_review_config (config_key, config_value) VALUES "
                + "('auto_enabled','true'),('ai_enabled','true'),('vision_enabled','true'),"
                + "('auto_approve_score','85'),('daily_ai_budget','300')");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_review_record (
                  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                  prod_id     BIGINT NOT NULL COMMENT '商品ID',
                  decision    VARCHAR(24) NOT NULL COMMENT 'AUTO_APPROVE/AUTO_REJECT/MANUAL/SUGGEST',
                  source      VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL',
                  score       INT NULL COMMENT '综合评分 0-100',
                  rule_result TEXT NULL COMMENT '规则引擎结果 JSON',
                  ai_result   TEXT NULL COMMENT '文本AI结果 JSON',
                  vision_result TEXT NULL COMMENT '图像AI结果 JSON',
                  model       VARCHAR(64) NULL COMMENT '使用的模型/渠道',
                  elapsed_ms  INT NULL COMMENT '耗时毫秒',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_review_prod (prod_id, id)
                ) ENGINE=InnoDB COMMENT='商品自动审核记录'
                """);
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

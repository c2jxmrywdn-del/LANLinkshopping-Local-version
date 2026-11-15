package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SKU 多规格表结构迁移（存量库幂等升级，prod profile sql.init=never 时全靠本器）：
 *  - 新建 t_product_spec（规格维度）/ t_product_spec_value（规格值）/ t_product_sku（SKU 组合）。
 * 与 sql/schema.sql 双写一致；三条均为 CREATE TABLE IF NOT EXISTS，重复启动只跳过不改结构、不丢数据。
 * 紧随 RfqSchemaInitializer(@Order(20)) 之后执行，故 @Order(21)。
 * 三表无外键约束（与全库风格一致），商品归属/组合校验由 SkuService 在应用层保证。
 */
@Component
@Order(21)
public class SkuSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SkuSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_spec (
                  spec_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  prod_id BIGINT NOT NULL,
                  spec_name VARCHAR(20) NOT NULL,
                  sort_order INT NOT NULL DEFAULT 0,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_spec_prod (prod_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品规格维度'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_spec_value (
                  value_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  spec_id BIGINT NOT NULL,
                  prod_id BIGINT NOT NULL,
                  spec_value VARCHAR(40) NOT NULL,
                  sort_order INT NOT NULL DEFAULT 0,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_value_spec (spec_id), KEY idx_value_prod (prod_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品规格值'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_sku (
                  sku_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  prod_id BIGINT NOT NULL,
                  sku_code VARCHAR(64) NULL,
                  spec_value_ids VARCHAR(255) NOT NULL,
                  price DECIMAL(14,2) NOT NULL,
                  stock INT NOT NULL DEFAULT 0,
                  status TINYINT NOT NULL DEFAULT 1,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_sku_combo (prod_id, spec_value_ids),
                  UNIQUE KEY uk_sku_code (prod_id, sku_code),
                  KEY idx_sku_prod (prod_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SKU组合'
                """);
    }
}
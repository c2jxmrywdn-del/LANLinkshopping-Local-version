package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 买家评价两张表的存量库兜底建表（幂等）。
 *
 * <p>全新库由 `schema.sql` 建；已经在跑的库不会重放 schema.sql 的建表段，
 * 所以这里再按 `CREATE TABLE IF NOT EXISTS` 补一次。`t_order` 不存在（测试/未初始化）时整体跳过。
 */
@Component
@Order(3)
public class ReviewSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public ReviewSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long orderTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order'",
                Long.class);
        if (orderTables == null || orderTables == 0) return;

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_review (
                  review_id      BIGINT PRIMARY KEY AUTO_INCREMENT,
                  item_id        BIGINT NOT NULL COMMENT '订单行ID（一单一品一评）',
                  order_no       VARCHAR(40) NOT NULL COMMENT '订单号',
                  user_id        BIGINT NOT NULL COMMENT '评价人',
                  mer_id         BIGINT COMMENT '商户ID',
                  prod_id        BIGINT NOT NULL COMMENT '商品ID',
                  rating         TINYINT NOT NULL COMMENT '1-5 星',
                  content        VARCHAR(500) COMMENT '评价内容',
                  anonymous      TINYINT DEFAULT 0 COMMENT '1=匿名展示',
                  status         TINYINT DEFAULT 1 COMMENT '0待审 1通过 2隐藏',
                  merchant_reply VARCHAR(500) COMMENT '商家回复',
                  reply_time     DATETIME COMMENT '回复时间',
                  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_review_item (item_id),
                  KEY idx_review_prod (prod_id, status, create_time),
                  KEY idx_review_user (user_id)
                ) ENGINE=InnoDB COMMENT='买家商品评价'
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_product_rating_stat (
                  prod_id      BIGINT PRIMARY KEY,
                  review_count INT NOT NULL DEFAULT 0,
                  rating_sum   BIGINT NOT NULL DEFAULT 0,
                  good_count   INT NOT NULL DEFAULT 0 COMMENT '4-5 星数量',
                  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB COMMENT='商品评分聚合'
                """);
    }
}

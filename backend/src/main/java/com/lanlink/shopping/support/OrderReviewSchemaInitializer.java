package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单维度评价表 t_order_review 的存量库兜底建表（幂等）。
 *
 * <p>全新库由 `schema.sql` 建；已经在跑的库不会重放 schema.sql 的建表段，
 * 所以这里再按 `CREATE TABLE IF NOT EXISTS` 补一次。`t_order` 不存在（测试/未初始化）时整体跳过，
 * 与 {@link ReviewSchemaInitializer} 同一模式。
 */
@Component
@Order(3)
public class OrderReviewSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public OrderReviewSchemaInitializer(JdbcTemplate jdbcTemplate) {
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
                CREATE TABLE IF NOT EXISTS t_order_review (
                  review_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
                  order_id      VARCHAR(40) NOT NULL COMMENT '订单号(t_order.order_no)',
                  order_item_id BIGINT NOT NULL COMMENT '订单行ID(t_order_item.item_id)，一单多品拆行',
                  prod_id       BIGINT NOT NULL COMMENT '商品ID(t_order_item.prod_id 冗余)',
                  buyer_user_id BIGINT NOT NULL COMMENT '评价买家',
                  mer_id        BIGINT COMMENT '商户ID(t_order_item 商品归属商户)',
                  rating        TINYINT NOT NULL COMMENT '1-5 星',
                  content       VARCHAR(500) COMMENT '评价内容',
                  attachments   JSON COMMENT '评价图片/附件 JSON 数组',
                  is_anonymous  TINYINT DEFAULT 0 COMMENT '1=匿名展示',
                  status        TINYINT DEFAULT 1 COMMENT '0待审 1通过 2驳回',
                  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_order_review_item (order_id, order_item_id),
                  KEY idx_or_prod (prod_id, status, created_at),
                  KEY idx_or_buyer (buyer_user_id),
                  KEY idx_or_mer (mer_id, status, created_at)
                ) ENGINE=InnoDB COMMENT='订单维度评价'
                """);
    }
}

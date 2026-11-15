package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 库存流水表 {@code t_stock_flow} 的存量库兜底建表（幂等，P1-3 幂等基础设施）。
 *
 * <p>全新库由 {@code sql/schema.sql} 建；已在跑的库不会重放 schema.sql 的建表段，故这里以
 * {@code information_schema.TABLES} 守卫再补一次（表已存在则跳过，不执行任何 ALTER）。
 * 与 {@code RefundSchemaInitializer} / {@code OrderReviewSchemaInitializer} 同一启动期初始化模式：
 * 无 {@code t_order}（测试库/未初始化）时整体跳过，避免建出孤立表。
 *
 * <p>表结构：
 * <pre>
 *   t_stock_flow(flow_id, biz_type, biz_no, prod_id, order_item_id, quantity,
 *                order_no, operator_id, remark, create_time)
 *   UNIQUE KEY uk_stock_flow (biz_type, biz_no, prod_id, order_item_id)  -- 幂等键
 * </pre>
 */
@Component
@Order(5)
public class StockFlowSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockFlowSchemaInitializer.class);
    private static final String TABLE_NAME = "t_stock_flow";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS t_stock_flow (
              flow_id       BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '流水主键',
              biz_type      VARCHAR(24)  NOT NULL COMMENT '业务类型 ORDER_CANCEL/ORDER_CLOSE/FULL_REFUND/RMA_RESTOCK',
              biz_no        VARCHAR(64)  NOT NULL COMMENT '业务单号：订单号/售后单号/退款单号',
              prod_id       BIGINT       NOT NULL COMMENT '商品ID',
              order_item_id BIGINT       NOT NULL COMMENT '订单行ID(t_order_item.item_id)，幂等键组成部分（禁止NULL）',
              quantity      INT          NOT NULL COMMENT '变动数量：回补为正',
              order_no      VARCHAR(40)  DEFAULT NULL COMMENT '冗余订单号（审计/对账）',
              operator_id   BIGINT       DEFAULT NULL COMMENT '操作人（自动任务可空）',
              remark        VARCHAR(255) DEFAULT NULL COMMENT '备注/触发来源',
              create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_stock_flow (biz_type, biz_no, prod_id, order_item_id),
              KEY idx_stock_flow_order (order_no, prod_id),
              KEY idx_stock_flow_prod (prod_id, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='库存流水(幂等键+审计)'
            """;

    private final JdbcTemplate jdbc;

    public StockFlowSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long orderTables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order'",
                    Long.class);
            if (orderTables == null || orderTables == 0) {
                log.info("[stock-schema] t_order 不存在（测试库/未初始化），跳过 {} 建表", TABLE_NAME);
                return;
            }
            if (tableExists(TABLE_NAME)) {
                log.info("[stock-schema] {} 已存在，跳过建表", TABLE_NAME);
                return;
            }
            jdbc.execute(CREATE_TABLE_SQL);
            log.info("[stock-schema] {} 创建成功（唯一键 uk_stock_flow 生效）", TABLE_NAME);
        } catch (Exception e) {
            // 与既有 schema initializer 同口径：建表失败不阻断应用启动；运行期缺表由 SQL 报错暴露
            log.warn("[stock-schema] 初始化 {} 失败: {}", TABLE_NAME, e.getMessage());
        }
    }

    private boolean tableExists(String tableName) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long.class, tableName);
        return count != null && count > 0;
    }
}

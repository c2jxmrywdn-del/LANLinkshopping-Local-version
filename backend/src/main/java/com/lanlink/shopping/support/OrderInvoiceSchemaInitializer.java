package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单开票数据模型初始化器（P1-5 增值税发票需求随单快照）。
 * 幂等为 t_order 增加 invoice_* 前缀列（抬头类型/抬头名称/税号/内容类型/商品类别/开具明细），
 * 照 RefundSchemaInitializer 的 information_schema 检查 + ALTER ADD COLUMN 模式，
 * 重复启动只跳过不改结构、不丢数据；与 sql/schema.sql 双写一致。
 */
@Component
@Order(24)
public class OrderInvoiceSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderInvoiceSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public OrderInvoiceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[order-invoice-schema] 正在检查并初始化订单开票数据模型...");

        addColumnIfNotExists("t_order", "invoice_needed",
                "TINYINT DEFAULT 0 COMMENT '是否需要发票 0否 1是'");
        addColumnIfNotExists("t_order", "invoice_title_type",
                "VARCHAR(16) DEFAULT NULL COMMENT '抬头类型 personal个人/company单位'");
        addColumnIfNotExists("t_order", "invoice_title",
                "VARCHAR(100) DEFAULT NULL COMMENT '发票抬头名称'");
        addColumnIfNotExists("t_order", "invoice_tax_no",
                "VARCHAR(20) DEFAULT NULL COMMENT '纳税人识别号(15-20位大写字母数字)'");
        addColumnIfNotExists("t_order", "invoice_content_type",
                "VARCHAR(16) DEFAULT NULL COMMENT '发票内容 category商品类别/detail开具明细'");
        addColumnIfNotExists("t_order", "invoice_category",
                "VARCHAR(60) DEFAULT NULL COMMENT '商品类别(内容=category)'");
        addColumnIfNotExists("t_order", "invoice_detail",
                "VARCHAR(255) DEFAULT NULL COMMENT '开具明细(内容=detail)'");

        log.info("[order-invoice-schema] 订单开票数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                log.info("[order-invoice-schema] 已成功为表 {} 增加字段 {}", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[order-invoice-schema] 检查或添加表 {} 字段 {} 失败 (可忽略): {}", tableName, columnName, e.getMessage());
        }
    }
}

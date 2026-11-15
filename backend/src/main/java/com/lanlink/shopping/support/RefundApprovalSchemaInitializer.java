package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * P0-C 退款出款收口数据模型初始化器（幂等，仿 {@link RefundSchemaInitializer}）。
 *
 * <p>建两张东西：
 * <ol>
 *   <li>{@code t_refund_approval}：append-only 退款审批留痕（商户初审 / 平台复核 / 申诉重开），
 *       一行一次动作；平台 APPROVE 行带 {@code consumed} 使用标记，出款前原子消费，
 *       保证“一次平台复核只出一次款”。</li>
 *   <li>幂等给 {@code t_order_rma} 补 {@code platform_audit_*} 列：平台复核人/时间/意见/结论，
 *       供售后详情与审计回放（实体未映射，按字符串列写入）。</li>
 * </ol>
 *
 * <p>DDL 同时需要合并进 {@code backend/src/main/resources/sql/schema.sql}（见
 * {@code reports/impl/p0c-refund-gate.md} 的“待合并 DDL”章节）。本初始化器负责存量库幂等升级。
 */
@Component
@Order(5)
public class RefundApprovalSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RefundApprovalSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public RefundApprovalSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[refund-approval-schema] 正在检查并初始化退款审批留痕表与 RMA 平台复核列...");
        createApprovalTable();
        // t_order_rma 可能由 RefundSchemaInitializer(@Order(4)) 创建；本类 @Order(5) 在其后执行。
        addColumnIfNotExists("t_order_rma", "platform_audit_time",
                "DATETIME NULL COMMENT '平台退款复核时间'");
        addColumnIfNotExists("t_order_rma", "platform_auditor_id",
                "BIGINT NULL COMMENT '平台退款复核人用户ID'");
        addColumnIfNotExists("t_order_rma", "platform_audit_note",
                "VARCHAR(255) NULL COMMENT '平台退款复核意见/驳回原因'");
        addColumnIfNotExists("t_order_rma", "platform_audit_status",
                "VARCHAR(16) NULL COMMENT '平台复核结论 APPROVED/REJECTED/REOPENED'");
        log.info("[refund-approval-schema] 退款审批留痕数据模型初始化检查完成。");
    }

    private void createApprovalTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS t_refund_approval ("
                    + "  id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '审批动作主键',"
                    + "  rma_no        VARCHAR(32)  NULL COMMENT '关联售后单号(链A为NULL)',"
                    + "  order_no      VARCHAR(40)  NOT NULL COMMENT '关联订单号',"
                    + "  stage         VARCHAR(16)  NOT NULL COMMENT '阶段 MERCHANT商户初审/PLATFORM平台复核',"
                    + "  action        VARCHAR(16)  NOT NULL COMMENT '动作 APPROVE通过/REJECT驳回/REOPEN申诉重开',"
                    + "  operator_id   BIGINT       NOT NULL COMMENT '操作人用户ID',"
                    + "  operator_role VARCHAR(16)  NOT NULL COMMENT '操作人角色 MERCHANT/ADMIN/SYSTEM',"
                    + "  amount        DECIMAL(14,2) NULL COMMENT '本次审批锁定的退款金额(元)',"
                    + "  remark        VARCHAR(255) NULL COMMENT '审批意见/驳回原因/重开理由',"
                    + "  consumed      TINYINT      NOT NULL DEFAULT 0 COMMENT '平台APPROVE是否已出款消费 0否1是',"
                    + "  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '动作时间',"
                    + "  KEY idx_ra_order (order_no, stage, id),"
                    + "  KEY idx_ra_rma (rma_no, id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款审批留痕(append-only)'");
            log.info("[refund-approval-schema] t_refund_approval 表检查/创建成功。");
        } catch (Exception e) {
            log.warn("[refund-approval-schema] 创建 t_refund_approval 异常: {}", e.getMessage());
        }
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                log.info("[refund-approval-schema] 已为 {} 增加字段 {}", tableName, columnName);
            }
        } catch (Exception e) {
            // t_order_rma 尚未创建（全新库且 RefundSchemaInitializer 失败）时保持幂等：只告警不阻断启动
            log.warn("[refund-approval-schema] 检查或添加 {} 字段 {} 失败(可忽略): {}",
                    tableName, columnName, e.getMessage());
        }
    }
}

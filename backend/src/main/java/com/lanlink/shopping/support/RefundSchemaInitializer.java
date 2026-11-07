package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 退款模块数据模型与行级分摊初始化器 (P1 阶段数据模型扩展)。
 * 1. 扩容 t_order.refund_status 为 VARCHAR(32)，避免 merchant_approved (17字符) 截断溢出；
 * 2. 幂等为 t_order_item 增加行级退款状态与已退金额字段，为多商品拆项退款提供模型支撑。
 */
@Component
@Order(4)
public class RefundSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RefundSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public RefundSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[refund-schema] 正在检查并初始化退款模块与商品项退款数据模型...");

        // 1. 扩容 t_order.refund_status 列宽至 VARCHAR(32) 并补齐驳回原因字段
        try {
            jdbc.execute("ALTER TABLE t_order MODIFY COLUMN refund_status VARCHAR(32) DEFAULT 'none' COMMENT '退款状态'");
            log.info("[refund-schema] t_order.refund_status 成功调整为 VARCHAR(32)");
        } catch (Exception e) {
            log.warn("[refund-schema] 调整 t_order.refund_status 宽度异常 (可能已是目标长度或已更新): {}", e.getMessage());
        }
        addColumnIfNotExists("t_order", "refund_reject_reason", "VARCHAR(255) DEFAULT NULL COMMENT '退款申请驳回原因'");
        addColumnIfNotExists("t_order", "current_rma_no", "VARCHAR(32) DEFAULT NULL COMMENT '当前活跃RMA单号'");
        addColumnIfNotExists("t_order", "out_refund_no", "VARCHAR(64) DEFAULT NULL COMMENT '最近一次渠道退款请求单号'");
        addColumnIfNotExists("t_order", "refund_type", "VARCHAR(16) NOT NULL DEFAULT 'refund_only' COMMENT 'refund_only仅退款/return_goods退货退款'");
        addColumnIfNotExists("t_order", "return_waybill_no", "VARCHAR(64) DEFAULT NULL COMMENT '退货物流运单号'");
        addColumnIfNotExists("t_order", "return_carrier_name", "VARCHAR(64) DEFAULT NULL COMMENT '退货承运商名称'");
        addColumnIfNotExists("t_order", "return_receiver_name", "VARCHAR(64) DEFAULT NULL COMMENT '退货收件人姓名'");
        addColumnIfNotExists("t_order", "return_receiver_phone", "VARCHAR(32) DEFAULT NULL COMMENT '退货收件人电话'");
        addColumnIfNotExists("t_order", "return_warehouse_addr", "VARCHAR(255) DEFAULT NULL COMMENT '退货仓库地址'");
        addColumnIfNotExists("t_order", "inspect_status", "VARCHAR(16) DEFAULT NULL COMMENT '验货状态 passed/damaged'");
        addColumnIfNotExists("t_order", "inspect_result", "VARCHAR(255) DEFAULT NULL COMMENT '质检结论描述'");
        addColumnIfNotExists("t_order", "inspect_time", "DATETIME DEFAULT NULL COMMENT '验货入库时间'");
        // 链A（简单退款）独占证据列；链B（RMA）证据权威存 t_order_rma.proof_images，不做双写
        addColumnIfNotExists("t_order", "refund_evidences", "TEXT COMMENT '退款证据JSON[{type,url,name}](链A简单退款独占)'");

        // 2. 幂等为 t_order_item 增加行级退款跟踪字段
        addColumnIfNotExists("t_order_item", "refunded_quantity", "INT NOT NULL DEFAULT 0 COMMENT '已退款商品数量'");
        addColumnIfNotExists("t_order_item", "refund_amount", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '已退金额(含优惠行级分摊)'");
        addColumnIfNotExists("t_order_item", "refund_status", "VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT 'none未退款/applied申请中/refunded已退款'");

        // 3. 幂等为 t_logistics_waybill 扩展逆向运单标识
        addColumnIfNotExists("t_logistics_waybill", "waybill_direction", "VARCHAR(16) NOT NULL DEFAULT 'forward' COMMENT 'forward正向发货/reverse逆向退货'");
        addColumnIfNotExists("t_logistics_waybill", "rma_no", "VARCHAR(32) DEFAULT NULL COMMENT '关联RMA单号'");

        // 4. 幂等创建 t_order_rma 主表
        createRmaTableIfNotExists();

        // 5. 存量库证据列扩容 VARCHAR(1000) → TEXT（9 项证据满配约 4.7KB；新库建表即 TEXT，此处幂等兜底）
        modifyColumnToText("t_order_rma", "proof_images");
        modifyColumnToText("t_order_rma", "inspection_photos");

        log.info("[refund-schema] 退款模块数据模型与 RMA 逆向物流数据模型初始化检查完成。");
    }

    /** 存量库列扩容为 TEXT（照 :34 try-MODIFY-catch-warn 幂等模式，新库同型 MODIFY 为无操作） */
    private void modifyColumnToText(String tableName, String columnName) {
        try {
            jdbc.execute("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " TEXT");
            log.info("[refund-schema] {}.{} 已确认为 TEXT", tableName, columnName);
        } catch (Exception e) {
            log.warn("[refund-schema] 扩容 {}.{} 异常 (可能已是 TEXT 或权限不足): {}", tableName, columnName, e.getMessage());
        }
    }

    private void createRmaTableIfNotExists() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS t_order_rma (" +
                    "  rma_id               BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'RMA主键ID'," +
                    "  rma_no               VARCHAR(32) NOT NULL COMMENT 'RMA业务单号'," +
                    "  order_no             VARCHAR(40) NOT NULL COMMENT '关联主订单号'," +
                    "  user_id              BIGINT NOT NULL COMMENT '申请买家用户ID'," +
                    "  mer_id               BIGINT NOT NULL COMMENT '所属商户ID'," +
                    "  rma_type             VARCHAR(16) NOT NULL DEFAULT 'return_goods' COMMENT 'refund_only仅退款/return_goods退货退款'," +
                    "  status               VARCHAR(32) NOT NULL DEFAULT 'buyer_applied' COMMENT '状态'," +
                    "  apply_amount         DECIMAL(14,2) NOT NULL COMMENT '申请退款金额'," +
                    "  actual_refund_amount DECIMAL(14,2) DEFAULT 0.00 COMMENT '实际结算退款金额'," +
                    "  reason_category      VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT '原因分类'," +
                    "  apply_reason         VARCHAR(500) NOT NULL COMMENT '详细原因'," +
                    "  proof_images         TEXT COMMENT '凭证图片URL列表(证据JSON,经RefundEvidenceValidator归一)'," +
                    "  merchant_audit_time  DATETIME COMMENT '商户初审时间'," +
                    "  merchant_audit_note  VARCHAR(255) COMMENT '商户初审备注'," +
                    "  return_receiver_name VARCHAR(64) COMMENT '退货收件人姓名'," +
                    "  return_receiver_phone VARCHAR(32) COMMENT '退货收件人电话'," +
                    "  return_warehouse_addr VARCHAR(255) COMMENT '退货仓库地址'," +
                    "  return_provider_id   BIGINT COMMENT '逆向物流承运商ID'," +
                    "  return_carrier_name  VARCHAR(64) COMMENT '承运商名称'," +
                    "  return_waybill_no    VARCHAR(64) COMMENT '退货物流运单号'," +
                    "  return_waybill_id    BIGINT COMMENT '系统逆向运单ID'," +
                    "  return_ship_time     DATETIME COMMENT '买家寄出时间'," +
                    "  return_ship_remark   VARCHAR(255) COMMENT '买家寄出物流备注'," +
                    "  inspect_time         DATETIME COMMENT '验货时间'," +
                    "  inspector_id         BIGINT COMMENT '验货操作员ID'," +
                    "  inspection_status    VARCHAR(16) COMMENT 'passed合格/damaged破损'," +
                    "  inspection_result    VARCHAR(500) COMMENT '质检结论'," +
                    "  inspection_photos    TEXT COMMENT '质检拍照凭证'," +
                    "  is_restocked         TINYINT DEFAULT 0 COMMENT '是否已恢复入库: 0否 1是'," +
                    "  out_refund_no        VARCHAR(64) COMMENT '商户退款请求单号'," +
                    "  channel_refund_id    VARCHAR(64) COMMENT '渠道官方退款流水号'," +
                    "  refund_time          DATETIME COMMENT '退款到账时间'," +
                    "  create_time          DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "  update_time          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "  deleted              TINYINT DEFAULT 0," +
                    "  UNIQUE KEY uk_rma_no (rma_no)," +
                    "  KEY idx_rma_order (order_no)," +
                    "  KEY idx_rma_user (user_id)," +
                    "  KEY idx_rma_mer (mer_id)," +
                    "  KEY idx_rma_status (status)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='RMA退货退款售后主表'";
            jdbc.execute(sql);
            log.info("[refund-schema] t_order_rma 表检查/创建成功。");
        } catch (Exception e) {
            log.warn("[refund-schema] 创建 t_order_rma 异常 (可忽略): {}", e.getMessage());
        }
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                log.info("[refund-schema] 已成功为表 {} 增加字段 {}", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[refund-schema] 检查或添加表 {} 字段 {} 失败 (可忽略): {}", tableName, columnName, e.getMessage());
        }
    }
}

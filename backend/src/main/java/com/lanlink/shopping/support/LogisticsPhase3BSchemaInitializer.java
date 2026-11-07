package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 物流发货板块 Phase 3B 数据模型与表结构幂等初始化器：
 *  1. 创建承运商月结对账单主表 t_logistics_reconciliation
 *  2. 创建承运商月结对账逐单勾稽明细表 t_logistics_reconciliation_item
 *  3. 创建 6% 交通运输专用发票池表 t_logistics_invoice
 *  4. 扩容物流结算单主表 t_logistics_settlement 关联字段 (recon_id, invoice_id, invoice_status, amount_without_tax, tax_amount)
 */
@Component
@Order(6)
public class LogisticsPhase3BSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogisticsPhase3BSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public LogisticsPhase3BSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[logistics-phase3b-schema] 正在检查并初始化物流发货 Phase 3B (对账勾稽与6%专票核销) 数据表结构与扩展列...");

        // 1. 创建对账单主表
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS `t_logistics_reconciliation` (
                  `recon_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                  `recon_no` VARCHAR(32) NOT NULL COMMENT '对账单编号 REC+年月+序号',
                  `provider_id` BIGINT NOT NULL COMMENT '承运服务商ID',
                  `bill_period` VARCHAR(7) NOT NULL COMMENT '账单所属周期(如 2026-11)',
                  `total_waybills` INT NOT NULL DEFAULT 0 COMMENT '对账单总笔数',
                  `matched_count` INT NOT NULL DEFAULT 0 COMMENT '自动平账通过笔数',
                  `discrepancy_count` INT NOT NULL DEFAULT 0 COMMENT '存在差异/争议笔数',
                  `system_freight_total` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '系统预估运费总额',
                  `carrier_billed_total` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '承运商上报账单总额',
                  `diff_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '总差额(承运商 - 系统)',
                  `status` VARCHAR(24) NOT NULL DEFAULT 'imported' COMMENT 'imported已导入 / matching勾稽中 / discrepancy存在差异 / reconciled已平账 / closed已结案',
                  `remark` VARCHAR(255) DEFAULT NULL COMMENT '对账批次说明',
                  `created_by` BIGINT DEFAULT NULL COMMENT '导入操作人ID',
                  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_provider_period` (`provider_id`, `bill_period`),
                  KEY `idx_recon_status` (`status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='承运商月结对账单主表';
            """);
            log.info("[logistics-phase3b-schema] 检查/创建 t_logistics_reconciliation 成功");
        } catch (Exception e) {
            log.warn("[logistics-phase3b-schema] 创建 t_logistics_reconciliation 告警: {}", e.getMessage());
        }

        // 2. 创建对账明细表
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS `t_logistics_reconciliation_item` (
                  `item_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                  `recon_id` BIGINT NOT NULL COMMENT '对账单主表ID',
                  `waybill_no` VARCHAR(32) NOT NULL COMMENT '物流运单号',
                  `order_no` VARCHAR(32) DEFAULT NULL COMMENT '关联主订单号',
                  `waybill_id` BIGINT DEFAULT NULL COMMENT '关联系统运单ID',
                  `system_freight` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '系统记录运费',
                  `carrier_billed_freight` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '承运商申报运费',
                  `diff_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '单笔差异金额(承运商 - 系统)',
                  `diff_reason` VARCHAR(128) DEFAULT NULL COMMENT '差异原因分析(复磅超重/偏远加价/VAS漏记等)',
                  `match_status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'matched完全一致 / diff_tolerated容差平账 / disputed争议挂起 / resolved已调解 / missing_waybill系统无此单',
                  `epod_verified` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已关联e-POD公章验签: 0否 1是',
                  `epod_url` VARCHAR(512) DEFAULT NULL COMMENT '现场加盖公章回执照片地址',
                  `adjustment_amount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '人工调解冲差金额',
                  `adjust_note` VARCHAR(255) DEFAULT NULL COMMENT '调解核准说明',
                  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  KEY `idx_recon_item_main` (`recon_id`),
                  KEY `idx_recon_item_wb` (`waybill_no`),
                  KEY `idx_recon_item_status` (`match_status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='承运商对账逐单勾稽明细表';
            """);
            log.info("[logistics-phase3b-schema] 检查/创建 t_logistics_reconciliation_item 成功");
        } catch (Exception e) {
            log.warn("[logistics-phase3b-schema] 创建 t_logistics_reconciliation_item 告警: {}", e.getMessage());
        }

        // 3. 创建 6% 专票池表
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS `t_logistics_invoice` (
                  `invoice_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
                  `invoice_no` VARCHAR(32) NOT NULL COMMENT '发票号码',
                  `invoice_code` VARCHAR(32) NOT NULL COMMENT '发票代码',
                  `provider_id` BIGINT NOT NULL COMMENT '开票承运商ID',
                  `settlement_id` BIGINT DEFAULT NULL COMMENT '关联物流结算单ID(冲抵后绑定)',
                  `invoice_type` VARCHAR(20) NOT NULL DEFAULT 'special' COMMENT 'special专票 / normal普票',
                  `tax_rate` DECIMAL(4,2) NOT NULL DEFAULT 6.00 COMMENT '增值税税率百分比(交通运输业为6.00%)',
                  `amount_without_tax` DECIMAL(12,2) NOT NULL COMMENT '不含税金额(元)',
                  `tax_amount` DECIMAL(12,2) NOT NULL COMMENT '增值税税额(元)',
                  `total_amount` DECIMAL(12,2) NOT NULL COMMENT '价税合计总金额(元)',
                  `seller_name` VARCHAR(128) NOT NULL COMMENT '销方名称(承运物流企业)',
                  `seller_tax_no` VARCHAR(32) NOT NULL COMMENT '销方纳税人统一社会信用代码',
                  `buyer_name` VARCHAR(128) NOT NULL COMMENT '购方名称(平台运营实体)',
                  `buyer_tax_no` VARCHAR(32) NOT NULL COMMENT '购方统一社会信用代码',
                  `invoice_date` DATE NOT NULL COMMENT '开票日期',
                  `check_code` VARCHAR(32) DEFAULT NULL COMMENT '校验码/防伪码',
                  `file_url` VARCHAR(512) NOT NULL COMMENT '电子发票源文件地址(PDF/OFD)',
                  `verify_status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending待查验 / verified查验通过 / failed查验异常 / cancelled已作废',
                  `verify_time` DATETIME DEFAULT NULL COMMENT '税局查验通过时间',
                  `verify_message` VARCHAR(255) DEFAULT NULL COMMENT '税局查验返回反馈',
                  `offset_status` VARCHAR(20) NOT NULL DEFAULT 'unapplied' COMMENT 'unapplied未冲抵 / applied已冲抵',
                  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY `uk_invoice_code_no` (`invoice_code`, `invoice_no`),
                  KEY `idx_invoice_provider` (`provider_id`),
                  KEY `idx_invoice_settlement` (`settlement_id`),
                  KEY `idx_invoice_verify` (`verify_status`, `offset_status`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='物流运输增值税专用发票表';
            """);
            log.info("[logistics-phase3b-schema] 检查/创建 t_logistics_invoice 成功");
        } catch (Exception e) {
            log.warn("[logistics-phase3b-schema] 创建 t_logistics_invoice 告警: {}", e.getMessage());
        }

        // 4. 扩容结算单 t_logistics_settlement
        addColumnIfNotExists("t_logistics_settlement", "recon_id", "BIGINT DEFAULT NULL COMMENT '关联月结对账单ID'");
        addColumnIfNotExists("t_logistics_settlement", "invoice_id", "BIGINT DEFAULT NULL COMMENT '关联专票ID'");
        addColumnIfNotExists("t_logistics_settlement", "invoice_status", "VARCHAR(20) NOT NULL DEFAULT 'unbilled' COMMENT '开票状态: unbilled未开票 / billed已收票 / verified已核销'");
        addColumnIfNotExists("t_logistics_settlement", "amount_without_tax", "DECIMAL(12,2) DEFAULT NULL COMMENT '不含税金额'");
        addColumnIfNotExists("t_logistics_settlement", "tax_amount", "DECIMAL(12,2) DEFAULT NULL COMMENT '6%增值税进项税额'");

        log.info("[logistics-phase3b-schema] Phase 3B 表结构与扩展列初始化完成。");
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column
            );
            if (count == null || count == 0) {
                jdbc.execute(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", table, column, definition));
                log.info("[logistics-phase3b-schema] 成功为表 {} 增加字段 {}", table, column);
            }
        } catch (Exception e) {
            log.warn("[logistics-phase3b-schema] 检查/增加字段 {}.{} 告警: {}", table, column, e.getMessage());
        }
    }
}

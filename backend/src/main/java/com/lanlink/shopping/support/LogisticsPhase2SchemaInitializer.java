package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工业物流发货板块 Phase 2 数据模型幂等初始化器：
 *  1. 创建工业随货同行单证与质保证书表 t_logistics_document
 *  2. 创建工业现场数字 e-POD 电子回单存证表 t_logistics_pod_record
 *  3. 扩容 t_logistics_waybill 增加 CoA 与 e-POD 验签流转字段
 *  4. 扩容 t_logistics_template 增加阶梯计费与工业增值服务费率池
 */
@Component
@Order(4)
public class LogisticsPhase2SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogisticsPhase2SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public LogisticsPhase2SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[logistics-phase2-schema] 正在检查并初始化物流发货 Phase 2 (CoA/e-POD/大宗阶梯) 数据模型...");

        // 1. 创建工业随货同行单证表
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_logistics_document (
              doc_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              waybill_id BIGINT NOT NULL COMMENT '运单ID',
              waybill_no VARCHAR(32) NOT NULL COMMENT '运单号',
              order_no VARCHAR(40) NOT NULL COMMENT '订单号',
              doc_type VARCHAR(24) NOT NULL DEFAULT 'coa' COMMENT '单证类型: coa质检报告 mtr材质单 coc合格证 packing_list装箱单 spec技术规格书',
              doc_name VARCHAR(255) NOT NULL COMMENT '单证文件原始名称',
              file_url VARCHAR(500) NOT NULL COMMENT '文件存储相对/绝对路径',
              file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件字节大小',
              mime_type VARCHAR(64) NOT NULL DEFAULT 'application/pdf' COMMENT 'MIME类型',
              sha256_hash VARCHAR(64) COMMENT 'SHA-256文件哈希防伪指纹',
              batch_no VARCHAR(64) COMMENT '生产批次号/炉号/出厂序列号',
              uploader_id BIGINT COMMENT '上传操作人用户ID',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              KEY idx_ld_waybill (waybill_id),
              KEY idx_ld_order (order_no),
              KEY idx_ld_type (doc_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='工业物流随货同行单证与质保证书表';
            """);

        // 2. 创建工业现场 e-POD 电子回单存证表
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_logistics_pod_record (
              pod_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              waybill_id BIGINT NOT NULL COMMENT '运单ID',
              waybill_no VARCHAR(32) NOT NULL COMMENT '运单号',
              order_no VARCHAR(40) NOT NULL COMMENT '订单号',
              uploader_id BIGINT COMMENT '上传用户ID(买方/现场人员)',
              signer_name VARCHAR(32) NOT NULL COMMENT '现场签收人姓名',
              signer_phone VARCHAR(20) NOT NULL COMMENT '现场签收人电话',
              signed_time DATETIME NOT NULL COMMENT '现场实际签收时间',
              stamp_photo_url VARCHAR(500) NOT NULL COMMENT '加盖公章的纸质签收原件照片URL',
              cargo_photos_json TEXT COMMENT '现场货物外观照片JSON列表',
              geo_lng DECIMAL(10,6) COMMENT '签收打卡经度',
              geo_lat DECIMAL(10,6) COMMENT '签收打卡纬度',
              sign_remark VARCHAR(255) COMMENT '签收点验备注',
              audit_status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending待审核 approved验签合格 rejected已驳回',
              auditor_id BIGINT COMMENT '审核人用户ID',
              audit_note VARCHAR(255) COMMENT '审核批注/驳回原因',
              audit_time DATETIME COMMENT '审核处理时间',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              KEY idx_pod_waybill (waybill_id),
              KEY idx_pod_order (order_no),
              KEY idx_pod_status (audit_status)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='工业现场e-POD电子回单存证表';
            """);

        // 3. 幂等为 t_logistics_waybill 扩展 CoA 快照与 e-POD 验签流转字段
        addColumnIfNotExists("t_logistics_waybill", "coa_docs_json", "TEXT COMMENT '出厂检验报告/原厂质保书文件列表JSON'");
        addColumnIfNotExists("t_logistics_waybill", "pod_signed_by", "VARCHAR(32) COMMENT 'e-POD签收人'");
        addColumnIfNotExists("t_logistics_waybill", "pod_signed_phone", "VARCHAR(20) COMMENT 'e-POD签收人电话'");
        addColumnIfNotExists("t_logistics_waybill", "pod_signed_time", "DATETIME COMMENT 'e-POD现场签收时间'");
        addColumnIfNotExists("t_logistics_waybill", "pod_stamp_photo", "VARCHAR(500) COMMENT 'e-POD公章纸质签收原件照片URL'");
        addColumnIfNotExists("t_logistics_waybill", "pod_cargo_photos", "TEXT COMMENT 'e-POD货物外观照片JSON'");
        addColumnIfNotExists("t_logistics_waybill", "pod_audit_status", "VARCHAR(16) DEFAULT 'none' COMMENT 'none未传 pending待审 approved已验签 rejected已驳回'");
        addColumnIfNotExists("t_logistics_waybill", "pod_audit_note", "VARCHAR(255) COMMENT '验签审核意见'");
        addColumnIfNotExists("t_logistics_waybill", "pod_audited_by", "BIGINT COMMENT '验签审核人'");
        addColumnIfNotExists("t_logistics_waybill", "pod_audited_at", "DATETIME COMMENT '验签审核时间'");

        // 4. 幂等为 t_logistics_template 扩展阶梯费率与工业增值服务费池
        addColumnIfNotExists("t_logistics_template", "tier_mode", "VARCHAR(16) DEFAULT 'linear' COMMENT 'linear线性 tiered阶梯 ton纯吨位'");
        addColumnIfNotExists("t_logistics_template", "tier_rule_json", "TEXT COMMENT '大宗阶梯区间JSON'");
        addColumnIfNotExists("t_logistics_template", "vas_rule_json", "TEXT COMMENT '增值服务费率JSON'");
        addColumnIfNotExists("t_logistics_template", "min_charge", "DECIMAL(10,2) DEFAULT 0 COMMENT '最低起运价'");

        // 5. 大件模板阶梯/VAS/起价回填（out/carrier-expansion-design.md §1.3）：
        //    按 template_name + provider_code 业务键定位（不按自增 template_id，防撞库）；
        //    tier_rule_json 仍为空才写入（不覆盖管理端人工配置）；配置成功才置 status=1，
        //    模板在 schema.sql 阶段以 status=0 落库，避免"线性占位价"先上线后跳阶梯。
        //    全部 tiered 模板强制 min_charge>0：calculateTiered 对坏 JSON 静默返 0，
        //    保底金额兜住 ¥0 垄断不以坏 JSON 路径回归。
        backfillHeavyCargoTemplate("KYE", "跨越特快-大件限时", java.math.BigDecimal.valueOf(45),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.3,\"basePrice\":30}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.22,\"basePrice\":30}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.15,\"basePrice\":30}]",
                "{\"forklift\":{\"basePrice\":80,\"freeTons\":1,\"perTonPrice\":50}," +
                        "\"upstairs\":{\"basePrice\":50,\"perFloorPrice\":20}," +
                        "\"woodenCrate\":{\"pricePerM3\":120}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.2}}");
        backfillHeavyCargoTemplate("ANE", "安能零担-大件经济", java.math.BigDecimal.valueOf(25),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.25,\"basePrice\":15}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.2,\"basePrice\":15}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.15,\"basePrice\":15}]",
                "{\"forklift\":{\"basePrice\":60,\"freeTons\":1,\"perTonPrice\":45}," +
                        "\"upstairs\":{\"basePrice\":40,\"perFloorPrice\":15}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.15}}");
        backfillHeavyCargoTemplate("BEST", "百世快运-托盘档", java.math.BigDecimal.valueOf(30),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.3,\"basePrice\":20}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.25,\"basePrice\":20}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.18,\"basePrice\":20}]",
                "{\"forklift\":{\"basePrice\":70,\"freeTons\":1,\"perTonPrice\":40}," +
                        "\"woodenCrate\":{\"pricePerM3\":110}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.15}}");
        backfillHeavyCargoTemplate("SF", "顺丰卡航-大件零担", java.math.BigDecimal.valueOf(80),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.5,\"basePrice\":40}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.35,\"basePrice\":40}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.25,\"basePrice\":40}]",
                "{\"forklift\":{\"basePrice\":90,\"freeTons\":1,\"perTonPrice\":55}," +
                        "\"upstairs\":{\"basePrice\":60,\"perFloorPrice\":20}," +
                        "\"woodenCrate\":{\"pricePerM3\":130}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.2}}");
        backfillHeavyCargoTemplate("DB", "德邦重货-送装一体", java.math.BigDecimal.valueOf(55),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.45,\"basePrice\":30}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.32,\"basePrice\":30}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.2,\"basePrice\":30}]",
                "{\"forklift\":{\"basePrice\":80,\"freeTons\":1,\"perTonPrice\":50}," +
                        "\"upstairs\":{\"basePrice\":55,\"perFloorPrice\":20}," +
                        "\"woodenCrate\":{\"pricePerM3\":120}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.18}}");
        backfillHeavyCargoTemplate("ZT", "中通快运-电商大件", java.math.BigDecimal.valueOf(20),
                "[{\"minWeight\":0,\"maxWeight\":300,\"unitPrice\":0.35,\"basePrice\":10}," +
                        "{\"minWeight\":300,\"maxWeight\":1000,\"unitPrice\":0.25,\"basePrice\":10}," +
                        "{\"minWeight\":1000,\"maxWeight\":999999,\"unitPrice\":0.15,\"basePrice\":10}]",
                "{\"upstairs\":{\"basePrice\":40,\"perFloorPrice\":15}," +
                        "\"oversizeOverweight\":{\"surchargeRate\":0.15}}");

        log.info("[logistics-phase2-schema] 物流发货 Phase 2 数据模型初始化完成。");
    }

    /** 单个大件模板的阶梯/VAS/起价回填：空配置守卫 + 配置成功置启用 */
    private void backfillHeavyCargoTemplate(String providerCode, String templateName,
                                            java.math.BigDecimal minCharge, String tierRuleJson, String vasRuleJson) {
        try {
            int updated = jdbc.update("UPDATE t_logistics_template t JOIN t_logistics_provider p " +
                            "ON t.provider_id = p.provider_id " +
                            "SET t.tier_mode='tiered', t.tier_rule_json=?, t.vas_rule_json=?, t.min_charge=? " +
                            "WHERE p.provider_code=? AND t.template_name=? " +
                            "AND (t.tier_rule_json IS NULL OR t.tier_rule_json = '')",
                    tierRuleJson, vasRuleJson, minCharge, providerCode, templateName);
            if (updated > 0) {
                jdbc.update("UPDATE t_logistics_template t JOIN t_logistics_provider p " +
                                "ON t.provider_id = p.provider_id " +
                                "SET t.status=1 WHERE p.provider_code=? AND t.template_name=? " +
                                "AND t.tier_rule_json IS NOT NULL AND t.tier_rule_json != ''",
                        providerCode, templateName);
                log.info("[logistics-phase2-schema] 大件模板阶梯回填完成: {} ({})", templateName, providerCode);
            }
        } catch (Exception e) {
            log.warn("[logistics-phase2-schema] 大件模板 {} 回填略过: {}", templateName, e.getMessage());
        }
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDef) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDef);
                log.info("[logistics-phase2-schema] 表 {} 扩展新字段 {} 成功", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[logistics-phase2-schema] 检查或添加字段 {}.{} 略过: {}", tableName, columnName, e.getMessage());
        }
    }
}

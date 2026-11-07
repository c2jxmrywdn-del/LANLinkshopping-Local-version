package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企业账期深化优化与业财物流联动幂等初始化器 (Phase 3B Step 3)。
 * 1. 扩展 t_credit_account：增加第三方权威征信脱敏信用评级 risk_tier (AAA/AA/A/B)；
 * 2. 扩展 t_credit_bill：增加 bill_type (采购集采 / 运费月结担保)、settlement_no、freeze_status (在途免息预占 / 见POD起息)、
 *    pod_record_id、pod_approved_time、logistics_discount_rate；
 * 3. 扩展 t_logistics_settlement：增加 pay_method (direct / credit_guarantee) 与 credit_bill_id。
 */
@Component
@Order(7)
public class CreditTermPhase3BStep3SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CreditTermPhase3BStep3SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public CreditTermPhase3BStep3SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[credit-step3-schema] 正在检查并初始化 Phase 3B Step 3 极速账期与业财物流联动数据表扩展字段...");

        // 1. 扩展 t_credit_account
        addColumnIfNotExists("t_credit_account", "risk_tier", "VARCHAR(16) NOT NULL DEFAULT 'A' COMMENT '第三方脱敏信用评级(AAA/AA/A/B)'");

        // 2. 扩展 t_credit_bill
        addColumnIfNotExists("t_credit_bill", "bill_type", "VARCHAR(32) NOT NULL DEFAULT 'ORDER_PROCUREMENT' COMMENT '账单类型 ORDER_PROCUREMENT采购 / LOGISTICS_FREIGHT运费月结担保'");
        addColumnIfNotExists("t_credit_bill", "settlement_no", "VARCHAR(64) DEFAULT NULL COMMENT '关联合同/物流结算单号'");
        addColumnIfNotExists("t_credit_bill", "freeze_status", "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '冻结状态 FROZEN在途免息预占 / ACTIVE见POD生效起息'");
        addColumnIfNotExists("t_credit_bill", "pod_record_id", "BIGINT DEFAULT NULL COMMENT '挂接现场公章e-POD回执ID'");
        addColumnIfNotExists("t_credit_bill", "pod_approved_time", "DATETIME DEFAULT NULL COMMENT '现场加盖公章验签通过时间(起息锚点)'");
        addColumnIfNotExists("t_credit_bill", "logistics_discount_rate", "DECIMAL(6,4) NOT NULL DEFAULT 0.0000 COMMENT '物流信誉利率折扣'");

        // 3. 扩展 t_logistics_settlement
        addColumnIfNotExists("t_logistics_settlement", "pay_method", "VARCHAR(32) DEFAULT NULL COMMENT '支付方式 direct直付 / credit_guarantee账期担保'");
        addColumnIfNotExists("t_logistics_settlement", "credit_bill_id", "BIGINT DEFAULT NULL COMMENT '关联账期账单ID'");

        log.info("[credit-step3-schema] Phase 3B Step 3 极速账期与业财物流联动数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName
            );
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition);
                log.info("[credit-step3-schema] 数据表 {} 成功添加字段 {}", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[credit-step3-schema] 检查/添加字段 `{}`.`{}` 略过: {}", tableName, columnName, e.getMessage());
        }
    }
}

package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企业账期合规与计息模型幂等初始化器 (Phase 1 基础数据模型扩展)。
 * 1. 落实第三方权威征信合规解耦：引入 credit_grant_token, assessment_ref_no, valid_until，平台零留存征信原始报告；
 * 2. 引入商业化利率与免息宽限期：daily_rate, apr, grace_days；
 * 3. 账单分项计息本息分离：principal_amount, interest_amount, penalty_amount 等。
 */
@Component
@Order(4)
public class CreditTermPhase1SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CreditTermPhase1SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public CreditTermPhase1SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[credit-schema] 正在检查并初始化企业账期合规与利息分项数据模型...");

        // 1. 扩展 t_credit_account
        addColumnIfNotExists("t_credit_account", "credit_grant_token", "VARCHAR(128) COMMENT '第三方征信授权Token(合规解耦)'");
        addColumnIfNotExists("t_credit_account", "assessment_ref_no", "VARCHAR(64) COMMENT '第三方信用评估流水号'");
        addColumnIfNotExists("t_credit_account", "valid_until", "DATETIME COMMENT '第三方授信额度有效截止期'");
        addColumnIfNotExists("t_credit_account", "daily_rate", "DECIMAL(8,6) NOT NULL DEFAULT 0.000300 COMMENT '基准日利率(如0.000300=0.03%/天)'");
        addColumnIfNotExists("t_credit_account", "apr", "DECIMAL(8,4) NOT NULL DEFAULT 0.1080 COMMENT '基准单利年化利率(如0.1080=10.80%)'");
        addColumnIfNotExists("t_credit_account", "grace_days", "INT NOT NULL DEFAULT 3 COMMENT '免息宽限期(天)'");

        // 2. 扩展 t_credit_bill
        addColumnIfNotExists("t_credit_bill", "principal_amount", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账单应还本金'");
        addColumnIfNotExists("t_credit_bill", "interest_amount", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账单应还正常利息'");
        addColumnIfNotExists("t_credit_bill", "paid_interest", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账单已还利息'");
        addColumnIfNotExists("t_credit_bill", "penalty_amount", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账单逾期罚息'");
        addColumnIfNotExists("t_credit_bill", "paid_penalty", "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账单已还逾期罚息'");
        addColumnIfNotExists("t_credit_bill", "daily_rate", "DECIMAL(8,6) NOT NULL DEFAULT 0.000300 COMMENT '计息执行日利率'");
        addColumnIfNotExists("t_credit_bill", "apr", "DECIMAL(8,4) NOT NULL DEFAULT 0.1080 COMMENT '计息执行年化利率'");
        addColumnIfNotExists("t_credit_bill", "interest_start_date", "DATE COMMENT '计息起始日'");
        addColumnIfNotExists("t_credit_bill", "last_accrual_date", "DATE COMMENT '上次计息跑批日期'");

        // 3. 存量历史账单数据平滑回填
        try {
            jdbc.execute("UPDATE t_credit_bill SET principal_amount = amount WHERE (principal_amount IS NULL OR principal_amount = 0) AND amount > 0");
        } catch (Exception e) {
            log.warn("[credit-schema] 回填存量账单本金略过: {}", e.getMessage());
        }

        log.info("[credit-schema] 企业账期合规与利息分项数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDef) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDef);
                log.info("[credit-schema] 表 {} 扩展新字段 {} 成功", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[credit-schema] 检查或添加字段 {}.{} 略过: {}", tableName, columnName, e.getMessage());
        }
    }
}

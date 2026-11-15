package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商户提现与财务台账表结构迁移（存量库幂等升级，prod profile sql.init=never 时全靠本器）：
 *  - 新建 t_merchant_withdrawal（提现单，UNIQUE(withdraw_no)，状态机 0待审/1已打款/2驳回/3已撤回）；
 *  - 新建 t_finance_ledger（append-only 台账，每笔资金变动一行，balance_after 落行时计算）。
 *
 * <p>与 sql/schema.sql 双写一致；两条 CREATE 均 IF NOT EXISTS，重复启动只跳过不改结构、不丢数据。
 * 排在 SettlementSchemaInitializer(@Order(23)) 之后，故 @Order(24)：提现余额聚合依赖结算台账已建。
 * 两表无外键约束（与全库风格一致），归属一致性由应用层（WithdrawalService）保证。
 */
@Component
@Order(24)
public class FinanceSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public FinanceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_withdrawal (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  mer_id BIGINT NOT NULL,
                  withdraw_no VARCHAR(40) NOT NULL,
                  amount DECIMAL(14,2) NOT NULL COMMENT '申请金额',
                  balance_snapshot DECIMAL(14,2) NOT NULL COMMENT '申请时点可提余额快照',
                  bank_account_name VARCHAR(64) NOT NULL COMMENT '收款账户户名（对公）',
                  bank_account_no_masked VARCHAR(64) NOT NULL COMMENT '银行账号掩码（仅前4后4，全号不入库）',
                  bank_name VARCHAR(100) NOT NULL COMMENT '开户银行全称（含支行）',
                  status TINYINT NOT NULL DEFAULT 0 COMMENT '0待审 1已打款 2驳回 3已撤回',
                  apply_note VARCHAR(255) NULL,
                  reject_reason VARCHAR(255) NULL,
                  reviewed_by BIGINT NULL COMMENT '审核运营 userId',
                  reviewed_at DATETIME NULL,
                  voucher_url VARCHAR(255) NULL COMMENT '打款凭证（附件中转 URL）',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_withdraw_no (withdraw_no),
                  KEY idx_wd_mer_status (mer_id, status),
                  KEY idx_wd_status (status, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户提现单'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_finance_ledger (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  mer_id BIGINT NOT NULL,
                  biz_type VARCHAR(32) NOT NULL COMMENT 'earn/settle/withdraw_freeze/withdraw_paid/withdraw_reject_unrefreeze',
                  biz_no VARCHAR(40) NOT NULL COMMENT '业务单号（提现单号）',
                  amount DECIMAL(14,2) NOT NULL COMMENT '正入负出',
                  balance_after DECIMAL(14,2) NOT NULL COMMENT '该笔后余额快照',
                  ref_order_id BIGINT NULL,
                  remark VARCHAR(255) NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_ledger_mer (mer_id, id),
                  KEY idx_ledger_biz (biz_type, biz_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户财务台账(append-only)'
                """);
    }
}

package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商户结算账本表结构迁移（存量库幂等升级，prod profile sql.init=never 时全靠本器）：
 *  - 新建 t_merchant_earning（收益台账，UNIQUE(order_no,item_id,type,refund_seq) 保证记入/冲正幂等）；
 *  - 新建 t_merchant_settlement（结算批次，UNIQUE(settle_no)）；
 *  - 新建 t_merchant_settlement_item（批次明细，UNIQUE(settle_id,earning_id) 保证同一收益行不重复纳入）；
 *  - t_merchant 补 commission_rate 列（商户级佣金率覆盖，NULL 表示走 app.settlement 全局默认）。
 *
 * <p>与 sql/schema.sql 双写一致；三条 CREATE 均 IF NOT EXISTS，重复启动只跳过不改结构、不丢数据。
 * 加列走 information_schema 守卫，故重启任意次列数恒为 1。
 *
 * <p>排在 TrafficErrorSchemaInitializer(@Order(22)) 之后，故 @Order(23)。
 * 三表无外键约束（与全库风格一致），收益归属/批次纳入的一致性由应用层（SettlementSyncService 等）保证。
 */
@Component
@Order(23)
public class SettlementSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettlementSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SettlementSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_earning (
                  earning_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  mer_id BIGINT NOT NULL,
                  order_no VARCHAR(40) NOT NULL,
                  item_id BIGINT NOT NULL,
                  prod_id BIGINT NOT NULL,
                  type VARCHAR(8) NOT NULL COMMENT 'EARN正向 REVERSE冲正',
                  refund_seq INT NOT NULL DEFAULT 0 COMMENT 'EARN=0；REVERSE第n次退款=n',
                  gross_amount DECIMAL(14,2) NOT NULL,
                  commission_rate DECIMAL(5,2) NOT NULL,
                  commission_amount DECIMAL(14,2) NOT NULL,
                  net_amount DECIMAL(14,2) NOT NULL,
                  status VARCHAR(12) NOT NULL COMMENT 'PENDING/AVAILABLE/SETTLED/REVERSED',
                  available_at DATETIME NULL,
                  ref_earning_id BIGINT NULL,
                  order_subtotal DECIMAL(14,2) NOT NULL,
                  order_total DECIMAL(14,2) NOT NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_earning_line (order_no, item_id, type, refund_seq),
                  KEY idx_earning_mer_status (mer_id, status),
                  KEY idx_earning_order (order_no)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户收益台账'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_settlement (
                  settle_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  settle_no VARCHAR(40) NOT NULL,
                  mer_id BIGINT NOT NULL,
                  period_start DATETIME,
                  period_end DATETIME,
                  total_net DECIMAL(14,2) NOT NULL,
                  commission_total DECIMAL(14,2) NOT NULL,
                  item_count INT NOT NULL,
                  status VARCHAR(12) NOT NULL COMMENT 'DRAFT/APPROVED/PAID',
                  pay_voucher VARCHAR(128) NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_settle_no (settle_no),
                  KEY idx_settle_mer (mer_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户结算批次'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_settlement_item (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  settle_id BIGINT NOT NULL,
                  earning_id BIGINT NOT NULL,
                  order_no VARCHAR(40) NOT NULL,
                  item_id BIGINT NOT NULL,
                  type VARCHAR(8) NOT NULL,
                  net_amount DECIMAL(14,2) NOT NULL,
                  UNIQUE KEY uk_settle_item (settle_id, earning_id),
                  KEY idx_sitem_settle (settle_id),
                  KEY idx_sitem_earning (earning_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结算批次明细'
                """);

        addColumnIfMissing("t_merchant", "commission_rate",
                "DECIMAL(5,2) NULL COMMENT '平台佣金率(%)，NULL=用全局默认'");

        // P0-A：收益行终态 PAID 的存量回填（幂等：只推进仍停在 SETTLED/REVERSED 的行）
        backfillPaidEarningStatus();
    }

    /**
     * 幂等回填：已登记线下打款（批次 status='PAID'）的收益行，从 {@code SETTLED/REVERSED} 推进到终态
     * {@code PAID}。PAID 收益行不计入可提余额（见 WithdrawalService 类注释），不发此回填会让历史
     * PAID 批次的收益继续留在「可提」口径里 —— 同一笔钱可被再次提现（结算双付）。
     *
     * <p>只按「批次已 PAID + 收益行仍非 PAID」匹配，重复执行影响 0 行；失败仅告警不阻断启动
     * （与全库 SchemaInitializer 的「迁移不得阻断应用启动」约定一致）。
     */
    private void backfillPaidEarningStatus() {
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE t_merchant_earning e
                    JOIN t_merchant_settlement_item si ON si.earning_id = e.earning_id
                    JOIN t_merchant_settlement s ON s.settle_id = si.settle_id
                    SET e.status = 'PAID'
                    WHERE s.status = 'PAID' AND e.status IN ('SETTLED','REVERSED')
                    """);
            log.info("[settlement-schema] PAID 批次收益行幂等回填完成: 影响 {} 行", updated);
        } catch (Exception e) {
            log.warn("[settlement-schema] PAID 批次收益行回填略过: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Long.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}

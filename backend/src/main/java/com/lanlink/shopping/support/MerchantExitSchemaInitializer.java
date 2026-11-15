package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商户退店表结构迁移（存量库幂等升级）：
 *  - 建 t_merchant_deposit（保证金台账，append-only）与 t_merchant_exit_log（流转轨迹）；
 *  - t_merchant 补六个退店列；t_order 补 finish_time（时间闸门唯一可靠口径）并回填存量。
 *
 * <p>@Order(0) —— 必须早于 DataInitializer(@Order1)：后者会 select One Merchant 实体，
 * 实体已含退店字段，存量库若先读后加列会 Unknown column（结算 commission_rate 同款教训）。
 * 建表/加列全部 IF NOT EXISTS / information_schema 守卫，重启任意次结构不变。
 * 与 sql/schema.sql 双写一致（prod profile sql.init=never 时全靠本器）。
 */
@Component
@Order(0)
public class MerchantExitSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public MerchantExitSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_deposit (
                  deposit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  mer_id BIGINT NOT NULL,
                  type VARCHAR(12) NOT NULL COMMENT 'PAY缴纳 REFUND退还 DEDUCT抵扣 FORFEIT罚没',
                  amount DECIMAL(14,2) NOT NULL COMMENT '正数；方向由type决定',
                  balance_after DECIMAL(14,2) NOT NULL COMMENT '该笔后余额快照',
                  remark VARCHAR(255) NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_deposit_mer (mer_id, deposit_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户保证金台账(append-only)'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_merchant_exit_log (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  mer_id BIGINT NOT NULL,
                  from_status VARCHAR(20) NULL,
                  to_status VARCHAR(20) NOT NULL,
                  actor_id BIGINT NULL,
                  actor_role VARCHAR(16) NULL,
                  detail VARCHAR(500) NULL,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_exitlog_mer (mer_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户退店流转轨迹'
                """);

        addColumnIfMissing("t_merchant", "exit_status", "VARCHAR(20) NULL COMMENT '退店状态 NULL=正常经营'");
        addColumnIfMissing("t_merchant", "exit_request_time", "DATETIME NULL COMMENT '退店申请时间'");
        addColumnIfMissing("t_merchant", "notice_end_time", "DATETIME NULL COMMENT '公示截止时间'");
        addColumnIfMissing("t_merchant", "close_time", "DATETIME NULL COMMENT '正式关闭时间'");
        addColumnIfMissing("t_merchant", "exit_reason", "VARCHAR(255) NULL COMMENT '退店原因'");
        addColumnIfMissing("t_merchant", "exit_reject_reason", "VARCHAR(255) NULL COMMENT '退店驳回原因'");
        addColumnIfMissing("t_order", "finish_time",
                "DATETIME NULL COMMENT '订单完成时刻(order_status置2时原子写入;时间闸门唯一可靠口径)'");

        // 存量回填：优先运单签收时间，无运单回落 update_time；显式自赋值 update_time 钉住
        // ON UPDATE CURRENT_TIMESTAMP，避免回填本身把历史 update_time 刷成今天（实测过漂移）。
        //
        // 运单表守卫：t_logistics_waybill 只由 schema.sql 创建（无 Java 初始化器负责），
        // prod 下 sql.init.mode=never 时该表可能不存在；此时 JOIN 会让启动硬失败，
        // 故降级为纯 update_time 回填（口径退化为近似值，但不阻断启动）。
        if (tableExists("t_logistics_waybill")) {
            jdbcTemplate.update("""
                    UPDATE t_order o
                       SET o.finish_time = COALESCE(
                             (SELECT MAX(w.delivered_time) FROM t_logistics_waybill w
                               WHERE w.order_no COLLATE utf8mb4_unicode_ci = o.order_no COLLATE utf8mb4_unicode_ci
                                 AND w.delivered_time IS NOT NULL),
                             o.update_time),
                           o.update_time = o.update_time
                     WHERE o.order_status = 2 AND o.finish_time IS NULL
                    """);
        } else {
            jdbcTemplate.update("""
                    UPDATE t_order o
                       SET o.finish_time = o.update_time, o.update_time = o.update_time
                     WHERE o.order_status = 2 AND o.finish_time IS NULL
                    """);
        }
    }

    private boolean tableExists(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long.class, table);
        return count != null && count > 0;
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

package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 订阅闭环（P1）迁移（存量库无需手工升级）：
 *  - t_subscription_order 订阅订单表在存量库缺失时补建（DDL 与 sql/schema.sql 一致；
 *    金额 amount_cents 为「整数元」语义，与 t_subscription_plan.price_cents 同源）；
 *  - t_support_ticket 幂等补 priority 列（D7 订阅专属客服通道优先级标记，存量行回填 NORMAL）。
 * 首次建库由 sql/schema.sql 全量 DDL 负责；t_user_subscription 不存在（测试环境/未初始化）则整体跳过。
 */
@Component
@Order(2)
public class SubscriptionSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long subTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user_subscription'",
                Long.class);
        if (subTables == null || subTables == 0) return; // 订阅域未初始化则跳过（首次建库口径走 schema.sql）

        Long orderTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_subscription_order'",
                Long.class);
        if (orderTables == null || orderTables == 0) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_subscription_order ("
                    + "order_no VARCHAR(32) PRIMARY KEY COMMENT '订阅单号(SUB+yyyyMMdd+8位序列)', "
                    + "user_id BIGINT NOT NULL COMMENT '用户ID', "
                    + "plan_id BIGINT NOT NULL COMMENT '订阅方案ID', "
                    + "plan_code VARCHAR(32) COMMENT '方案编码快照', "
                    + "plan_name VARCHAR(32) COMMENT '方案名称快照', "
                    // 金额单位钉死：整数元（8888=¥8,888），与 price_cents 同语义，渠道「分」换算统一在 PaymentService
                    + "amount_cents INT NOT NULL DEFAULT 0 COMMENT '实付金额(整数元, 8888=¥8,888)', "
                    + "duration_days INT NOT NULL DEFAULT 0 COMMENT '有效天数快照(方案下架后结算仍可依此顺延)', "
                    + "auto_renew TINYINT DEFAULT 0 COMMENT '下单时方案 auto_renew 快照(0单次 1自动续费)', "
                    + "pay_status TINYINT DEFAULT 0 COMMENT '0待支付 1已支付 2已取消(支付状态机)', "
                    + "channel VARCHAR(16) COMMENT '支付渠道 wallet/mock/wechat/alipay/stripe', "
                    + "transaction_id VARCHAR(64) COMMENT '渠道交易号', "
                    + "paid_at DATETIME COMMENT '支付完成时间', "
                    + "apply_status TINYINT DEFAULT 0 COMMENT '0未生效 1已驱动订阅生效/顺延(回调重放幂等闸)', "
                    + "renew_sub_id BIGINT COMMENT '自动续费来源订阅ID(手动购买为 NULL)', "
                    + "retry_count INT NOT NULL DEFAULT 0 COMMENT '自动续费扣款失败次数(达上限置 cancelled)', "
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "KEY idx_subord_user (user_id, created_at), "
                    + "KEY idx_subord_pay (pay_status), "
                    + "KEY idx_subord_renew (renew_sub_id, pay_status)"
                    + ") ENGINE=InnoDB COMMENT='订阅订单（购买/续费流水）'");
        }

        addColumnIfMissing("t_support_ticket", "priority",
                "VARCHAR(8) NOT NULL DEFAULT 'NORMAL' COMMENT '处理优先级 NORMAL/VIP(有效订阅专属客服通道 D7)'");
    }

    /** 幂等加列：information_schema 检查后才 ALTER（MySQL 无 ADD COLUMN IF NOT EXISTS） */
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

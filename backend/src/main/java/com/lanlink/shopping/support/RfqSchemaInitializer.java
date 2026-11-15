package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RFQ 询报价表结构迁移（存量库幂等升级，prod profile sql.init=never 时全靠本器）：
 *  - 新建 t_rfq / t_rfq_quote / t_rfq_event；
 *  - t_order 补 rfq_no / rfq_quote_id 两列（接受转订单时回填）。
 * 与 sql/schema.sql 双写一致；重复启动 information_schema 守卫保证列数恒为 1。
 * t_order 不存在（测试环境/未初始化）时跳过加列，建表语句本身幂等。
 */
@Component
@Order(20)
public class RfqSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public RfqSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_rfq (
                  rfq_id         BIGINT PRIMARY KEY AUTO_INCREMENT,
                  rfq_no         VARCHAR(40) NOT NULL COMMENT '询价单号',
                  user_id        BIGINT NOT NULL COMMENT '买家ID',
                  title          VARCHAR(120) NOT NULL COMMENT '采购标题',
                  description    VARCHAR(1000) COMMENT '需求描述',
                  prod_id        BIGINT NULL COMMENT '意向目录商品(可空)',
                  quantity       DECIMAL(14,2) NOT NULL COMMENT '采购数量',
                  unit           VARCHAR(16) COMMENT '计量单位',
                  quote_deadline DATETIME NOT NULL COMMENT '报价截止',
                  max_quotes     INT NOT NULL DEFAULT 10 COMMENT '报价户数上限',
                  status         VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED/QUOTED/ACCEPTED/ORDERED/EXPIRED/CANCELLED',
                  create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_rfq_no (rfq_no),
                  KEY idx_rfq_user (user_id, status, create_time),
                  KEY idx_rfq_status_deadline (status, quote_deadline)
                ) ENGINE=InnoDB COMMENT='买家询价单'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_rfq_quote (
                  quote_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
                  rfq_id       BIGINT NOT NULL COMMENT '询价单ID',
                  mer_id       BIGINT NOT NULL COMMENT '报价商户ID',
                  prod_id      BIGINT NOT NULL COMMENT '锚定目录商品(须属本商户)',
                  unit_price   DECIMAL(14,2) NOT NULL COMMENT '报价单价',
                  quantity     DECIMAL(14,2) NOT NULL COMMENT '报价数量',
                  total_amount DECIMAL(14,2) NOT NULL COMMENT 'unit_price*quantity(scale2)',
                  remark       VARCHAR(500) COMMENT '报价备注',
                  valid_until  DATETIME COMMENT '报价有效期',
                  status       VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/ACCEPTED/REJECTED/WITHDRAWN/EXPIRED',
                  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  UNIQUE KEY uk_quote_rfq_mer (rfq_id, mer_id),
                  KEY idx_quote_rfq (rfq_id, status),
                  KEY idx_quote_mer (mer_id, create_time)
                ) ENGINE=InnoDB COMMENT='商户报价(一户一报价)'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_rfq_event (
                  event_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
                  rfq_id     BIGINT NOT NULL COMMENT '询价单ID',
                  quote_id   BIGINT NULL COMMENT '关联报价ID',
                  event_type VARCHAR(32) NOT NULL COMMENT '事件类型',
                  actor_id   BIGINT COMMENT '操作人ID',
                  actor_role VARCHAR(16) COMMENT '操作人角色',
                  detail     VARCHAR(1000) COMMENT '事件明细',
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  KEY idx_event_rfq (rfq_id, event_id)
                ) ENGINE=InnoDB COMMENT='RFQ追加式留痕'
                """);

        Long orderTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order'",
                Long.class);
        if (orderTables == null || orderTables == 0) return;

        addColumnIfMissing("t_order", "rfq_no",
                "VARCHAR(40) NULL COMMENT '来源询价单号(t_rfq.rfq_no)'");
        addColumnIfMissing("t_order", "rfq_quote_id",
                "BIGINT NULL COMMENT '来源报价ID(t_rfq_quote.quote_id)'");
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
package com.lanlink.shopping.support;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商户自建促销 + 每日签到 + 任务激励 迁移：
 *  - t_campaign 幂等补 4 列（mer_id/prod_ids/review_status/review_note），存量库无需手工升级；
 *  - t_campaign_participant 幂等补唯一键 uk_cp_campaign_user(campaign_id, user_id)，
 *    激励参与（注册有礼/消费有礼）同用户同活动仅一条记录；存量重复行先去重（保留最早一条）再补键；
 *  - t_checkin 签到表在存量库缺失时补建（唯一键 uk_checkin_user_date 保证每日一签）；
 *  - t_member_point_log 幂等补唯一键 uk_point_user_type_ref(user_id, change_type, ref_order_no)，
 *    同一激励（签到/活动奖励/订单积分）并发重复发放由数据库兜底；存量重复行先去重（保留最早一条）；
 *  - t_order 幂等补 2 列（promo_title/promo_source），优惠构成展示快照（纯展示，不参与计价）；
 *  - t_task_record 任务激励流水表在存量库缺失时补建（唯一键 uk_task_user_key_ref 防任务重复落账）。
 * 首次建库由 sql/schema.sql 全量 DDL 负责；t_campaign 不存在（如测试环境/未初始化）则整体跳过。
 */
@Component
@Order(2)
public class CampaignCheckinSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    public CampaignCheckinSchemaInitializer(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override
    public void run(ApplicationArguments args) {
        Long tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_campaign'",
                Long.class);
        if (tables == null || tables == 0) return; // 表不存在则跳过（首次建库口径走 schema.sql）
        addColumnIfMissing("t_campaign", "mer_id", "BIGINT NULL");
        addColumnIfMissing("t_campaign", "prod_ids", "VARCHAR(500) NULL");
        addColumnIfMissing("t_campaign", "review_status", "TINYINT NOT NULL DEFAULT 1");
        addColumnIfMissing("t_campaign", "review_note", "VARCHAR(200) NULL");

        // t_order 优惠构成展示快照（F1：下单时落 quote.appliedTitle / quote.source，纯展示不参与计价）
        addColumnIfMissing("t_order", "promo_title", "VARCHAR(120) NULL");
        addColumnIfMissing("t_order", "promo_source", "VARCHAR(16) NULL");

        Long participantTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_campaign_participant'",
                Long.class);
        if (participantTables != null && participantTables > 0) {
            addUniqueKeyIfMissing("t_campaign_participant", "uk_cp_campaign_user", "campaign_id, user_id",
                    "DELETE cp FROM t_campaign_participant cp "
                            + "JOIN t_campaign_participant keep "
                            + "ON keep.campaign_id = cp.campaign_id AND keep.user_id = cp.user_id "
                            + "AND keep.participant_id < cp.participant_id");
        }

        Long checkinTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_checkin'",
                Long.class);
        if (checkinTables == null || checkinTables == 0) {
            jdbcTemplate.execute("CREATE TABLE t_checkin ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                    + "user_id BIGINT NOT NULL COMMENT '签到用户', "
                    + "checkin_date DATE NOT NULL COMMENT '签到日期', "
                    + "points INT NOT NULL COMMENT '当日获得积分', "
                    + "streak INT NOT NULL DEFAULT 1 COMMENT '连续签到天数', "
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY uk_checkin_user_date (user_id, checkin_date)"
                    + ") ENGINE=InnoDB COMMENT='每日签到'");
        }

        // 任务激励流水（F3；DDL 与 sql/schema.sql t_task_record 一致，uk_task_user_key_ref 防重复落账）
        Long taskTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_task_record'",
                Long.class);
        if (taskTables == null || taskTables == 0) {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_task_record ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
                    + "user_id BIGINT NOT NULL COMMENT '用户', "
                    + "task_key VARCHAR(32) NOT NULL COMMENT '任务标识(first_order/share_product)', "
                    + "ref_no VARCHAR(64) NOT NULL COMMENT '幂等引用(orderNo/shareId:yyyyMMdd)', "
                    + "points INT NOT NULL DEFAULT 0, "
                    + "growth INT NOT NULL DEFAULT 0, "
                    + "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY uk_task_user_key_ref (user_id, task_key, ref_no)"
                    + ") ENGINE=InnoDB COMMENT='任务激励流水'");
        }

        Long pointLogTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_member_point_log'",
                Long.class);
        if (pointLogTables != null && pointLogTables > 0) {
            addUniqueKeyIfMissing("t_member_point_log", "uk_point_user_type_ref",
                    "user_id, change_type, ref_order_no",
                    // 仅对业务引用号非空的激励流水去重；NULL 引用（人工调整）不受唯一键约束
                    "DELETE dup FROM t_member_point_log dup "
                            + "JOIN t_member_point_log keep "
                            + "ON keep.user_id = dup.user_id "
                            + "AND keep.change_type = dup.change_type "
                            + "AND keep.ref_order_no = dup.ref_order_no "
                            + "AND keep.log_id < dup.log_id "
                            + "WHERE dup.ref_order_no IS NOT NULL");
        }
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

    /**
     * 幂等补唯一键：先执行 dedupSql 对存量重复行去重（保留同组合内最早一条），
     * 再查 information_schema.STATISTICS 确认索引缺失后才 ALTER 加唯一键。
     */
    private void addUniqueKeyIfMissing(String table, String indexName, String keyColumns, String dedupSql) {
        jdbcTemplate.update(dedupSql);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, table, indexName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + indexName + " (" + keyColumns + ")");
        }
    }
}

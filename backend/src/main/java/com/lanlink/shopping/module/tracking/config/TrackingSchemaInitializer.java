package com.lanlink.shopping.module.tracking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 自建物流跟踪数据模型幂等初始化器（out/logistics-tracking-plan.md P1 任务 2）。
 * 与 LogisticsSplitSchemaInitializer 同一模式：修复 mode:never 手工建库路径漏表/漏列，
 * 支持在现有库表上无感热扩列，存量数据与测试环境零阻抗。
 *
 * 建表 DDL 与 schema.sql 物流段后的 tracking_event/tracking_sync_task/tracking_callback_log
 * 同源定义（CREATE TABLE IF NOT EXISTS 幂等）；热扩列：t_logistics_waybill.track_channel/
 * track_subscribe_status、t_logistics_alert.ticket_id（P3 客服工单桥接先建列）。
 */
@Component
@Order(4)
public class TrackingSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrackingSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public TrackingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[tracking-schema] 正在检查并初始化自建物流跟踪数据模型...");

        // 1. 渠道事件存档（uk_event 幂等键：provider_code + external_event_id）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tracking_event (
              event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码 mock/kuaidi100/kdniao/track17/aftership',
              external_event_id VARCHAR(64) NOT NULL COMMENT '渠道侧事件唯一ID(推送eventId；无显式ID取正文摘要哈希)',
              waybill_id BIGINT COMMENT '运单ID',
              logistics_no VARCHAR(64) COMMENT '第三方物流单号(快递单号)',
              event_type VARCHAR(16) DEFAULT 'callback' COMMENT 'poll=补拉 callback=推送 manual=人工',
              status VARCHAR(16) DEFAULT 'received' COMMENT 'received/normalized/failed/duplicate/skipped',
              raw_body MEDIUMTEXT COMMENT '渠道原文(落库前经 CallbackDesensitizer 脱敏)',
              normalized_count INT DEFAULT 0 COMMENT '解析出的规范化节点数',
              error_msg VARCHAR(500) COMMENT '解析/入库失败原因',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_event (provider_code, external_event_id),
              KEY idx_event_waybill (waybill_id),
              KEY idx_event_create (create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='物流跟踪渠道事件存档';
            """);

        // 2. 补拉任务（uk_task_waybill 一单一任务；owner/claimed_at 供 CAS 认领；
        //    fail_count/active_channel 供 ProviderRouter 主备切换，P2）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tracking_sync_task (
              task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              waybill_id BIGINT NOT NULL COMMENT '运单ID(一单一任务)',
              provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码(TrackingChannel 补拉路由键)',
              logistics_no VARCHAR(64) COMMENT '第三方物流单号(快递单号)',
              status VARCHAR(16) DEFAULT 'pending' COMMENT 'pending/running/done/failed/dead',
              next_poll_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下次补拉时间(成功回调顺延/失败指数退避)',
              retry_count INT DEFAULT 0,
              fail_count INT DEFAULT 0 COMMENT '当前生效通道连续失败次数(达阈值切备用通道，成功清零)',
              active_channel VARCHAR(16) COMMENT '当前生效通道(主备切换后为备用渠道编码)',
              owner VARCHAR(64) COMMENT 'CAS 认领者(实例标识)',
              claimed_at DATETIME COMMENT '认领时间',
              finished_time DATETIME COMMENT '终态时间',
              last_error VARCHAR(500) COMMENT '最近一次失败原因',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_task_waybill (waybill_id),
              KEY idx_task_claim (status, next_poll_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='物流跟踪补拉任务';
            """);

        // 3. 渠道事件存档扩列：event_time（渠道侧最新节点时间，入库延迟 P95 指标分母）
        addColumnIfNotExists("tracking_event", "event_time",
                "DATETIME NULL COMMENT '渠道侧最新节点时间(解析节点取最大值)'");

        // 4. 补拉任务扩列：fail_count/active_channel（P2 主备切换）
        addColumnIfNotExists("tracking_sync_task", "fail_count",
                "INT DEFAULT 0 COMMENT '当前生效通道连续失败次数(达阈值切备用通道，成功清零)'");
        addColumnIfNotExists("tracking_sync_task", "active_channel",
                "VARCHAR(16) NULL COMMENT '当前生效通道(主备切换后为备用渠道编码)'");

        // 5. 回调审计（验签失败也记脱敏原文）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tracking_callback_log (
              log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              provider_code VARCHAR(32) NOT NULL COMMENT '渠道编码',
              remote_ip VARCHAR(64) COMMENT '来源IP',
              verify_result VARCHAR(8) NOT NULL COMMENT 'PASS/FAIL',
              http_status INT COMMENT '响应码(验签失败401)',
              body_raw MEDIUMTEXT COMMENT '请求原文(落库前脱敏；PASS 仅存前2KB摘要，FAIL 存全量)',
              fail_reason VARCHAR(255) COMMENT '验签失败原因',
              event_id BIGINT COMMENT '关联 tracking_event.event_id(验签失败时为空)',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              KEY idx_cblog (provider_code, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='物流跟踪回调审计日志';
            """);

        // 6. 热扩列：运单跟踪通道与订阅状态、告警工单桥接列（P1 先建列，P3 写入）
        addColumnIfNotExists("t_logistics_waybill", "track_channel",
                "VARCHAR(16) DEFAULT 'mock' COMMENT '跟踪渠道 mock/kuaidi100/kdniao/track17/aftership'");
        addColumnIfNotExists("t_logistics_waybill", "track_subscribe_status",
                "VARCHAR(16) COMMENT '订阅状态 subscribed/unconfigured/failed/skipped/no_channel'");
        addColumnIfNotExists("t_logistics_alert", "ticket_id",
                "BIGINT NULL COMMENT '桥接客服工单ID(TrackingTicketBridge 回写，P3)'");

        log.info("[tracking-schema] 自建物流跟踪数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDef) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDef);
                log.info("[tracking-schema] 表 {} 扩展新字段 {} 成功", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[tracking-schema] 检查或添加字段 {}.{} 略过: {}", tableName, columnName, e.getMessage());
        }
    }
}

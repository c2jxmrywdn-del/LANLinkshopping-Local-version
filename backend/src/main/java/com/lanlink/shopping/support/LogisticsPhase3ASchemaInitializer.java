package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 物流发货板块 Phase 3A 数据模型与索引幂等初始化器：
 *  1. 为 t_logistics_waybill 扩展 last_trace_time 与 exception_flag 字段
 *  2. 为 t_logistics_provider 扩展 avg_eta_hours, service_score, sla_rate 字段并初始化基准指标
 *  3. 建设 idx_waybill_watchdog (status, update_time) 联合索引，加速在途停滞 SLA 看门狗扫描
 */
@Component
@Order(5)
public class LogisticsPhase3ASchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogisticsPhase3ASchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public LogisticsPhase3ASchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[logistics-phase3a-schema] 正在检查并初始化物流发货 Phase 3A (AI比价推荐与SLA看门狗) 索引与模型...");

        // 1. 扩容 t_logistics_waybill
        addColumnIfNotExists("t_logistics_waybill", "last_trace_time", "DATETIME COMMENT '最新轨迹更新时间(用于在途SLA巡检)'");
        addColumnIfNotExists("t_logistics_waybill", "exception_flag", "TINYINT NOT NULL DEFAULT 0 COMMENT '在途异常或停滞标记: 0正常 1异常'");

        // 2. 扩容 t_logistics_provider
        addColumnIfNotExists("t_logistics_provider", "avg_eta_hours", "INT NOT NULL DEFAULT 48 COMMENT '干线平均运输时效(小时)'");
        addColumnIfNotExists("t_logistics_provider", "service_score", "INT NOT NULL DEFAULT 92 COMMENT '服务质量与履约分(0-100)'");
        addColumnIfNotExists("t_logistics_provider", "sla_rate", "DECIMAL(5,2) NOT NULL DEFAULT 98.00 COMMENT '历史准时交付率百分比'");

        // 3. 回填或初始化主流服务商基准 SLA 数据
        try {
            jdbc.update("UPDATE t_logistics_provider SET avg_eta_hours = 24, service_score = 98, sla_rate = 99.50 WHERE provider_code = 'SF' OR provider_name LIKE '%顺丰%'");
            jdbc.update("UPDATE t_logistics_provider SET avg_eta_hours = 36, service_score = 96, sla_rate = 99.00 WHERE provider_code = 'JD' OR provider_name LIKE '%京东%'");
            jdbc.update("UPDATE t_logistics_provider SET avg_eta_hours = 48, service_score = 95, sla_rate = 98.50 WHERE provider_code = 'DBL' OR provider_name LIKE '%德邦%'");
            jdbc.update("UPDATE t_logistics_provider SET avg_eta_hours = 72, service_score = 90, sla_rate = 96.00 WHERE provider_code = 'ANE' OR provider_name LIKE '%安能%'");
            // 将历史运单的 last_trace_time 初始化为 update_time
            jdbc.update("UPDATE t_logistics_waybill SET last_trace_time = update_time WHERE last_trace_time IS NULL");
        } catch (Exception e) {
            log.warn("[logistics-phase3a-schema] 回填服务商 SLA 指标异常 (可忽略): {}", e.getMessage());
        }

        // 4. 幂等创建复合索引 idx_waybill_watchdog (status, update_time)
        addIndexIfNotExists("t_logistics_waybill", "idx_waybill_watchdog", "(status, update_time)");

        log.info("[logistics-phase3a-schema] Phase 3A 索引与数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column
            );
            if (count == null || count == 0) {
                jdbc.execute(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", table, column, definition));
                log.info("[logistics-phase3a-schema] 成功为表 {} 增加字段 {}", table, column);
            }
        } catch (Exception e) {
            log.warn("[logistics-phase3a-schema] 检查/增加字段 {}.{} 告警: {}", table, column, e.getMessage());
        }
    }

    private void addIndexIfNotExists(String table, String indexName, String columnsDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, indexName
            );
            if (count == null || count == 0) {
                jdbc.execute(String.format("ALTER TABLE `%s` ADD INDEX `%s` %s", table, indexName, columnsDefinition));
                log.info("[logistics-phase3a-schema] 成功为表 {} 增加复合索引 {}", table, indexName);
            }
        } catch (Exception e) {
            log.warn("[logistics-phase3a-schema] 检查/创建索引 {}.{} 告警: {}", table, indexName, e.getMessage());
        }
    }
}

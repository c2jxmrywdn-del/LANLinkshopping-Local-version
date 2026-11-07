package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 工业物流发货与多包裹拆单数据模型幂等初始化器 (Phase 1 基础数据模型扩展)。
 * 支持在现有库表上无感热增列，保证存量数据与测试环境零阻抗。
 */
@Component
@Order(3)
public class LogisticsSplitSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LogisticsSplitSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public LogisticsSplitSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[logistics-schema] 正在检查并初始化工业物流发货与多包裹拆单数据模型...");

        // 1. 创建运单发货商品明细关联表 t_logistics_waybill_item
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS t_logistics_waybill_item (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              waybill_id BIGINT NOT NULL COMMENT '运单ID',
              waybill_no VARCHAR(32) NOT NULL COMMENT '运单号',
              order_no VARCHAR(40) NOT NULL COMMENT '订单号',
              order_item_id BIGINT NOT NULL COMMENT '订单明细ID',
              prod_id BIGINT NOT NULL COMMENT '商品ID',
              prod_name VARCHAR(200) COMMENT '商品名称',
              shipped_qty INT NOT NULL DEFAULT 1 COMMENT '本次包裹实发数量',
              create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
              KEY idx_lwi_waybill (waybill_id),
              KEY idx_lwi_order (order_no),
              KEY idx_lwi_item (order_item_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
              COMMENT='运单发货商品明细关联表(多包裹拆单发货)';
            """);

        // 2. 幂等为 t_order_item 增加已发货数量与交付状态
        addColumnIfNotExists("t_order_item", "shipped_quantity", "INT NOT NULL DEFAULT 0 COMMENT '已发货数量(拆单累计)'");
        addColumnIfNotExists("t_order_item", "shipping_status", "VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending未发货 partial部分发货 shipped已全部发货'");

        // 3. 幂等为 t_logistics_waybill 增加多包裹字段、运输模式及地理坐标
        addColumnIfNotExists("t_logistics_waybill", "package_no", "VARCHAR(32) DEFAULT 'PKG-01' COMMENT '包裹编号(如PKG-01)'");
        addColumnIfNotExists("t_logistics_waybill", "package_index", "INT DEFAULT 1 COMMENT '包裹序号(第几包)'");
        addColumnIfNotExists("t_logistics_waybill", "package_total", "INT DEFAULT 1 COMMENT '总包裹数'");
        addColumnIfNotExists("t_logistics_waybill", "transport_mode", "VARCHAR(16) DEFAULT 'express' COMMENT 'express快递 ltl零担 ftl整车 direct直发 self自提'");
        addColumnIfNotExists("t_logistics_waybill", "sender_lng", "DECIMAL(10,6) COMMENT '发货地经度'");
        addColumnIfNotExists("t_logistics_waybill", "sender_lat", "DECIMAL(10,6) COMMENT '发货地纬度'");
        addColumnIfNotExists("t_logistics_waybill", "receiver_lng", "DECIMAL(10,6) COMMENT '目的地经度'");
        addColumnIfNotExists("t_logistics_waybill", "receiver_lat", "DECIMAL(10,6) COMMENT '目的地纬度'");
        addColumnIfNotExists("t_logistics_waybill", "current_lng", "DECIMAL(10,6) COMMENT '当前在途经度'");
        addColumnIfNotExists("t_logistics_waybill", "current_lat", "DECIMAL(10,6) COMMENT '当前在途纬度'");
        addColumnIfNotExists("t_logistics_waybill", "current_location", "VARCHAR(128) COMMENT '当前在途位置'");

        // 4. 幂等为 t_logistics_trace 增加经纬度与节点类型
        addColumnIfNotExists("t_logistics_trace", "longitude", "DECIMAL(10,6) COMMENT '经度'");
        addColumnIfNotExists("t_logistics_trace", "latitude", "DECIMAL(10,6) COMMENT '纬度'");
        addColumnIfNotExists("t_logistics_trace", "city", "VARCHAR(32) COMMENT '城市'");
        addColumnIfNotExists("t_logistics_trace", "hub_type", "VARCHAR(16) DEFAULT 'hub' COMMENT 'warehouse/hub/transfer/terminal/client'");

        log.info("[logistics-schema] 工业物流发货与多包裹拆单数据模型初始化完成。");
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDef) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDef);
                log.info("[logistics-schema] 表 {} 扩展新字段 {} 成功", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[logistics-schema] 检查或添加字段 {}.{} 略过: {}", tableName, columnName, e.getMessage());
        }
    }
}

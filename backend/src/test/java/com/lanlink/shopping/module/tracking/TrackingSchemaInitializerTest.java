package com.lanlink.shopping.module.tracking;

import com.lanlink.shopping.module.tracking.config.TrackingSchemaInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 跟踪库表初始化幂等单测（out/logistics-tracking-plan.md G1-2 的可离线部分）：
 * 同一 initializer 连续执行两次不抛错，且两次都发出三表 CREATE TABLE IF NOT EXISTS
 * 与三列热扩列 ALTER（information_schema 缺列判定由运行时真实库完成，此处以
 * COUNT 查询返回 null=缺列 的 mock 语义驱动 ALTER 分支）。
 * 集成档（真实 MySQL 连续两次执行）随 P2 worker 的测试容器一并覆盖。
 */
class TrackingSchemaInitializerTest {

    @Test
    @DisplayName("连续执行两次 initializer：不抛错，三表 DDL 与热扩列 ALTER 均幂等重发")
    void runsTwiceIdempotently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 默认 queryForObject 返回 null → addColumnIfNotExists 视为缺列 → 执行 ALTER
        TrackingSchemaInitializer initializer = new TrackingSchemaInitializer(jdbc);

        assertDoesNotThrow(() -> {
            initializer.run(null);
            initializer.run(null);
        }, "二次执行不得抛错（幂等建表/扩列）");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(sql.capture());
        List<String> executed = sql.getAllValues();

        for (String table : new String[]{"tracking_event", "tracking_sync_task", "tracking_callback_log"}) {
            long count = executed.stream()
                    .filter(s -> s.contains("CREATE TABLE IF NOT EXISTS " + table))
                    .count();
            assertEquals(2, count, "表 " + table + " 两次执行都应发出 CREATE TABLE IF NOT EXISTS");
        }
        // 唯一键/索引关键定义在场
        assertTrue(executed.stream().anyMatch(s -> s.contains("UNIQUE KEY uk_event (provider_code, external_event_id)")));
        assertTrue(executed.stream().anyMatch(s -> s.contains("UNIQUE KEY uk_task_waybill (waybill_id)")));
        assertTrue(executed.stream().anyMatch(s -> s.contains("KEY idx_task_claim (status, next_poll_time)")));
        // 热扩列：waybill.track_channel/track_subscribe_status、alert.ticket_id
        assertTrue(executed.stream().anyMatch(s -> s.contains("ADD COLUMN track_channel")));
        assertTrue(executed.stream().anyMatch(s -> s.contains("ADD COLUMN track_subscribe_status")));
        assertTrue(executed.stream().anyMatch(s -> s.contains("ADD COLUMN ticket_id")));
    }
}

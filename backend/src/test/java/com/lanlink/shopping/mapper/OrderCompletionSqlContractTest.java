package com.lanlink.shopping.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单完成 SQL 的契约回归（设计 §4.4）。
 *
 * <p>两条完成语句是 `t_order.finish_time` 的唯一写入点——退店时间闸门全靠它。
 * 任何一次"顺手重构"把 finish_time 弄丢，闸门会静默退化为永不放行/永远放行，
 * 而且不会有任何编译错误。这里用反射把 SQL 文本本身钉住。
 *
 * <p>同时钉住 autoCompleteShipped 的显式 COLLATE：两侧 order_no 排序规则不同
 * （utf8mb4_unicode_ci vs utf8mb4_general_ci），漏掉 COLLATE 会抛 ERROR 1267，
 * 该自动完成任务曾因此从未生效（实测复现过）。
 */
class OrderCompletionSqlContractTest {

    private static String sqlOf(String method, Class<?>... params) throws Exception {
        Method m = OrderMapper.class.getMethod(method, params);
        Update update = m.getAnnotation(Update.class);
        assertNotNull(update, method + " 必须带 @Update");
        return String.join(" ", update.value()).replaceAll("\\s+", " ").trim();
    }

    @Test
    void markCompletedAtomic_writes_finish_time() throws Exception {
        String sql = sqlOf("markCompletedAtomic", String.class);
        assertTrue(sql.contains("order_status = 2"), "必须置为已完成: " + sql);
        assertTrue(sql.contains("finish_time = NOW()"), "必须在同一语句原子写入 finish_time: " + sql);
        assertTrue(sql.contains("order_status IN (1, 4)"),
                "前置状态必须限定为已发货/部分发货: " + sql);
    }

    @Test
    void autoCompleteShipped_writes_finish_time() throws Exception {
        String sql = sqlOf("autoCompleteShipped", int.class);
        assertTrue(sql.contains("finish_time = NOW()"), "必须写 finish_time: " + sql);
        assertTrue(sql.contains("o.order_status = 2"), "必须置为已完成: " + sql);
    }

    @Test
    void autoCompleteShipped_pins_collation_on_both_sides() throws Exception {
        String sql = sqlOf("autoCompleteShipped", int.class);
        // 两侧都要显式 COLLATE，否则 utf8mb4_general_ci × utf8mb4_unicode_ci → ERROR 1267
        assertTrue(sql.contains("w.order_no COLLATE utf8mb4_unicode_ci"),
                "JOIN 左侧必须显式 COLLATE: " + sql);
        assertTrue(sql.contains("o.order_no COLLATE utf8mb4_unicode_ci"),
                "JOIN 右侧必须显式 COLLATE: " + sql);
    }

    @Test
    void autoCompleteShipped_keeps_its_eligibility_filters() throws Exception {
        String sql = sqlOf("autoCompleteShipped", int.class);
        assertTrue(sql.contains("o.pay_status = 1"), "仅已支付订单可自动完成: " + sql);
        assertTrue(sql.contains("w.ship_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)"),
                "超时口径必须按运单发货时间: " + sql);
    }

    @Test
    void no_other_completion_statement_forgets_finish_time() throws Exception {
        // 兜底：任何把 order_status 置 2 的语句都必须同时写 finish_time
        for (Method m : OrderMapper.class.getMethods()) {
            Update u = m.getAnnotation(Update.class);
            if (u == null) continue;
            String sql = String.join(" ", u.value()).replaceAll("\\s+", " ");
            boolean setsCompleted = sql.matches("(?s).*\\border_status\\s*=\\s*2\\b.*");
            if (setsCompleted) {
                assertTrue(sql.contains("finish_time"),
                        m.getName() + " 把订单置为已完成却没写 finish_time（时间闸门会失真）: " + sql);
            }
        }
    }
}

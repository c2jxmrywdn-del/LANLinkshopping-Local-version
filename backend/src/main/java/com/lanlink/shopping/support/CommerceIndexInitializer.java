package com.lanlink.shopping.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 交易主链路的索引兜底（幂等）。
 *
 * <p>背景：库建好时 `t_order` 只有主键、`t_product` 只有主键，而这两个表承载的是最高频的读路径：
 * 「我的订单」按 user_id + create_time 排序、后台按 pay_status 过滤、商城列表按
 * status/review_status/deleted 过滤后按销量或价格排序。没有二级索引时这些查询是全表扫，
 * 数据量一上来先崩的就是首页与订单页。
 *
 * <p>为什么在代码里加而不是只改 schema.sql：schema.sql 只对全新库生效，
 * 已经在跑的库不会重放；这里用 information_schema 判存后再 ALTER，新老库都能补齐。
 * 索引列顺序按「等值过滤列 → 排序列」组织，与 {@code ProductService.page} /
 * {@code OrderService.myOrders} 的实际 SQL 对齐。
 */
@Component
@Order(3)
public class CommerceIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommerceIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public CommerceIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 商品列表：三种排序各一条，前缀都是 status/review_status/deleted（列表查询的等值条件）
        addIndexIfMissing("t_product", "idx_product_list_default", "(status, review_status, deleted, create_time)");
        addIndexIfMissing("t_product", "idx_product_list_sales", "(status, review_status, deleted, sales)");
        addIndexIfMissing("t_product", "idx_product_list_price", "(status, review_status, deleted, price)");
        // 商户中心「我的商品」
        addIndexIfMissing("t_product", "idx_product_mer", "(mer_id, status)");

        // 我的订单：WHERE user_id = ? ORDER BY create_time DESC
        addIndexIfMissing("t_order", "idx_order_user_time", "(user_id, create_time)");
        // 后台按支付状态过滤
        addIndexIfMissing("t_order", "idx_order_pay_time", "(pay_status, create_time)");
        // 全量按时间倒序 / 后续超时关单扫描未付单
        addIndexIfMissing("t_order", "idx_order_time", "(create_time)");

        // 购物车：/cart/list 与结算取行都是 WHERE user_id = ?。
        // 该表随用户量线性增长且读写频繁，无索引时每次进购物车都是全表扫。
        addIndexIfMissing("t_cart", "idx_cart_user", "(user_id)");
        // 购物车加购的原子累加（INSERT ... ON DUPLICATE KEY UPDATE）依赖该唯一键；
        // 没有它时同用户同商品会产生重复行，累加语义失效。
        addUniqueIfMissing("t_cart", "uk_cart_user_prod", "(user_id, prod_id)");
        // 注：t_order_item 的 order_no 索引已由历史 DDL 建为 idx_oi_order，
        // 这里不再重复添加（同一列上两条等价索引只会拖慢写入）。
        // 商户端 15+ 处按登录账号反查商户档案：WHERE user_id = ?
        addIndexIfMissing("t_merchant", "idx_merchant_user", "(user_id)");
    }

    /**
     * 幂等加唯一键。
     *
     * <p>加唯一键前先合并历史重复行（保留最早一行、数量求和、其余删除），
     * 否则 ALTER 会因存量重复数据直接失败、启动中断。合并只在「确实存在重复」时执行，
     * 正常库是一次 no-op 查询。
     */
    private void addUniqueIfMissing(String table, String index, String columns) {
        Long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, table, index);
        if (exists != null && exists > 0) return;
        if ("t_cart".equals(table)) {
            // 同 user+prod 多行：把数量并入 cart_id 最小的那行，再删掉其余
            jdbcTemplate.execute("UPDATE t_cart c JOIN ("
                    + " SELECT user_id, prod_id, MIN(cart_id) keep_id, SUM(quantity) total_qty"
                    + " FROM t_cart GROUP BY user_id, prod_id HAVING COUNT(*) > 1) d"
                    + " ON c.cart_id = d.keep_id SET c.quantity = d.total_qty");
            jdbcTemplate.execute("DELETE c FROM t_cart c JOIN ("
                    + " SELECT user_id, prod_id, MIN(cart_id) keep_id"
                    + " FROM t_cart GROUP BY user_id, prod_id HAVING COUNT(*) > 1) d"
                    + " ON c.user_id = d.user_id AND c.prod_id = d.prod_id"
                    + " WHERE c.cart_id <> d.keep_id");
        }
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + index + " " + columns);
        log.info("[schema] 已补唯一键 {}.{} {}", table, index, columns);
    }

    /** 幂等加索引：MySQL 没有 ADD INDEX IF NOT EXISTS，只能先查 information_schema */
    private void addIndexIfMissing(String table, String index, String columns) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Long.class, table, index);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD KEY " + index + " " + columns);
            log.info("[schema] 已补索引 {}.{} {}", table, index, columns);
        }
    }
}

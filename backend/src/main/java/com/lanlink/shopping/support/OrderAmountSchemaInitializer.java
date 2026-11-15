package com.lanlink.shopping.support;

import com.lanlink.shopping.integration.pricing.OrderAmounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单「金额口径拆分」数据模型初始化器（P1-6 批次 1）。
 *
 * <p>只做数据模型与存量回填，不接任何交易编排（下单/退款链路下一批次接入）：
 * <ol>
 *   <li>{@code t_order} 加 {@code goods_amount}（货款实付，NULL 允许）与
 *       {@code freight_amount}（运费，NOT NULL DEFAULT 0）；</li>
 *   <li>{@code t_order_item} 加 {@code allocated_discount / paid_amount}（NULL 允许）与
 *       {@code freight_amount}（NOT NULL DEFAULT 0）；</li>
 *   <li>幂等回填订单级：{@code goods_amount = total_amount}（按口径纠错——total_amount 已是实付现金，
 *       不能再扣 promo/coupon/point，否则二次扣减成负货款），运费保持 0；</li>
 *   <li>幂等回填行级：按 {@link OrderAmounts#allocate} 把 goods_amount 分摊成 paid_amount/allocated_discount
 *       （Σ行实付 == goods_amount，末行吸尾差），未回填行以 {@code paid_amount IS NULL} 判定。</li>
 * </ol>
 *
 * <p><b>不改 schema.sql</b>（并行任务约束）：列与回填全部走本初始化器，重复启动幂等。
 * 回填失败只告警不阻断启动（脏数据不得让应用起不来）；回填后三条不变式由 {@link OrderAmounts} 校验，
 * 单个订单脏数据仅跳过该单并告警。
 *
 * <p>@Order(8)：晚于 {@code RefundSchemaInitializer(@Order(4))}（t_order/t_order_item 已存在），
 * 早于结算/财务初始化器；8 当前未被占用。
 */
@Component
@Order(8)
public class OrderAmountSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderAmountSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public OrderAmountSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("[order-amount-schema] 正在检查订单金额口径拆分数据模型（P1-6）...");

        // 1. 订单级列
        addColumnIfNotExists("t_order", "goods_amount",
                "DECIMAL(14,2) NULL COMMENT '货款实付(=total-运费,不含运费;存量=total_amount)'");
        addColumnIfNotExists("t_order", "freight_amount",
                "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '运费(存量0;total=goods+freight)'");

        // 2. 订单行级列
        addColumnIfNotExists("t_order_item", "allocated_discount",
                "DECIMAL(14,2) NULL COMMENT '行分摊优惠(促销+会员+券+积分)=subtotal-paid'");
        addColumnIfNotExists("t_order_item", "paid_amount",
                "DECIMAL(14,2) NULL COMMENT '行实付货款=subtotal-allocated_discount;Σ=goods_amount'");
        addColumnIfNotExists("t_order_item", "freight_amount",
                "DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '行分摊运费;Σ=order.freight_amount'");

        // 3. 订单级幂等回填：goods_amount = total_amount（不扣任何优惠，防二次扣减）
        backfillOrderGoodsAmount();

        // 4. 行级幂等回填：按订单 goods_amount 比例分摊（末行吸尾差）
        backfillItemAmounts();

        log.info("[order-amount-schema] 订单金额口径拆分数据模型检查/回填完成。");
    }

    private void backfillOrderGoodsAmount() {
        try {
            int updated = jdbc.update(
                    "UPDATE t_order SET goods_amount = COALESCE(total_amount, 0) "
                            + "WHERE goods_amount IS NULL");
            if (updated > 0) {
                log.info("[order-amount-schema] t_order.goods_amount 存量回填完成: {} 单（=total_amount，运费0）", updated);
            }
        } catch (Exception e) {
            log.warn("[order-amount-schema] t_order.goods_amount 回填失败（下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 行级回填：只处理 {@code paid_amount IS NULL} 的行，按订单分组、按 subtotal 比例分摊
     * {@code goods_amount}/{@code freight_amount}，逐行 UPDATE。单订单异常单独告警跳过，不拖垮整轮回填。
     */
    private void backfillItemAmounts() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT i.item_id, i.order_no, i.subtotal, o.goods_amount, o.freight_amount "
                            + "FROM t_order_item i JOIN t_order o ON o.order_no = i.order_no "
                            + "WHERE i.paid_amount IS NULL AND o.goods_amount IS NOT NULL "
                            + "ORDER BY i.order_no, i.item_id");
        } catch (Exception e) {
            log.warn("[order-amount-schema] 行级回填查询失败（下次启动重试）: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<Map<String, Object>>> byOrder = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String orderNo = String.valueOf(row.get("order_no"));
            byOrder.computeIfAbsent(orderNo, k -> new ArrayList<>()).add(row);
        }
        int updatedItems = 0;
        int skippedOrders = 0;
        for (Map.Entry<String, List<Map<String, Object>>> entry : byOrder.entrySet()) {
            String orderNo = entry.getKey();
            List<Map<String, Object>> items = entry.getValue();
            try {
                List<BigDecimal> subtotals = new ArrayList<>(items.size());
                for (Map<String, Object> item : items) {
                    subtotals.add(toMoney(item.get("subtotal")));
                }
                BigDecimal goods = toMoney(items.get(0).get("goods_amount"));
                BigDecimal freight = toMoney(items.get(0).get("freight_amount"));
                List<OrderAmounts.LineAmount> lines = OrderAmounts.allocate(subtotals, goods, freight);
                OrderAmounts.checkInvariants(subtotals, goods, freight, lines);
                for (int i = 0; i < items.size(); i++) {
                    Object itemId = items.get(i).get("item_id");
                    OrderAmounts.LineAmount line = lines.get(i);
                    updatedItems += jdbc.update(
                            "UPDATE t_order_item SET allocated_discount = ?, paid_amount = ?, freight_amount = ? "
                                    + "WHERE item_id = ? AND paid_amount IS NULL",
                            line.allocatedDiscount(), line.paidAmount(), line.freightAmount(), itemId);
                }
            } catch (Exception e) {
                skippedOrders++;
                log.warn("[order-amount-schema] 订单 {} 行级金额回填跳过（需人工核）: {}", orderNo, e.getMessage());
            }
        }
        log.info("[order-amount-schema] t_order_item 行级金额回填完成: 订单{}单/行{}条{}",
                byOrder.size(), updatedItems,
                skippedOrders > 0 ? "，跳过异常订单 " + skippedOrders + " 单" : "");
    }

    /** DB 值归一：MySQL 驱动通常返回 BigDecimal，兼容 Number/String；null→0 */
    private static BigDecimal toMoney(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value.toString().trim();
        return text.isEmpty() ? BigDecimal.ZERO : new BigDecimal(text);
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, tableName, columnName);
            if (count == null || count == 0) {
                jdbc.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                log.info("[order-amount-schema] 已为表 {} 增加字段 {}", tableName, columnName);
            }
        } catch (Exception e) {
            log.warn("[order-amount-schema] 检查/添加 {}.{} 失败（可忽略）: {}",
                    tableName, columnName, e.getMessage());
        }
    }
}

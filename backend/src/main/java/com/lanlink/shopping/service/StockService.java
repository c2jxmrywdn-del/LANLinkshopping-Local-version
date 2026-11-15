package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.StockFlow;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.StockFlowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存回补唯一入口（P1-3 幂等基础设施）。
 *
 * <p>解决的问题：{@code ProductMapper.restoreStockAtomic} 是累加式 UPDATE，同一笔业务事件重复执行
 * （重复取消、关单重试、验货重放、看门狗补偿、人工重试）会把库存越补越多。本类以「库存流水 +
 * 唯一键」做业务事件级幂等：
 *
 * <pre>
 *   1. INSERT IGNORE INTO t_stock_flow(biz_type,biz_no,prod_id,order_item_id,...)
 *        ├─ affected=1：首次，继续 2
 *        └─ affected=0：已回补过，直接返回 false（数据库不会执行任何库存 UPDATE）
 *   2. UPDATE t_product SET stock=stock+?, sales=GREATEST(0,sales-?)
 * </pre>
 *
 * <p><b>事务约束</b>：方法以 {@link Propagation#MANDATORY} 运行，必须由调用方事务包裹——抢键与
 * 库存 UPDATE 必须同事务提交/回滚，否则会出现「流水已占位但库存未补」的永久丢补。
 * 现有 4 个回补调用方（取消/超时关单/全额退款/RMA 验货）均为 {@code @Transactional}，满足约束。
 *
 * <p><b>不做的事</b>：不复制 bump（销量/库存）SQL，直接复用 {@link ProductMapper#restoreStockAtomic}；
 * 不改变订单状态机与释放触发条件（那是既有 CAS 的职责）。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /** 买家取消未支付订单 */
    public static final String BIZ_ORDER_CANCEL = "ORDER_CANCEL";
    /** 超时未支付系统关单 */
    public static final String BIZ_ORDER_CLOSE = "ORDER_CLOSE";
    /** 全额退款结算（货物是否退回由调用方按业务决定是否调用本方法） */
    public static final String BIZ_FULL_REFUND = "FULL_REFUND";
    /** RMA 退货验货合格入库（按实际收货数量回补） */
    public static final String BIZ_RMA_RESTOCK = "RMA_RESTOCK";

    private final StockFlowMapper stockFlowMapper;
    private final ProductMapper productMapper;

    /** 订单行查询（可选注入：单测既有一参构造不受影响；生产由 Spring 注入）。 */
    @Autowired(required = false)
    private OrderItemMapper orderItemMapper;

    public void setOrderItemMapper(OrderItemMapper orderItemMapper) {
        this.orderItemMapper = orderItemMapper;
    }

    public StockService(StockFlowMapper stockFlowMapper, ProductMapper productMapper) {
        this.stockFlowMapper = stockFlowMapper;
        this.productMapper = productMapper;
    }

    /**
     * 按订单维度幂等回补（取消 / 超时关单 / 全额退款 / RMA 验货四个入口共用）。
     *
     * <p>逐订单行调用 {@link #restore}：同一 {@code bizType + bizNo} 下每一行至多生效一次；
     * 取代原先各调用方各自复制的「按 orderNo 查明细再逐行累加」的非幂等实现。
     *
     * @return 本次真正执行了库存回补的行数（幂等命中/非法行不计）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int restoreByOrder(String bizType, String bizNo, String orderNo, Long operatorId, String remark) {
        requireText(bizType, "bizType");
        requireText(bizNo, "bizNo");
        requireText(orderNo, "orderNo");
        if (orderItemMapper == null) {
            // fail-fast：生产该 Bean 必注入；静默跳过会造成「订单已取消/退款但库存未回补」
            throw new IllegalStateException("OrderItemMapper 未注入，无法按订单回补库存");
        }
        List<OrderItem> items = orderItemMapper.selectList(Wrappers.<OrderItem>lambdaQuery()
                .eq(OrderItem::getOrderNo, orderNo));
        int restored = 0;
        for (OrderItem it : items) {
            Integer qty = it.getQuantity();
            if (it.getProdId() == null || it.getItemId() == null || qty == null || qty <= 0) {
                log.warn("[stock] 跳过非法订单行 orderNo={} itemId={} prodId={} qty={}",
                        orderNo, it.getItemId(), it.getProdId(), qty);
                continue;
            }
            if (restore(bizType, bizNo, it.getProdId(), it.getItemId(), qty, orderNo, operatorId, remark)) {
                restored++;
            }
        }
        return restored;
    }

    /**
     * 幂等回补单行商品库存。
     *
     * @param bizType     业务类型（用本类 {@code BIZ_*} 常量）
     * @param bizNo       业务单号（订单号/售后单号/退款单号）；与 bizType+prodId+orderItemId 共同构成幂等键
     * @param prodId      商品 ID
     * @param orderItemId 订单行 ID；不允许 null（MySQL 唯一索引对 NULL 不判重）
     * @param quantity    回补数量，必须 &gt; 0
     * @param orderNo     冗余订单号（可空，仅审计）
     * @param operatorId  操作人（可空，自动任务传 null）
     * @param remark      备注（可空）
     * @return true = 本次真正执行了库存回补；false = 幂等命中（此前已回补过，本次跳过）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restore(String bizType, String bizNo, Long prodId, Long orderItemId,
                           int quantity, String orderNo, Long operatorId, String remark) {
        requireText(bizType, "bizType");
        requireText(bizNo, "bizNo");
        if (prodId == null) {
            throw new IllegalArgumentException("prodId 不能为空");
        }
        if (orderItemId == null) {
            // 唯一索引 (…, order_item_id) 对 NULL 不判重，允许 null 会让幂等静默失效
            throw new IllegalArgumentException("orderItemId 不能为空（幂等唯一键依赖该列）");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }

        StockFlow flow = new StockFlow();
        flow.setBizType(bizType);
        flow.setBizNo(bizNo);
        flow.setProdId(prodId);
        flow.setOrderItemId(orderItemId);
        flow.setQuantity(quantity);
        flow.setOrderNo(orderNo);
        flow.setOperatorId(operatorId);
        flow.setRemark(remark);

        // 第一步：抢幂等键。唯一键冲突由数据库裁决，并发重复调用只会有一路 affected=1。
        int claimed = stockFlowMapper.insertIgnore(flow);
        if (claimed == 0) {
            log.info("[stock] 重复回补已忽略 bizType={} bizNo={} prodId={} orderItemId={}",
                    bizType, bizNo, prodId, orderItemId);
            return false;
        }

        // 第二步：复用既有原子回补 SQL（不复制 bump 逻辑）。
        int affected = productMapper.restoreStockAtomic(prodId, quantity);
        if (affected == 0) {
            // 商品已被逻辑删除：库存无处可补。流水保留占位，避免反复重试；返回 true 表示该事件已结案。
            log.warn("[stock] 回补目标不存在（商品已删除），流水已占位 bizType={} bizNo={} prodId={}",
                    bizType, bizNo, prodId);
        }
        return true;
    }

    /** 便捷重载：无订单号/操作人/备注的自动回补场景。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restore(String bizType, String bizNo, Long prodId, Long orderItemId, int quantity) {
        return restore(bizType, bizNo, prodId, orderItemId, quantity, null, null, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}

package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水（P1-3 幂等基础设施）。
 *
 * <p>一行记录一次「库存回补业务事件」。唯一键
 * {@code (biz_type, biz_no, prod_id, order_item_id)} 是幂等闸门：同一业务单号对同一订单行只允许
 * 一条回补流水，重复触发时由 {@code INSERT IGNORE} 命中唯一键返回 0，调用方据此跳过库存 UPDATE。
 *
 * <p>注意 {@code order_item_id} 必须非空：MySQL 唯一索引对 NULL 不判重，若允许 NULL 会使幂等失效。
 *
 * <p>本表只做「回补」记账与幂等；扣减侧仍由 {@code ProductMapper.deductStockAtomic} 的条件更新
 * 保证不超卖，不在此重复记账（扣减失败整体回滚，无需补偿）。
 */
@Data
@TableName("t_stock_flow")
public class StockFlow {

    @TableId(type = IdType.AUTO)
    private Long flowId;

    /** 业务类型：ORDER_CANCEL / ORDER_CLOSE / FULL_REFUND / RMA_RESTOCK（见 StockService 常量） */
    private String bizType;

    /** 业务单号：订单号 / 售后单号 / 渠道退款单号 */
    private String bizNo;

    /** 商品 ID */
    private Long prodId;

    /** 订单行 ID（t_order_item.item_id；幂等键组成部分，禁止为 null） */
    private Long orderItemId;

    /** 变动数量：回补为正 */
    private Integer quantity;

    /** 冗余订单号：便于按订单审计/对账 */
    private String orderNo;

    /** 操作人（人工触发时记录，自动任务可空） */
    private Long operatorId;

    /** 备注：触发原因/来源，便于排障 */
    private String remark;

    private LocalDateTime createTime;
}

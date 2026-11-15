package com.lanlink.shopping.module.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 结算批次明细：一条 = 一个批次纳入的一条收益台账行。
 *
 * <p>uk_settle_item(settle_id, earning_id) 是「同一收益行在同一批次内不重复」的硬约束；
 * 收益行一旦被某批次纳入即转 SETTLED，故跨批次也不会重复结算。
 *
 * <p>order_no / item_id / type / net_amount 为纳入时点的冗余快照，便于对账时不回表。
 * 与 support/SettlementSchemaInitializer.java + sql/schema.sql 的 t_merchant_settlement_item 双写一致。
 */
@Data
@TableName("t_merchant_settlement_item")
public class MerchantSettlementItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属批次(t_merchant_settlement.settle_id) */
    private Long settleId;
    /** 纳入的收益行(t_merchant_earning.earning_id) */
    private Long earningId;
    /** 订单号快照 */
    private String orderNo;
    /** 订单行ID快照 */
    private Long itemId;
    /** EARN / REVERSE 快照 */
    private String type;
    /** 纳入净额快照 */
    private BigDecimal netAmount;
}

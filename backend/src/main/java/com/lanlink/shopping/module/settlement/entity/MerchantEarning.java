package com.lanlink.shopping.module.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户收益台账（逐订单行记入，平台侧只读账本，不触碰资金流向）。
 *
 * <p>幂等骨架：UNIQUE uk_earning_line(order_no, item_id, type, refund_seq)。
 * 正向记入 type=EARN 且 refund_seq=0；每笔退款冲正 type=REVERSE 且 refund_seq=n（第 n 次退款），
 * 故同一订单行重复同步只会命中唯一键、不会重复记入或重复冲正。
 *
 * <p>金额口径：gross_amount = 行成交额；commission_amount = gross × commission_rate/100；
 * net_amount = gross - commission_amount（DECIMAL(14,2) 落库，均为 NOT NULL）。
 *
 * <p>状态机：PENDING（记入即入持有期）→ AVAILABLE（持有期满）→ SETTLED（进入结算批次）
 * → PAID（所属批次已完成线下打款登记，终态，不计入可提余额）；退款冲正后原行转 REVERSED，
 * 冲正行自身走同一套状态流转。PAID 仅为账面打款登记，平台不代收代付。
 *
 * <p>与 support/SettlementSchemaInitializer.java + sql/schema.sql 的 t_merchant_earning 双写一致。
 */
@Data
@TableName("t_merchant_earning")
public class MerchantEarning {
    @TableId(type = IdType.AUTO)
    private Long earningId;
    /** 归属商户(t_merchant.mer_id) */
    private Long merId;
    /** 订单号(t_order.order_no) */
    private String orderNo;
    /** 订单行ID(t_order_item.item_id) */
    private Long itemId;
    /** 商品ID */
    private Long prodId;
    /** EARN 正向记入 / REVERSE 退款冲正 */
    private String type;
    /** EARN=0；REVERSE 第 n 次退款=n（配合 uk_earning_line 幂等） */
    private Integer refundSeq;
    /** 行成交额 */
    private BigDecimal grossAmount;
    /** 记账时点的佣金率(%)快照，不随商户后续改率而变 */
    private BigDecimal commissionRate;
    /** 平台佣金 = grossAmount × commissionRate/100 */
    private BigDecimal commissionAmount;
    /** 商户净得 = grossAmount - commissionAmount（REVERSE 行为负数） */
    private BigDecimal netAmount;
    /** PENDING / AVAILABLE / SETTLED / PAID / REVERSED（PAID = 所属批次已登记线下打款，终态） */
    private String status;
    /** 持有期满转 AVAILABLE 的时间点 */
    private LocalDateTime availableAt;
    /** REVERSE 行指向被冲正的 EARN 行 */
    private Long refEarningId;
    /** 订单行小计快照 */
    private BigDecimal orderSubtotal;
    /** 订单总额快照 */
    private BigDecimal orderTotal;
    private LocalDateTime createTime;
}

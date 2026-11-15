package com.lanlink.shopping.module.settlement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户结算批次（一次结算 = 一个商户在一个账期内的一批 AVAILABLE 收益行）。
 *
 * <p>uk_settle_no 保证批次号唯一；批次明细落 t_merchant_settlement_item，
 * 明细侧 uk_settle_item(settle_id, earning_id) 保证同一收益行不会被同一批次重复纳入。
 *
 * <p>状态机：DRAFT（生成待审）→ APPROVED（审核通过待打款）→ PAID（线下打款并回填凭证）。
 * 平台只做记账与对账（二清规避），实际打款在平台外完成，pay_voucher 仅存凭证引用。
 *
 * <p>与 support/SettlementSchemaInitializer.java + sql/schema.sql 的 t_merchant_settlement 双写一致。
 */
@Data
@TableName("t_merchant_settlement")
public class MerchantSettlement {
    @TableId(type = IdType.AUTO)
    private Long settleId;
    /** 批次号（业务唯一） */
    private String settleNo;
    /** 归属商户 */
    private Long merId;
    /** 账期起（含） */
    private LocalDateTime periodStart;
    /** 账期止（不含） */
    private LocalDateTime periodEnd;
    /** 批次净额合计 */
    private BigDecimal totalNet;
    /** 批次佣金合计 */
    private BigDecimal commissionTotal;
    /** 纳入明细条数 */
    private Integer itemCount;
    /** DRAFT / APPROVED / PAID */
    private String status;
    /** 线下打款凭证引用（URL 或单号） */
    private String payVoucher;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

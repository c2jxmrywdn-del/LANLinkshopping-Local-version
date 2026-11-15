package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款审批留痕（P0-C 退款出款收口，append-only：一行 = 一次审批动作，不删不改历史）。
 *
 * <p>双人留痕链：
 * <ol>
 *   <li>MERCHANT/APPROVE（商户初审通过）或 MERCHANT/REJECT（商户驳回）；</li>
 *   <li>PLATFORM/APPROVE（平台复核通过，写入后由出款方“消费”）、PLATFORM/REJECT（平台驳回复核）、
 *       PLATFORM/REOPEN（申诉重开复核，把平台驳回的售后单放回待复核）。</li>
 * </ol>
 *
 * <p>{@code consumed}：平台 APPROVE 行是否已被一次出款消费。出款前用条件更新
 * {@code UPDATE ... SET consumed = 1 WHERE id = ? AND consumed = 0} 抢占，保证“一次复核只出一次款、
 * 并发重复复核也只会有一路拿到出款权”。consumed 只做使用标记，审批行本身仍保留（审计可回放）。
 *
 * <p>表由 {@code support/RefundApprovalSchemaInitializer} 幂等创建（DDL 见
 * {@code reports/impl/p0c-refund-gate.md}，待合并进 schema.sql）。
 */
@Data
@TableName("t_refund_approval")
public class RefundApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 RMA 单号；链A（普通订单退款）为 null */
    private String rmaNo;

    /** 关联订单号 */
    private String orderNo;

    /** 审批阶段：MERCHANT 商户初审 / PLATFORM 平台复核 */
    private String stage;

    /** 动作：APPROVE 通过 / REJECT 驳回 / REOPEN 申诉重开（仅 PLATFORM 阶段） */
    private String action;

    /** 操作人用户ID */
    private Long operatorId;

    /** 操作人角色：MERCHANT / ADMIN / SYSTEM */
    private String operatorRole;

    /** 本次审批锁定的退款金额（元）；null 表示不锁定金额（仅历史兼容） */
    private BigDecimal amount;

    /** 审批意见/驳回原因/重开理由 */
    private String remark;

    /** 平台 APPROVE 是否已被出款消费：0 未消费 / 1 已消费（一次复核只允许一次出款） */
    private Integer consumed;

    private LocalDateTime createTime;
}

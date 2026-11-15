package com.lanlink.shopping.payment.conflict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付收款冲突挂账（P0-B）：渠道已扣款、本地订单却已关闭/已取消/已退款时的事实记录与处置进度。
 *
 * <p>字段与 {@code support/PaymentConflictSchemaInitializer}（22 列）一一对应；
 * 唯一键 {@code (channel, transaction_id)} 决定同一笔渠道流水只能挂账一次，
 * 因此 {@code transaction_id} 由服务层归一化保证非空（缺失时 {@code NA:<orderNo>}）。
 */
@Data
@TableName("t_payment_conflict")
public class PaymentConflict {

    @TableId(type = IdType.AUTO)
    private Long conflictId;

    /** 平台订单号 */
    private String orderNo;

    /** late_pay_after_close | duplicate_txn | data_invariant_unknown */
    private String conflictType;

    /** 挂账时订单状态快照：0待发货 1已发货 2已完成 3已取消 4部分发货 5已退款 */
    private Integer orderStatusAtDetect;

    /** 挂账时支付状态快照：0未支付 1已支付 2已退款 */
    private Integer payStatusAtDetect;

    /** 渠道：wechat | alipay | stripe | mock | wallet */
    private String channel;

    /** 渠道交易号；缺失时归一为 NA:&lt;orderNo&gt;（唯一键要求 NOT NULL） */
    private String transactionId;

    /** 渠道实收金额（元） */
    private BigDecimal amount;

    /** 付款用户ID（取订单 userId） */
    private Long payerUserId;

    /** 冲突被发现/挂账时间 */
    private LocalDateTime detectedAt;

    /** OPEN | AUTO_REFUNDING | REFUNDED | FAILED | MANUAL | MANUAL_REFUNDED | MANUAL_CLOSED */
    private String status;

    /** 处置结论：AUTO_REFUND | MANUAL_REFUND | MANUAL_WRITE_OFF */
    private String resolution;

    /** 补偿退款请求单号（渠道幂等键） */
    private String outRefundNo;

    /** 渠道退款流水号 */
    private String refundId;

    /** 自动退款重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因（脱敏） */
    private String lastError;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryAt;

    /** 人工处置人ID */
    private Long resolvedBy;

    /** 人工处置完成时间 */
    private LocalDateTime resolvedAt;

    /** 备注/人工处置说明 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

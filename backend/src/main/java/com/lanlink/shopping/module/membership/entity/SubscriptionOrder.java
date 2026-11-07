package com.lanlink.shopping.module.membership.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅订单（购买/续费支付流水，P1-1）。
 * order_no 以「SUB」前缀与商品订单（纯数字单号）区分单据类型命名空间，/payment/* 按前缀分流。
 * 金额单位（钉死）：amount_cents 为「整数元」（8888 = ¥8,888），与 t_subscription_plan.price_cents 同一语义，
 * 不是分；渠道「分」换算统一在 PaymentService 以 BigDecimal 元 ×100 进行（同 t_order.total_amount 口径）。
 * pay_status：0 待支付 / 1 已支付 / 2 已取消；apply_status：0 未生效 / 1 已驱动订阅生效/顺延（回调重放幂等闸）。
 */
@Data
@TableName("t_subscription_order")
public class SubscriptionOrder {
    @TableId  // 字符串业务单号主键（非自增），口径同 t_order.order_no
    private String orderNo;
    private Long userId;
    private Long planId;
    private String planCode;
    private String planName;
    private Integer amountCents;
    private Integer durationDays;
    private Integer autoRenew;
    private Integer payStatus;
    private String channel;
    private String transactionId;
    private LocalDateTime paidAt;
    private Integer applyStatus;
    private Long renewSubId;
    private Integer retryCount;
    private LocalDateTime createdAt;
}

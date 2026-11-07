package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台服务费台账（只记录服务费，不记录交易本金）。
 * fee_type: BROKERAGE 经纪费 / SERVICE 服务费 / EVALUATION 评价组织费 / SUBSCRIPTION 订阅费。
 * status: PENDING 待支付 / PAID 已支付 / REFUNDED 已退款。
 */
@Data
@TableName("t_tech_service_fee")
public class TechServiceFee {
    @TableId(type = IdType.AUTO)
    private Long feeId;
    private String feeNo;
    private Long contractId;
    private String contractNo;
    private Long projectId;
    private Long payerUserId;
    private String feeType;
    private BigDecimal amount;
    private String status;
    private String payChannel;
    private String externalRef;
    private String invoiceNo;
    private LocalDateTime paidAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

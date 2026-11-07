package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物流运输增值税专用发票表
 */
@Data
@TableName("t_logistics_invoice")
public class LogisticsInvoice {
    @TableId(type = IdType.AUTO)
    private Long invoiceId;
    private String invoiceNo;
    private String invoiceCode;
    private Long providerId;
    private Long settlementId;
    /** special/normal */
    private String invoiceType;
    /** 交通运输业专票税率 6.00% */
    private BigDecimal taxRate;
    private BigDecimal amountWithoutTax;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String sellerName;
    private String sellerTaxNo;
    private String buyerName;
    private String buyerTaxNo;
    private LocalDate invoiceDate;
    private String checkCode;
    private String fileUrl;
    /** pending/verified/failed/cancelled */
    private String verifyStatus;
    private LocalDateTime verifyTime;
    private String verifyMessage;
    /** unapplied/applied */
    private String offsetStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

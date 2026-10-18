package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 物流结算单 */
@Data
@TableName("t_logistics_settlement")
public class LogisticsSettlement {
    @TableId(type = IdType.AUTO)
    private Long settlementId;
    private String settlementNo;
    private Long providerId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer waybillCount;
    private BigDecimal totalFreight;
    private BigDecimal deduction;
    private BigDecimal extraFee;
    private BigDecimal payableAmount;
    /** draft/pending_review/approved/paid */
    private String status;
    private Long reviewerId;
    private String reviewNote;
    private LocalDateTime paidTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}

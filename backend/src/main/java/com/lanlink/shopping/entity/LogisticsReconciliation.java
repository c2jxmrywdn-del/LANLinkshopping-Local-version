package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承运商月结对账单主表
 */
@Data
@TableName("t_logistics_reconciliation")
public class LogisticsReconciliation {
    @TableId(type = IdType.AUTO)
    private Long reconId;
    private String reconNo;
    private Long providerId;
    /** 账单所属周期 YYYY-MM */
    private String billPeriod;
    private Integer totalWaybills;
    private Integer matchedCount;
    private Integer discrepancyCount;
    private BigDecimal systemFreightTotal;
    private BigDecimal carrierBilledTotal;
    private BigDecimal diffAmount;
    /** imported/matching/discrepancy/reconciled/closed */
    private String status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

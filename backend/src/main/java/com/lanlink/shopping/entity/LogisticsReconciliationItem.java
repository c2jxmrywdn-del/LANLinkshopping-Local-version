package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承运商对账逐单勾稽明细表
 */
@Data
@TableName("t_logistics_reconciliation_item")
public class LogisticsReconciliationItem {
    @TableId(type = IdType.AUTO)
    private Long itemId;
    private Long reconId;
    private String waybillNo;
    private String orderNo;
    private Long waybillId;
    private BigDecimal systemFreight;
    private BigDecimal carrierBilledFreight;
    private BigDecimal diffAmount;
    private String diffReason;
    /** matched/diff_tolerated/disputed/resolved/missing_waybill */
    private String matchStatus;
    private Integer epodVerified;
    private String epodUrl;
    private BigDecimal adjustmentAmount;
    private String adjustNote;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

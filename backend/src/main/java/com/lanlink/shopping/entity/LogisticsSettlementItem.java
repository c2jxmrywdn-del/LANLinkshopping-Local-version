package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 物流结算明细 */
@Data
@TableName("t_logistics_settlement_item")
public class LogisticsSettlementItem {
    @TableId(type = IdType.AUTO)
    private Long itemId;
    private Long settlementId;
    private Long waybillId;
    private String waybillNo;
    private BigDecimal freight;
    private String remark;
    private LocalDateTime createTime;
}

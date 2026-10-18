package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 物流异常告警 */
@Data
@TableName("t_logistics_alert")
public class LogisticsAlert {
    @TableId(type = IdType.AUTO)
    private Long alertId;
    private Long waybillId;
    /** delay/reject/return/damage/lost/timeout_no_update */
    private String alertType;
    /** low/medium/high */
    private String alertLevel;
    private String content;
    /** pending/processing/resolved */
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String handleNote;
    private LocalDateTime createTime;
}

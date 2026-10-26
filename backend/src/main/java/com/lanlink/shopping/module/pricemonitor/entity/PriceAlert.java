package com.lanlink.shopping.module.pricemonitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格预警订阅（买家降价提醒 / 商户价格异动提醒）
 * condition_type: BELOW 低于 / ABOVE 高于 / DROP_PCT 降幅% / RISE_PCT 涨幅%
 */
@Data
@TableName("t_price_alert")
public class PriceAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long prodId;
    private String conditionType;
    private BigDecimal threshold;
    private Integer status;
    private LocalDateTime lastTriggeredTime;
    private LocalDateTime createTime;
}

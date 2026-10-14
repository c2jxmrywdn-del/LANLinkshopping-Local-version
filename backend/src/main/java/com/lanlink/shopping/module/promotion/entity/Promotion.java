package com.lanlink.shopping.module.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 促销规则（营销中台·促销系统）
 * type: full_reduce 满减 / discount 折扣
 * scope: all 全场 / ind 行业
 * status: 0 停用 / 1 启用
 */
@Data
@TableName("t_promotion")
public class Promotion {
    @TableId(type = IdType.AUTO)
    private Long promoId;
    private String title;
    private String type;
    private BigDecimal threshold;
    private BigDecimal benefitAmount;
    private BigDecimal discountRate;
    private String scope;
    private Long indId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 是否对指定行业订单适用 */
    public boolean applicable(Long orderIndId) {
        return "all".equals(scope) || (indId != null && indId.equals(orderIndId));
    }
}

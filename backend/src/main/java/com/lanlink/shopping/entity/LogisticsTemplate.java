package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运费模板 */
@Data
@TableName("t_logistics_template")
public class LogisticsTemplate {
    @TableId(type = IdType.AUTO)
    private Long templateId;
    private String templateName;
    private Long providerId;
    /** weight按重量 volume按体积 fixed固定 piece按件 */
    private String pricingType;
    private BigDecimal firstWeight;
    private BigDecimal firstPrice;
    private BigDecimal continuedWeight;
    private BigDecimal continuedPrice;
    private BigDecimal volumeFactor;
    private BigDecimal freeThreshold;
    /** 区域加价规则 JSON（存储层内部格式，服务层解析，不外发） */
    @JsonIgnore
    private String regionRuleJson;
    private BigDecimal remoteSurcharge;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /** 逻辑删除标记（存储层内部字段，不外发） */
    @JsonIgnore
    private Integer deleted;
}

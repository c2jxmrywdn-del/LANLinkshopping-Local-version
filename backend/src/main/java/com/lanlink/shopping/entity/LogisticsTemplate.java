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
    /** 计费阶梯模式：linear 线性 / tiered 阶梯 / ton 纯吨位 */
    private String tierMode;
    private BigDecimal firstWeight;
    private BigDecimal firstPrice;
    private BigDecimal continuedWeight;
    private BigDecimal continuedPrice;
    private BigDecimal volumeFactor;
    private BigDecimal freeThreshold;
    /** 大宗阶梯区间规则 JSON [{"minWeight":0,"maxWeight":300,"unitPrice":1.5,"basePrice":30.0}, ...] */
    private String tierRuleJson;
    /** 工业增值服务费率池 JSON (叉车/上楼/木架/超重超长) */
    private String vasRuleJson;
    /** 单票最低起运保底价 */
    private BigDecimal minCharge;
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

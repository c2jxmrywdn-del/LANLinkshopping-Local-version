package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_service_product")
public class TechServiceProduct {
    @TableId(type = IdType.AUTO)
    private Long serviceId;
    private String providerType;
    private Long providerId;
    private String category;
    private String title;
    private String subtitle;
    private String description;
    private String deliverables;
    private String priceMode;
    private BigDecimal price;
    private BigDecimal commissionRate;
    private Integer durationDays;
    private String tags;
    private String icon;
    private String highlight;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
}

package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 概念验证 / 中试资源（P1）。
 * resource_type: PILOT 中试 / VERIFICATION 概念验证 / LAB 共享实验室。
 */
@Data
@TableName("t_tech_pilot_resource")
public class TechPilotResource {
    @TableId(type = IdType.AUTO)
    private Long resourceId;
    private String name;
    private String orgName;
    private String region;
    private String resourceType;
    private String capabilities;
    private String equipment;
    private String priceMode;
    private BigDecimal price;
    private String contact;
    private Integer status;
    private Integer sort;
    private LocalDateTime createTime;
}

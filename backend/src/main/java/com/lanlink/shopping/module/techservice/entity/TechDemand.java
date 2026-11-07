package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_demand")
public class TechDemand {
    @TableId(type = IdType.AUTO)
    private Long demandId;
    private String demandNo;
    private Long userId;
    private String title;
    private String industry;
    private String techField;
    private String description;
    private String painPoints;
    private String expectedOutcome;
    private BigDecimal budgetMin;
    private BigDecimal budgetMax;
    private LocalDate deadline;
    private Integer anonymous;
    private String status;
    private Integer viewCount;
    private Integer matchCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

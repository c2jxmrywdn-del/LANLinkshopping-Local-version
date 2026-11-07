package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_project")
public class TechProject {
    @TableId(type = IdType.AUTO)
    private Long projectId;
    private String projectNo;
    private Long userId;
    private String projectType;
    private String title;
    private String stage;
    private String status;
    private Integer progress;
    private Long ownerExpertId;
    private Long brokerId;
    private BigDecimal amount;
    private BigDecimal commissionRate;
    private String sourceType;
    private Long sourceId;
    private String nextAction;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

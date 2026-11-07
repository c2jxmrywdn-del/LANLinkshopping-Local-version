package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_hosting")
public class TechHosting {
    @TableId(type = IdType.AUTO)
    private Long hostingId;
    private String hostingNo;
    private Long userId;
    private Long projectId;
    private String hostingType;
    private Long targetId;
    private String targetName;
    private Long providerOrgId;
    private String agreementNo;
    private String scope;
    private LocalDate startDate;
    private LocalDate endDate;
    private String feeMode;
    private BigDecimal feeAmount;
    private BigDecimal commissionRate;
    private String status;
    private String activationPlan;
    private LocalDateTime lastServiceAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

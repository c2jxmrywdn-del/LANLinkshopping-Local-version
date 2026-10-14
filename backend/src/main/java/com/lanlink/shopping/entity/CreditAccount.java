package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_credit_account")
public class CreditAccount {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long entId;
    private String status;
    private BigDecimal requestedLimit;
    private BigDecimal creditLimit;
    private BigDecimal availableLimit;
    private BigDecimal usedLimit;
    private Integer termDays;
    private String purpose;
    private String riskLevel;
    private String reviewRemark;
    private LocalDateTime applyTime;
    private LocalDateTime approvedTime;
    private LocalDateTime updateTime;
}

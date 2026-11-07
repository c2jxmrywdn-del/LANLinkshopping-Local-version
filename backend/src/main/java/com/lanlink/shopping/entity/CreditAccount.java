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
    /** 第三方脱敏信用评级 AAA/AA/A/B */
    private String riskTier;
    private String reviewRemark;
    /** 第三方权威征信授权 Token (平台零征信数据留存) */
    private String creditGrantToken;
    /** 第三方机构风险评估流水号 */
    private String assessmentRefNo;
    /** 第三方授信额度有效截止时间 */
    private LocalDateTime validUntil;
    /** 基准日利率 (如 0.000300 代表 0.03%/天) */
    private BigDecimal dailyRate;
    /** 对应单利年化利率 APR (如 0.1080 代表 10.80%) */
    private BigDecimal apr;
    /** 免息宽限期天数 (如 3 天) */
    private Integer graceDays;
    private LocalDateTime applyTime;
    private LocalDateTime approvedTime;
    private LocalDateTime updateTime;
}

package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 广告推广计划（商户购买流量推广）。
 * 状态机：pending_payment(待支付) → active(投放中) ⇄ paused(已暂停) → ended(已结束)。
 * ended 细分 end_reason：expired(到期) / budget_exhausted(预算耗尽) / manual(手动结束)。
 * 金额闭环：支付冻结 total_budget → 点击按 CPC 原子扣减 spent → 结束时退回 total_budget - spent。
 */
@Data
@TableName("t_ad_campaign")
public class AdCampaign {
    @TableId(type = IdType.AUTO)
    private Long campaignId;
    private Long merId;
    private Long userId;
    private Long prodId;
    private String planName;
    private BigDecimal totalBudget;
    private BigDecimal spent;
    private Long clicks;
    private Long impressions;
    private BigDecimal cpcPrice;
    private Integer durationDays;
    private String status;
    private String endReason;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal settled;
    private LocalDateTime settleTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}

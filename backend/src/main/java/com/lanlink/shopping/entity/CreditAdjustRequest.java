package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 企业账期调额申请：已生效授信账户可提交调额/调账期申请。
 * 审核期间原授信额度继续可用；通过后按锁内已用额度重算可用额度。
 */
@Data
@TableName("t_credit_adjust_request")
public class CreditAdjustRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long userId;
    private BigDecimal requestedLimit;
    private Integer termDays;
    private String purpose;
    /** PENDING待审 APPROVED通过 REJECTED驳回 WITHDRAWN已撤回 */
    private String status;
    private BigDecimal approvedLimit;
    private Integer approvedTermDays;
    private String reviewRemark;
    private LocalDateTime applyTime;
    private LocalDateTime reviewedTime;
    private LocalDateTime updateTime;
}

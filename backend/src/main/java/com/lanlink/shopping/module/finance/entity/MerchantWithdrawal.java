package com.lanlink.shopping.module.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户提现单（t_merchant_withdrawal）。
 *
 * <p>状态机：0 待审 → 1 已打款（管理端标记打款+凭证）/ 2 驳回（解冻）/ 3 已撤回（商户主动，解冻）。
 * 全部流转走条件更新（WHERE status=前置状态），返回 0 即被并发先行者抢占。
 */
@Data
@TableName("t_merchant_withdrawal")
public class MerchantWithdrawal {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属商户(t_merchant.mer_id) */
    private Long merId;
    /** 提现单号（SequenceService 取号） */
    private String withdrawNo;
    /** 申请金额 */
    private BigDecimal amount;
    /** 申请时点可提余额快照 */
    private BigDecimal balanceSnapshot;
    /** 收款账户户名（对公） */
    private String bankAccountName;
    /** 银行账号掩码（前4后4，全号不入库） */
    private String bankAccountNoMasked;
    /** 开户银行全称（含支行） */
    private String bankName;
    /** 0待审 1已打款 2驳回 3已撤回 */
    private Integer status;
    private String applyNote;
    private String rejectReason;
    /** 审核运营 userId */
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    /** 打款凭证（附件中转 URL） */
    private String voucherUrl;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

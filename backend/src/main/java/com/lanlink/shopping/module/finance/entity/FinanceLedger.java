package com.lanlink.shopping.module.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户财务台账（t_finance_ledger，append-only）：每笔资金变动一行。
 * amount 正入负出，balance_after 落行时计算；历史行一律不 UPDATE/DELETE（审计可追溯）。
 */
@Data
@TableName("t_finance_ledger")
public class FinanceLedger {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属商户(t_merchant.mer_id) */
    private Long merId;
    /** earn/settle/withdraw_freeze/withdraw_paid/withdraw_reject_unrefreeze */
    private String bizType;
    /** 业务单号（提现单号） */
    private String bizNo;
    /** 正入负出 */
    private BigDecimal amount;
    /** 该笔后余额快照 */
    private BigDecimal balanceAfter;
    /** 预留冲正指向（当前提现域固定 NULL） */
    private Long refOrderId;
    private String remark;
    private LocalDateTime createTime;
}

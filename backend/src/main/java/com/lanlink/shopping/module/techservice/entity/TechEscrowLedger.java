package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易本金监管台账（平台不划转资金，只登记外部存管/分账/交易所结算凭证）。
 * direction: IN 买方入金 / OUT 卖方结算；status: PENDING / CONFIRMED / RELEASED。
 */
@Data
@TableName("t_tech_escrow_ledger")
public class TechEscrowLedger {
    @TableId(type = IdType.AUTO)
    private Long ledgerId;
    private String escrowNo;
    private Long dealId;
    private Long contractId;
    private BigDecimal amount;
    private String direction;
    private String provider;
    private String externalRef;
    private String status;
    private LocalDateTime occurredAt;
    private LocalDateTime createTime;
}

package com.lanlink.shopping.payment.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 钱包流水：充值/消费/退款入账，每笔记录变动后余额便于对账 */
@Data
@TableName("t_wallet_log")
public class WalletLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** recharge 充值 / pay 消费 / refund 退款入账 */
    private String changeType;
    /** 变动金额（带符号，消费为负） */
    private BigDecimal amount;
    /** 变动后余额 */
    private BigDecimal balanceAfter;
    private String refOrderNo;
    private String remark;
    private LocalDateTime createTime;
}

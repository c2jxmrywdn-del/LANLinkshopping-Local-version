package com.lanlink.shopping.payment.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户钱包（user_id 唯一；余额变动仅经 WalletService 条件更新，禁止直接改实体落库） */
@Data
@TableName("t_wallet")
public class Wallet {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private BigDecimal balance;
    private LocalDateTime updateTime;
}

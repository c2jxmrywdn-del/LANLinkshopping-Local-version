package com.lanlink.shopping.module.merchantexit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 保证金台账（append-only：只 INSERT，禁 UPDATE/DELETE 历史行）。 */
@Data
@TableName("t_merchant_deposit")
public class MerchantDeposit {
    @TableId(type = IdType.AUTO)
    private Long depositId;
    private Long merId;
    /** PAY 缴纳 / REFUND 退还 / DEDUCT 抵扣 / FORFEIT 罚没 */
    private String type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String remark;
    private LocalDateTime createTime;
}

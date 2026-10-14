package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_credit_repayment")
public class CreditRepayment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long billId;
    private Long userId;
    private BigDecimal amount;
    private String method;
    private String referenceNo;
    private String remark;
    private LocalDateTime createTime;
}

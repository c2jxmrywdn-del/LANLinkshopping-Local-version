package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_credit_bill")
public class CreditBill {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private Long userId;
    private String orderNo;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal outstandingAmount;
    private LocalDate dueDate;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime settledTime;
}

package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_deal")
public class TechDeal {
    @TableId(type = IdType.AUTO)
    private Long dealId;
    private String dealNo;
    private Long projectId;
    private Long achievementId;
    private Long buyerUserId;
    private Long sellerUserId;
    private String dealType;
    private BigDecimal amount;
    private BigDecimal commissionRate;
    private BigDecimal commissionAmount;
    private String paymentMode;
    private String status;
    private String contractNo;
    private String evidenceHash;
    private String escrowRef;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

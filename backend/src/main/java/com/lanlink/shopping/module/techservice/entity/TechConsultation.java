package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_consultation")
public class TechConsultation {
    @TableId(type = IdType.AUTO)
    private Long consultId;
    private Long expertId;
    private Long userId;
    private Long projectId;
    private String topic;
    private String description;
    private String mode;
    private LocalDateTime scheduledAt;
    private Integer durationMin;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

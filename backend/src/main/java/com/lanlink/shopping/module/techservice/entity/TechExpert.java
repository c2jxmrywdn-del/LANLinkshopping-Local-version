package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_expert")
public class TechExpert {
    @TableId(type = IdType.AUTO)
    private Long expertId;
    private Long userId;
    private String expertType;
    private String name;
    private String avatarUrl;
    private String title;
    private String organization;
    private String industries;
    private String domains;
    private String bio;
    private String credentials;
    private Integer verified;
    private String inviteStatus;
    private BigDecimal rating;
    private Integer serviceCount;
    private BigDecimal consultFee;
    private Integer responseHours;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

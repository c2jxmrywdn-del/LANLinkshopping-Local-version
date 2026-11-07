package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_organization")
public class TechOrganization {
    @TableId(type = IdType.AUTO)
    private Long orgId;
    private String name;
    private String orgType;
    private String licenseNo;
    private String qualifications;
    private String logoText;
    private String region;
    private String intro;
    private String website;
    private Integer verified;
    private BigDecimal rating;
    private String partnershipStatus;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

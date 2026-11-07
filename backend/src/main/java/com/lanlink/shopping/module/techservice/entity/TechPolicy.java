package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("t_tech_policy")
public class TechPolicy {
    @TableId(type = IdType.AUTO)
    private Long policyId;
    private String title;
    private String issuer;
    private String docNo;
    private String region;
    private String category;
    private LocalDate publishDate;
    private String summary;
    private String url;
    private String highlight;
    private Integer sort;
    private Integer status;
    private LocalDateTime createTime;
}

package com.lanlink.shopping.module.techservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 履约评价（P1）：验收通过后对专家/经纪人/机构评分，五维各 1-5 分。
 * 同一项目 + 评价对象 + 评价人只能提交一次。
 */
@Data
@TableName("t_tech_review")
public class TechReview {
    @TableId(type = IdType.AUTO)
    private Long reviewId;
    private String reviewNo;
    private String targetType;
    private Long targetId;
    private Long projectId;
    private Long reviewerUserId;
    private Integer scoreResponse;
    private Integer scoreProfessional;
    private Integer scoreDelivery;
    private Integer scoreCommunication;
    private Integer scoreResult;
    private BigDecimal overall;
    private String comment;
    private LocalDateTime createTime;

    /** 展示用派生字段（不入库）：评价对象名称、关联项目标题 */
    @TableField(exist = false)
    private String targetName;
    @TableField(exist = false)
    private String projectTitle;
}

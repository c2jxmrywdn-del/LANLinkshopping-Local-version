package com.lanlink.shopping.module.productreview.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品自动审核留痕（每轮规则/AI/视觉审核一条记录）。
 * 决策值：AUTO_APPROVE 自动通过 / AUTO_REJECT 自动驳回 / MANUAL 转人工 / SUGGEST 影子建议。
 */
@Data
@TableName("t_product_review_record")
public class ProductReviewRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long prodId;
    private String decision;
    private String source;
    private Integer score;
    private String ruleResult;
    private String aiResult;
    private String visionResult;
    private String model;
    private Integer elapsedMs;
    private LocalDateTime createTime;
}

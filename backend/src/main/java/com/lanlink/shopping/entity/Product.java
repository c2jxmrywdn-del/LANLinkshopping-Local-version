package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.lanlink.shopping.common.storage.StorageUrlSerializer;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long prodId;
    private Long merId;
    private Long catId;
    private Long indId;
    private String title;
    private String brand;
    private String spec;
    private BigDecimal price;
    /** 阶梯价JSON（存储层内部格式，服务层解析，不外发） */
    @JsonIgnore
    private String tierPriceJson;
    private Integer stock;
    /**
     * 库内存 object key（如 1001.webp / products/prod_ts.webp，out/storage-mq-plan.md A3/A4）；
     * 出站经 StorageUrlSerializer 拼公开绝对 URL，存量相对路径 /api/uploads/** 与外部 URL 原样兜底，
     * 存量 DB cover_url 不直改（规划 A4 决策）。
     */
    @JsonSerialize(using = StorageUrlSerializer.class)
    private String coverUrl;
    private String detail;
    private Integer status;          // 可售状态 1可售(审核通过) 0不可售
    private Integer reviewStatus;    // 审核状态 0待审核 1通过 2驳回
    private String rejectReason;     // 驳回原因
    private String reviewSource;     // 审核来源 MANUAL人工 AUTO自动
    private Integer autoScore;       // 自动审核评分 0-100
    private LocalDateTime autoReviewedAt; // 最近自动审核时间
    private Integer sales;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 类目名称（非表列，详情接口回填；供前端习惯画像与推荐展示） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String catName;
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}

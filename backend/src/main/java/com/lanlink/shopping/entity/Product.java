package com.lanlink.shopping.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * 阶梯价对外契约形态（P1-17）：入站=商户发布/编辑表单提交，出站=详情/商户编辑回显。
     * 仅由服务层从 tierPriceJson 解析回填（买家侧公开展示）或校验后序列化落库，列表接口不回填（NON_NULL 不出现）。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.List<TierPrice> tierPrices;

    /** 阶梯档位（对外键名即契约：minQty 严格递增、price 递减或相等；区间 [minQty, 下一档)） */
    public record TierPrice(int minQty, BigDecimal price) {
    }

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
    /** 公开展示：仅当所属店铺已审核通过、仍在经营且被标记为平台特邀时为 true。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean platformInvited;
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}

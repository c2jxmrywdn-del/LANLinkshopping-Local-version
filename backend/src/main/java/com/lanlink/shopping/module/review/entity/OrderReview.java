package com.lanlink.shopping.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单维度评价（拍板：独立新表，评价挂订单 + 聚合到商品）。
 *
 * <p>与 {@link ProductReview} 的区别：本表唯一约束是订单号+订单行（一单多品按 t_order_item
 * 拆行），走独立的准入与聚合链路；行维度的旧链路继续由 t_product_review 承载，两表并存互不迁移。
 */
@Data
@TableName("t_order_review")
public class OrderReview {
    @TableId(type = IdType.AUTO)
    private Long reviewId;
    /** 订单号（t_order.order_no） */
    private String orderId;
    /** 订单行ID（t_order_item.item_id，一单多品拆行） */
    private Long orderItemId;
    /** 商品ID（t_order_item.prod_id 冗余） */
    private Long prodId;
    private Long buyerUserId;
    /** 商户ID（商品的归属商户） */
    private Long merId;
    /** 1-5 星 */
    private Integer rating;
    private String content;
    /** 评价图片/附件 JSON 数组 */
    private String attachments;
    /** 1=匿名展示 */
    private Integer isAnonymous;
    /** 0待审 1通过 2驳回 */
    private Integer status;
    private LocalDateTime createdAt;
}

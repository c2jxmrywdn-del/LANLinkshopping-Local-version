package com.lanlink.shopping.module.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 买家商品评价（按订单行维度：一单一品一评）。
 *
 * <p>为什么挂在 order_item 而不是 order：订单可跨商户合单、发货与退款都按行，
 * 整单评价既无法归属商品，也无法算商家评分。
 */
@Data
@TableName("t_product_review")
public class ProductReview {
    @TableId(type = IdType.AUTO)
    private Long reviewId;
    private Long itemId;
    private String orderNo;
    private Long userId;
    private Long merId;
    private Long prodId;
    /** 1-5 星 */
    private Integer rating;
    private String content;
    /** 1=匿名展示 */
    private Integer anonymous;
    /** 0待审 1通过 2隐藏 */
    private Integer status;
    private String merchantReply;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

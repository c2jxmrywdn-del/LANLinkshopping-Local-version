package com.lanlink.shopping.module.review.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单评价展示视图：不返回 buyerUserId，匿名评价连昵称掩码也不给。
 */
public record OrderReviewView(
        Long reviewId,
        String orderId,
        Long orderItemId,
        Long prodId,
        int rating,
        String content,
        List<String> attachments,
        String nickname,
        LocalDateTime createdAt) {
}

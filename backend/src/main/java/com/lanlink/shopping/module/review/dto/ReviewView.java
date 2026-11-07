package com.lanlink.shopping.module.review.dto;

import java.time.LocalDateTime;

/**
 * 评价展示视图：不返回 userId，匿名评价连昵称掩码也不给。
 */
public record ReviewView(
        Long reviewId,
        Long prodId,
        int rating,
        String content,
        String nickname,
        String merchantReply,
        LocalDateTime createTime) {
}

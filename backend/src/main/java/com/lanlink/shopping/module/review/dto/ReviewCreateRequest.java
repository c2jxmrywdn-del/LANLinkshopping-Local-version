package com.lanlink.shopping.module.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交评价入参：商品由订单行推导，客户端只给行 id 与评分内容。
 */
public record ReviewCreateRequest(
        @NotNull(message = "订单行不能为空") Long itemId,
        @NotNull(message = "请给出评分") @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星") Integer rating,
        @Size(max = 500, message = "评价内容最多 500 字") String content,
        Boolean anonymous) {
}

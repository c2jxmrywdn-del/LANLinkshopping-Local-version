package com.lanlink.shopping.module.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.review.dto.ReviewCreateRequest;
import com.lanlink.shopping.module.review.dto.ReviewView;
import com.lanlink.shopping.module.review.service.ProductReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 商品评价接口。
 *
 * <p>读接口挂在 `/product/detail/{id}/reviews` 下：`/product/detail/` 已在
 * AuthInterceptor 白名单里（商品详情公开），这样评价列表天然公开，不需要再放开新前缀；
 * 写接口走 `/order/{orderNo}/review`，需登录且已被 CSRF 覆盖（/order/**）。
 */
@RestController
public class ProductReviewController {

    private final ProductReviewService reviews;

    public ProductReviewController(ProductReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/product/detail/{prodId}/reviews")
    public R<IPage<ReviewView>> list(@PathVariable Long prodId,
                                     @RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "10") long size) {
        return R.ok(reviews.pageByProduct(prodId, page, size));
    }

    @GetMapping("/product/detail/{prodId}/reviews/summary")
    public R<Map<String, Object>> summary(@PathVariable Long prodId) {
        return R.ok(reviews.summary(prodId));
    }

    @PostMapping("/order/{orderNo}/review")
    public R<Long> submit(@PathVariable String orderNo,
                          @Valid @RequestBody ReviewCreateRequest req,
                          HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new com.lanlink.shopping.common.BusinessException(401, "请先登录");
        return R.ok("评价成功", reviews.submit(userId, orderNo, req));
    }
}

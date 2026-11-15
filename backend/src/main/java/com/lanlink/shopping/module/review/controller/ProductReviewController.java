package com.lanlink.shopping.module.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.review.dto.OrderReviewCreateRequest;
import com.lanlink.shopping.module.review.dto.OrderReviewView;
import com.lanlink.shopping.module.review.dto.ReviewCreateRequest;
import com.lanlink.shopping.module.review.dto.ReviewView;
import com.lanlink.shopping.module.review.service.OrderReviewService;
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
 *
 * <p>订单维度评价（t_order_review 新链路）同样复用这两组前缀：
 * 公开读走 `/product/detail/{id}/order-reviews`，登录写走 `/order/{orderNo}/order-review`
 * （限流与 CSRF 的 /order/**、/merchant/** 前缀均已覆盖），商家侧读走 `/merchant/order-reviews`。
 */
@RestController
public class ProductReviewController {

    private final ProductReviewService reviews;
    private final OrderReviewService orderReviews;

    public ProductReviewController(ProductReviewService reviews, OrderReviewService orderReviews) {
        this.reviews = reviews;
        this.orderReviews = orderReviews;
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

    @GetMapping("/order/{orderNo}/reviewed-items")
    public R<java.util.List<Long>> reviewedItems(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new com.lanlink.shopping.common.BusinessException(401, "请先登录");
        return R.ok(reviews.reviewedItemIds(userId, orderNo));
    }

    // ===== 订单维度评价（t_order_review）：拍板的新链路 =====

    /** 买家提交订单评价：校验订单归属+已完成，一行一评，聚合同步 t_product_rating_stat */
    @PostMapping("/order/{orderNo}/order-review")
    public R<Long> submitOrderReview(@PathVariable String orderNo,
                                     @Valid @RequestBody OrderReviewCreateRequest req,
                                     HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new com.lanlink.shopping.common.BusinessException(401, "请先登录");
        return R.ok("评价成功", orderReviews.submit(userId, orderNo, req));
    }

    /** 本人在该单已评过的订单行（订单维度新表口径） */
    @GetMapping("/order/{orderNo}/order-reviewed-items")
    public R<java.util.List<Long>> orderReviewedItems(@PathVariable String orderNo, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new com.lanlink.shopping.common.BusinessException(401, "请先登录");
        return R.ok(orderReviews.reviewedItemIds(userId, orderNo));
    }

    /** 商品详情页订单评价列表（公开读，与旧行级评价同一白名单前缀） */
    @GetMapping("/product/detail/{prodId}/order-reviews")
    public R<IPage<OrderReviewView>> orderReviewList(@PathVariable Long prodId,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "10") long size) {
        return R.ok(orderReviews.pageByProduct(prodId, page, size));
    }

    /** 商品详情页订单评价聚合（公开读） */
    @GetMapping("/product/detail/{prodId}/order-reviews/summary")
    public R<Map<String, Object>> orderReviewSummary(@PathVariable Long prodId) {
        return R.ok(orderReviews.summary(prodId));
    }

    /** 商家查看收到的订单评价：merId 由登录态推导，客户端不可指定 */
    @GetMapping("/merchant/order-reviews")
    public R<IPage<OrderReviewView>> merchantOrderReviews(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        if (userId == null) throw new com.lanlink.shopping.common.BusinessException(401, "请先登录");
        return R.ok(orderReviews.pageByMerchant(userId, page, size));
    }
}

package com.lanlink.shopping.module.review.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.module.review.dto.ReviewCreateRequest;
import com.lanlink.shopping.module.review.dto.ReviewView;
import com.lanlink.shopping.module.review.entity.ProductRatingStat;
import com.lanlink.shopping.module.review.entity.ProductReview;
import com.lanlink.shopping.module.review.mapper.ProductRatingStatMapper;
import com.lanlink.shopping.module.review.mapper.ProductReviewMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家商品评价。
 *
 * <p>准入规则（全部是硬条件，缺一不可）：订单属于本人、已完成、完成时间在评价窗口内、
 * 行属于该订单；一行只能评一次（`uk_review_item` 兜底并发）。评分聚合走增量 upsert，
 * 不与商品表的扣库存热点争锁。
 */
@Service
public class ProductReviewService {

    private static final Logger log = LoggerFactory.getLogger(ProductReviewService.class);
    /** 评价窗口：完成后 30 天内可评（过期不可评，避免"考古式"差评） */
    private static final int WINDOW_DAYS = 30;
    private static final int MAX_PAGE_SIZE = 50;
    /** 订单已完成状态值（与 OrderService 的 order_status=2 同口径） */
    private static final int ORDER_COMPLETED = 2;
    /** 通过即展示；0/2 预留给后续审核与隐藏 */
    private static final int STATUS_VISIBLE = 1;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductReviewMapper reviewMapper;
    private final ProductRatingStatMapper statMapper;

    public ProductReviewService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                                ProductReviewMapper reviewMapper, ProductRatingStatMapper statMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.reviewMapper = reviewMapper;
        this.statMapper = statMapper;
    }

    @Transactional
    public Long submit(Long userId, String orderNo, ReviewCreateRequest req) {
        if (req.rating() == null || req.rating() < 1 || req.rating() > 5) {
            throw new BusinessException(400, "评分需为 1-5 星");
        }
        Order order = orderMapper.selectById(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(404, "订单不存在");
        }
        if (order.getOrderStatus() == null || order.getOrderStatus() != ORDER_COMPLETED) {
            throw new BusinessException(400, "订单完成后才能评价");
        }
        LocalDateTime doneAt = order.getUpdateTime() == null ? order.getCreateTime() : order.getUpdateTime();
        if (doneAt != null && doneAt.isBefore(LocalDateTime.now().minusDays(WINDOW_DAYS))) {
            throw new BusinessException(400, "已超过评价期限（完成后 " + WINDOW_DAYS + " 天内可评）");
        }
        OrderItem item = orderItemMapper.selectById(req.itemId());
        if (item == null || !orderNo.equals(item.getOrderNo())) {
            throw new BusinessException(404, "订单行不存在");
        }

        ProductReview review = new ProductReview();
        review.setItemId(item.getItemId());
        review.setOrderNo(orderNo);
        review.setUserId(userId);
        review.setProdId(item.getProdId());
        review.setRating(req.rating());
        review.setContent(req.content() == null ? null : req.content().trim());
        review.setAnonymous(Boolean.TRUE.equals(req.anonymous()) ? 1 : 0);
        review.setStatus(STATUS_VISIBLE);
        review.setCreateTime(LocalDateTime.now());
        review.setUpdateTime(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // uk_review_item：并发重复提交时只有一条能落库
            throw new BusinessException(409, "该商品已评价过");
        }
        int good = req.rating() >= 4 ? 1 : 0;
        reviewMapper.bumpStat(item.getProdId(), 1, req.rating(), good);
        log.info("[REVIEW] 新增评价 prod={} order={} rating={}", item.getProdId(), orderNo, req.rating());
        return review.getReviewId();
    }

    /** 商品评价列表（仅展示可见评价，按时间倒序） */
    public IPage<ReviewView> pageByProduct(Long prodId, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        IPage<ProductReview> rows = reviewMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<ProductReview>lambdaQuery()
                        .eq(ProductReview::getProdId, prodId)
                        .eq(ProductReview::getStatus, STATUS_VISIBLE)
                        .orderByDesc(ProductReview::getCreateTime)
                        .orderByDesc(ProductReview::getReviewId));
        return rows.convert(this::toView);
    }

    /** 评分汇总：均分、总数、好评率与 1-5 星分布（无评价时返回全 0，前端据此隐藏区块） */
    public Map<String, Object> summary(Long prodId) {
        ProductRatingStat stat = statMapper.selectById(prodId);
        int count = stat == null || stat.getReviewCount() == null ? 0 : stat.getReviewCount();
        long sum = stat == null || stat.getRatingSum() == null ? 0L : stat.getRatingSum();
        int good = stat == null || stat.getGoodCount() == null ? 0 : stat.getGoodCount();

        Map<Integer, Integer> dist = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            dist.put(star, 0);
        }
        for (Map<String, Object> row : reviewMapper.ratingDistribution(prodId)) {
            Object rating = row.get("rating");
            Object c = row.get("c");
            if (rating != null && c != null) {
                dist.put(((Number) rating).intValue(), ((Number) c).intValue());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", count);
        out.put("average", count == 0 ? 0.0 : Math.round(sum * 10.0 / count) / 10.0);
        out.put("goodRate", count == 0 ? 0.0 : Math.round(good * 1000.0 / count) / 10.0);
        out.put("distribution", dist);
        return out;
    }

    private ReviewView toView(ProductReview r) {
        String nickname = Integer.valueOf(1).equals(r.getAnonymous()) ? null : mask(r.getUserId());
        return new ReviewView(r.getReviewId(), r.getProdId(), r.getRating(), r.getContent(),
                nickname, r.getMerchantReply(), r.getCreateTime());
    }

    /** 昵称掩码：不查用户表，避免为展示引入跨模块依赖；只暴露"买家 + 尾号" */
    private String mask(Long userId) {
        if (userId == null) return "买家";
        String s = String.valueOf(userId);
        String tail = s.length() <= 2 ? s : s.substring(s.length() - 2);
        return "买家" + tail;
    }
}

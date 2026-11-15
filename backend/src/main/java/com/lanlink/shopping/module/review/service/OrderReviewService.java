package com.lanlink.shopping.module.review.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.review.dto.OrderReviewCreateRequest;
import com.lanlink.shopping.module.review.dto.OrderReviewView;
import com.lanlink.shopping.module.review.entity.OrderReview;
import com.lanlink.shopping.module.review.entity.ProductRatingStat;
import com.lanlink.shopping.module.review.mapper.OrderReviewMapper;
import com.lanlink.shopping.module.review.mapper.ProductRatingStatMapper;
import com.lanlink.shopping.service.MerchantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单维度评价（拍板的新链路）。
 *
 * <p>准入规则（全部是硬条件，缺一不可）：订单属于本人、状态=已完成、行属于该订单；
 * 一行只能评一次（`uk_order_review_item` 兜底并发）。评价落库后同一事务内以原子增量
 * upsert 聚合到 t_product_rating_stat（事务内 UPDATE 行锁串行并发，防丢计数），
 * 搜索排序读同一张聚合表，口径随写随一致。
 */
@Service
public class OrderReviewService {

    private static final Logger log = LoggerFactory.getLogger(OrderReviewService.class);
    /** 评价窗口：完成后 30 天内可评（过期不可评，避免"考古式"差评） */
    private static final int WINDOW_DAYS = 30;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_ATTACHMENTS = 9;
    private static final int MAX_ATTACHMENT_URL = 500;
    /** 订单已完成状态值（与 OrderService 的 order_status=2 同口径） */
    private static final int ORDER_COMPLETED = 2;
    /** 通过即展示；0/2 预留给审核流 */
    private static final int STATUS_VISIBLE = 1;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final OrderReviewMapper reviewMapper;
    private final ProductRatingStatMapper statMapper;
    private final MerchantService merchantService;
    private final ObjectMapper objectMapper;

    public OrderReviewService(OrderMapper orderMapper, OrderItemMapper orderItemMapper,
                              ProductMapper productMapper, OrderReviewMapper reviewMapper,
                              ProductRatingStatMapper statMapper, MerchantService merchantService,
                              ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.reviewMapper = reviewMapper;
        this.statMapper = statMapper;
        this.merchantService = merchantService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long submit(Long userId, String orderId, OrderReviewCreateRequest req) {
        Order order = orderMapper.selectById(orderId);
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
        OrderItem item = orderItemMapper.selectById(req.orderItemId());
        if (item == null || !orderId.equals(item.getOrderNo())) {
            throw new BusinessException(404, "订单行不存在");
        }

        OrderReview review = new OrderReview();
        review.setOrderId(orderId);
        review.setOrderItemId(item.getItemId());
        review.setProdId(item.getProdId());
        review.setBuyerUserId(userId);
        review.setMerId(resolveMerchantId(item.getProdId()));
        review.setRating(req.rating());
        review.setContent(req.content() == null ? null : req.content().trim());
        review.setAttachments(serializeAttachments(req.attachments()));
        review.setIsAnonymous(Boolean.TRUE.equals(req.anonymous()) ? 1 : 0);
        review.setStatus(STATUS_VISIBLE);
        review.setCreatedAt(LocalDateTime.now());
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // uk_order_review_item：并发重复提交时只有一条能落库
            throw new BusinessException(409, "该订单行已评价过");
        }
        int good = req.rating() >= 4 ? 1 : 0;
        // 聚合与评价同一事务：事务内 upsert 走 t_product_rating_stat 行锁，并发评价在此串行，
        // 增量相加不会互相覆盖；insert 失败整体回滚，聚合与明细永不错位
        reviewMapper.bumpStat(item.getProdId(), 1, req.rating(), good);
        log.info("[ORDER-REVIEW] 新增订单评价 prod={} order={} item={} rating={}",
                item.getProdId(), orderId, item.getItemId(), req.rating());
        return review.getReviewId();
    }

    /** 商品维度订单评价列表（仅展示可见评价，按时间倒序） */
    public IPage<OrderReviewView> pageByProduct(Long prodId, long page, long size) {
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        IPage<OrderReview> rows = reviewMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<OrderReview>lambdaQuery()
                        .eq(OrderReview::getProdId, prodId)
                        .eq(OrderReview::getStatus, STATUS_VISIBLE)
                        .orderByDesc(OrderReview::getCreatedAt)
                        .orderByDesc(OrderReview::getReviewId));
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

    /** 本人在该单已评过的订单行（订单详情据此渲染"已评价"，刷新后不丢失状态） */
    public List<Long> reviewedItemIds(Long userId, String orderId) {
        return reviewMapper.reviewedItemIds(userId, orderId);
    }

    /**
     * 商家查看收到的订单评价：只看自己商户的商品，分页按时间倒序。
     * merId 由登录态推导（t_merchant.user_id → 当前用户），不能由客户端指定。
     */
    public IPage<OrderReviewView> pageByMerchant(Long userId, long page, long size) {
        Long merId = resolveMerchantIdByUser(userId);
        if (merId == null) throw new BusinessException(403, "仅入驻商户可查看");
        long current = Math.max(1, page);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        IPage<OrderReview> rows = reviewMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<OrderReview>lambdaQuery()
                        .eq(OrderReview::getMerId, merId)
                        .eq(OrderReview::getStatus, STATUS_VISIBLE)
                        .orderByDesc(OrderReview::getCreatedAt)
                        .orderByDesc(OrderReview::getReviewId));
        return rows.convert(this::toView);
    }

    private OrderReviewView toView(OrderReview r) {
        String nickname = Integer.valueOf(1).equals(r.getIsAnonymous()) ? null : mask(r.getBuyerUserId());
        return new OrderReviewView(r.getReviewId(), r.getOrderId(), r.getOrderItemId(), r.getProdId(),
                r.getRating(), r.getContent(), deserializeAttachments(r.getAttachments()),
                nickname, r.getCreatedAt());
    }

    /** 昵称掩码：不查用户表，避免为展示引入跨模块依赖；只暴露"买家 + 尾号" */
    private String mask(Long userId) {
        if (userId == null) return "买家";
        String s = String.valueOf(userId);
        String tail = s.length() <= 2 ? s : s.substring(s.length() - 2);
        return "买家" + tail;
    }

    private Long resolveMerchantId(Long prodId) {
        Product p = productMapper.selectById(prodId);
        return p == null ? null : p.getMerId();
    }

    private Long resolveMerchantIdByUser(Long userId) {
        Merchant m = merchantService.getByUser(userId);
        return m == null ? null : m.getMerId();
    }

    private String serializeAttachments(List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        List<String> cleaned = new ArrayList<>();
        for (String a : attachments) {
            if (a == null) continue;
            String url = a.trim();
            if (url.isEmpty()) continue;
            if (url.length() > MAX_ATTACHMENT_URL) {
                throw new BusinessException(400, "评价附件地址过长");
            }
            cleaned.add(url);
        }
        if (cleaned.size() > MAX_ATTACHMENTS) {
            throw new BusinessException(400, "评价附件最多 " + MAX_ATTACHMENTS + " 张");
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new BusinessException(400, "评价附件序列化失败");
        }
    }

    private List<String> deserializeAttachments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}

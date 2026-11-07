package com.lanlink.shopping.module.review.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderItem;
import com.lanlink.shopping.mapper.OrderItemMapper;
import com.lanlink.shopping.mapper.OrderMapper;
import com.lanlink.shopping.module.review.dto.ReviewCreateRequest;
import com.lanlink.shopping.module.review.entity.ProductRatingStat;
import com.lanlink.shopping.module.review.entity.ProductReview;
import com.lanlink.shopping.module.review.mapper.ProductRatingStatMapper;
import com.lanlink.shopping.module.review.mapper.ProductReviewMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 买家评价的准入与聚合口径。
 *
 * <p>核心是"谁能评、评什么"：别人的订单、未完成的订单、不属于该单的订单行、重复评价都必须拒绝；
 * 只有通过准入才写评价并累加商品评分。
 */
class ProductReviewServiceTest {

    private OrderMapper orderMapper;
    private OrderItemMapper itemMapper;
    private ProductReviewMapper reviewMapper;
    private ProductRatingStatMapper statMapper;
    private ProductReviewService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        itemMapper = mock(OrderItemMapper.class);
        reviewMapper = mock(ProductReviewMapper.class);
        statMapper = mock(ProductRatingStatMapper.class);
        service = new ProductReviewService(orderMapper, itemMapper, reviewMapper, statMapper);
    }

    private static Order order(Long userId, int status) {
        Order o = new Order();
        o.setOrderNo("ON-1");
        o.setUserId(userId);
        o.setOrderStatus(status);
        o.setUpdateTime(LocalDateTime.now().minusDays(1));
        return o;
    }

    private static OrderItem item(long itemId, String orderNo, long prodId) {
        OrderItem i = new OrderItem();
        i.setItemId(itemId);
        i.setOrderNo(orderNo);
        i.setProdId(prodId);
        return i;
    }

    @Test
    void rejectsForeignOrderWith404() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(99L, 2));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 5, "好", false)));
        assertEquals(404, e.getCode(), "别人的订单按不存在处理，避免订单号探测");
        verify(reviewMapper, never()).insert(any(ProductReview.class));
    }

    @Test
    void rejectsUnfinishedOrder() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 1));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 5, "好", false)));
        assertEquals(400, e.getCode());
    }

    @Test
    void rejectsExpiredWindow() {
        Order o = order(1L, 2);
        o.setUpdateTime(LocalDateTime.now().minusDays(31));
        when(orderMapper.selectById("ON-1")).thenReturn(o);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 5, "好", false)));
        assertEquals(400, e.getCode());
    }

    @Test
    void rejectsItemFromAnotherOrder() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "OTHER", 500L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 5, "好", false)));
        assertEquals(404, e.getCode());
    }

    @Test
    void successWritesReviewAndBumpsAggregate() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "ON-1", 500L));

        service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 5, "  很好用  ", true));

        ArgumentCaptor<ProductReview> captor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewMapper).insert(captor.capture());
        ProductReview saved = captor.getValue();
        assertEquals(500L, saved.getProdId());
        assertEquals(5, saved.getRating());
        assertEquals("很好用", saved.getContent(), "内容要 trim");
        assertEquals(1, saved.getAnonymous());
        assertEquals(1, saved.getStatus(), "MVP 先直接可见");
        verify(reviewMapper).bumpStat(500L, 1, 5, 1);
    }

    @Test
    void duplicateReviewBecomes409() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "ON-1", 500L));
        when(reviewMapper.insert(any(ProductReview.class))).thenThrow(new DuplicateKeyException("uk_review_item"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new ReviewCreateRequest(10L, 4, "还行", false)));
        assertEquals(409, e.getCode());
        verify(reviewMapper, never()).bumpStat(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void summaryComputesAverageAndGoodRate() {
        ProductRatingStat stat = new ProductRatingStat();
        stat.setProdId(500L);
        stat.setReviewCount(4);
        stat.setRatingSum(17L);   // 5+5+4+3
        stat.setGoodCount(3);     // 5,5,4
        when(statMapper.selectById(500L)).thenReturn(stat);
        when(reviewMapper.ratingDistribution(500L)).thenReturn(List.of(
                Map.of("rating", 5, "c", 2L), Map.of("rating", 3, "c", 1L)));

        Map<String, Object> s = service.summary(500L);

        assertEquals(4, s.get("count"));
        assertEquals(4.3, (Double) s.get("average"), 0.001);
        assertEquals(75.0, (Double) s.get("goodRate"), 0.001);
        @SuppressWarnings("unchecked")
        Map<Integer, Integer> dist = (Map<Integer, Integer>) s.get("distribution");
        assertEquals(2, dist.get(5));
        assertEquals(0, dist.get(4));
        assertEquals(1, dist.get(3));
    }

    @Test
    void summaryOfNeverReviewedProductIsZeroed() {
        when(statMapper.selectById(any())).thenReturn(null);
        when(reviewMapper.ratingDistribution(any())).thenReturn(List.of());
        Map<String, Object> s = service.summary(1L);
        assertEquals(0, s.get("count"));
        assertEquals(0.0, s.get("average"));
        assertEquals(0.0, s.get("goodRate"));
    }
}

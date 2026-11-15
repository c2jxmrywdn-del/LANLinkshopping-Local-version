package com.lanlink.shopping.module.review.service;

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
import com.lanlink.shopping.module.review.entity.OrderReview;
import com.lanlink.shopping.module.review.mapper.OrderReviewMapper;
import com.lanlink.shopping.module.review.mapper.ProductRatingStatMapper;
import com.lanlink.shopping.service.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单维度评价（t_order_review 新链路）的准入与聚合口径。
 *
 * <p>核心是"谁能评、评什么、聚合是否一致"：别人的订单、未完成的订单、不属于该单的订单行、
 * 重复评价都必须拒绝；只有通过准入才写评价并原子累加商品评分聚合。
 */
class OrderReviewServiceTest {

    private OrderMapper orderMapper;
    private OrderItemMapper itemMapper;
    private ProductMapper productMapper;
    private OrderReviewMapper reviewMapper;
    private ProductRatingStatMapper statMapper;
    private MerchantService merchantService;
    private OrderReviewService service;

    @BeforeEach
    void setUp() {
        // 纯单测无 Spring 上下文，lambda 条件构造需要实体元数据缓存（getTargetSql 断言依赖）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                OrderReview.class);
        orderMapper = mock(OrderMapper.class);
        itemMapper = mock(OrderItemMapper.class);
        productMapper = mock(ProductMapper.class);
        reviewMapper = mock(OrderReviewMapper.class);
        statMapper = mock(ProductRatingStatMapper.class);
        merchantService = mock(MerchantService.class);
        service = new OrderReviewService(orderMapper, itemMapper, productMapper, reviewMapper,
                statMapper, merchantService, new ObjectMapper());
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
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "好", null, false)));
        assertEquals(404, e.getCode(), "别人的订单按不存在处理，避免订单号探测");
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    void rejectsUnfinishedOrder() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 1));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "好", null, false)));
        assertEquals(400, e.getCode());
        verify(reviewMapper, never()).bumpStat(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void rejectsExpiredWindow() {
        Order o = order(1L, 2);
        o.setUpdateTime(LocalDateTime.now().minusDays(31));
        when(orderMapper.selectById("ON-1")).thenReturn(o);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "好", null, false)));
        assertEquals(400, e.getCode());
    }

    @Test
    void rejectsItemFromAnotherOrder() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "OTHER", 500L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "好", null, false)));
        assertEquals(404, e.getCode());
    }

    @Test
    void successWritesReviewAndBumpsAggregate() {
        Order o = order(1L, 2);
        when(orderMapper.selectById("ON-1")).thenReturn(o);
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "ON-1", 500L));
        Product p = new Product();
        p.setProdId(500L);
        p.setMerId(7L);
        when(productMapper.selectById(500L)).thenReturn(p);

        service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "  很好用  ",
                List.of("http://a/1.jpg", " http://a/2.jpg "), true));

        ArgumentCaptor<OrderReview> captor = ArgumentCaptor.forClass(OrderReview.class);
        verify(reviewMapper).insert(captor.capture());
        OrderReview saved = captor.getValue();
        assertEquals("ON-1", saved.getOrderId());
        assertEquals(10L, saved.getOrderItemId());
        assertEquals(500L, saved.getProdId());
        assertEquals(7L, saved.getMerId(), "商户ID由商品归属推导");
        assertEquals(5, saved.getRating());
        assertEquals("很好用", saved.getContent(), "内容要 trim");
        assertEquals(1, saved.getIsAnonymous());
        assertEquals(1, saved.getStatus(), "MVP 先直接可见");
        assertEquals(List.of("http://a/1.jpg", "http://a/2.jpg"), parsed(saved.getAttachments()));
        verify(reviewMapper).bumpStat(500L, 1, 5, 1);
    }

    @Test
    void duplicateReviewBecomes409AndSkipsAggregate() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "ON-1", 500L));
        when(reviewMapper.insert(any(OrderReview.class))).thenThrow(new DuplicateKeyException("uk_order_review_item"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 4, "还行", null, false)));
        assertEquals(409, e.getCode());
        verify(reviewMapper, never()).bumpStat(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void rejectsTooManyAttachments() {
        when(orderMapper.selectById("ON-1")).thenReturn(order(1L, 2));
        when(itemMapper.selectById(10L)).thenReturn(item(10L, "ON-1", 500L));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.submit(1L, "ON-1", new OrderReviewCreateRequest(10L, 5, "好",
                        List.of("u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8", "u9", "u10"), false)));
        assertEquals(400, e.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    void merchantPageRequiresSettledMerchant() {
        when(merchantService.getByUser(1L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.pageByMerchant(1L, 1, 10));
        assertEquals(403, e.getCode(), "未入驻用户拿不到商户视角");
        verify(reviewMapper, never()).selectPage(any(), any());
    }

    @Test
    void merchantPageResolvesMerIdFromLoginUser() {
        Merchant m = new Merchant();
        m.setMerId(7L);
        when(merchantService.getByUser(1L)).thenReturn(m);
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderReview>(1, 10);
        when(reviewMapper.selectPage(any(), any())).thenReturn(page);

        service.pageByMerchant(1L, 1, 10);

        // 查询条件里必须带 mer_id=7：商家视角永远按登录态推导，不信客户端
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<OrderReview>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(reviewMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("mer_id"), "商户视角必须按 mer_id 过滤: " + sql);
    }

    @Test
    void summaryComputesAverageAndGoodRate() {
        com.lanlink.shopping.module.review.entity.ProductRatingStat stat =
                new com.lanlink.shopping.module.review.entity.ProductRatingStat();
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

    @Test
    void reviewedItemIdsAlwaysNarrowByRequestingUser() {
        when(reviewMapper.reviewedItemIds(7L, "ON-1")).thenReturn(List.of(11L, 12L));
        assertEquals(List.of(11L, 12L), service.reviewedItemIds(7L, "ON-1"));
        // 归属收窄必须落在查询条件上：查别人的单只会得到空列表，而不是泄漏其评价行
        verify(reviewMapper).reviewedItemIds(7L, "ON-1");
    }

    @Test
    void anonymousViewHidesNickname() throws Exception {
        OrderReview r = new OrderReview();
        r.setReviewId(1L);
        r.setOrderId("ON-1");
        r.setOrderItemId(10L);
        r.setProdId(500L);
        r.setBuyerUserId(123456L);
        r.setRating(5);
        r.setContent("好");
        r.setAttachments("[\"http://a/1.jpg\"]");
        r.setIsAnonymous(1);
        r.setCreatedAt(LocalDateTime.now());
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderReview>(1, 10);
        page.setRecords(List.of(r));
        page.setTotal(1);
        when(reviewMapper.selectPage(any(), any())).thenReturn(page);

        var out = service.pageByProduct(500L, 1, 10);
        assertNull(out.getRecords().get(0).nickname(), "匿名评价不返回昵称");
        assertEquals(List.of("http://a/1.jpg"), out.getRecords().get(0).attachments());
    }

    private List<String> parsed(String json) {
        try {
            return new ObjectMapper().readValue(json, new ObjectMapper().getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

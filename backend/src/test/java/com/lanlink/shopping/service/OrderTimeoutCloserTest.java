package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.mapper.OrderMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时关单调度器：批量扫描、逐条隔离、结果计数。
 *
 * <p>这里只测编排逻辑（谁被处理、异常是否隔离、计数是否正确）；"只有未支付订单能被关"
 * 由 SQL 条件更新保证，靠真机验证。
 */
class OrderTimeoutCloserTest {

    private OrderMapper orderMapper;
    private OrderService orderService;
    private SimpleMeterRegistry registry;
    private OrderTimeoutCloser closer;

    @BeforeEach
    void setUp() throws Exception {
        orderMapper = mock(OrderMapper.class);
        orderService = mock(OrderService.class);
        registry = new SimpleMeterRegistry();
        closer = new OrderTimeoutCloser(orderMapper, orderService, registry);
        set("enabled", true);
        set("timeoutMinutes", 30L);
    }

    private void set(String field, Object value) throws Exception {
        Field f = OrderTimeoutCloser.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(closer, value);
    }

    private static Order order(String no) {
        Order o = new Order();
        o.setOrderNo(no);
        o.setPayStatus(0);
        o.setOrderStatus(0);
        o.setCreateTime(LocalDateTime.now().minusHours(1));
        return o;
    }

    private double count(String outcome) {
        return registry.get("lanlink.order.autoclose").tag("outcome", outcome).counter().count();
    }

    @Test
    void closesEveryCandidateAndCountsThem() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("A"), order("B")));
        when(orderService.closeUnpaid(anyString())).thenReturn(true);

        int closed = closer.drain(LocalDateTime.now());

        assertEquals(2, closed);
        assertEquals(2.0, count("closed"));
    }

    @Test
    void countsRaceWhenOrderWasPaidOrCancelledMeanwhile() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("A")));
        when(orderService.closeUnpaid("A")).thenReturn(false);

        assertEquals(0, closer.drain(LocalDateTime.now()));
        assertEquals(1.0, count("race"));
    }

    @Test
    void oneFailureDoesNotStopTheBatch() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order("A"), order("B")));
        when(orderService.closeUnpaid("A")).thenThrow(new RuntimeException("db down"));
        when(orderService.closeUnpaid("B")).thenReturn(true);

        int closed = closer.drain(LocalDateTime.now());

        assertEquals(1, closed);
        assertEquals(1.0, count("failed"));
        assertEquals(1.0, count("closed"));
    }

    @Test
    void disabledSweeperTouchesNothing() throws Exception {
        set("enabled", false);

        assertEquals(0, closer.drain(LocalDateTime.now()));
        verify(orderMapper, never()).selectList(any());
    }
}

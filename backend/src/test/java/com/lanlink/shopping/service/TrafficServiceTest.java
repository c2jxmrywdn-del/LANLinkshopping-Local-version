package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.mapper.TrafficMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 商户流量统计单测：口径透传 / 天数上限 / 非商户拦截
 */
class TrafficServiceTest {

    private TrafficMapper trafficMapper;
    private MerchantService merchantService;
    private TrafficService service;

    private Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerId(7L);
        return m;
    }

    @BeforeEach
    void setUp() {
        trafficMapper = mock(TrafficMapper.class);
        merchantService = mock(MerchantService.class);
        service = new TrafficService(trafficMapper, merchantService);
    }

    @Test
    void overviewAggregatesMapperResults() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        when(trafficMapper.countOnSale(7L)).thenReturn(4L);
        when(trafficMapper.countActiveProducts(7L)).thenReturn(2L);
        Map<String, Object> recent = new HashMap<>();
        recent.put("amount", new java.math.BigDecimal("300.00"));
        recent.put("orders", 4L);
        when(trafficMapper.sumRecent(7L, 30)).thenReturn(recent);
        Map<String, Object> prev = new HashMap<>();
        prev.put("amount", new java.math.BigDecimal("200.00"));
        prev.put("orders", 3L);
        when(trafficMapper.sumPrevPeriod(7L, 30, 60)).thenReturn(prev);
        Map<String, Object> total = new HashMap<>();
        total.put("amount", new java.math.BigDecimal("1000.00"));
        total.put("sold", 40L);
        when(trafficMapper.sumTotal(7L)).thenReturn(total);

        Map<String, Object> out = service.overview(2L);
        assertEquals(4L, out.get("onSaleProducts"));
        assertEquals(0, new java.math.BigDecimal("1000.00").compareTo((java.math.BigDecimal) out.get("totalAmount")));
        assertEquals(40L, out.get("totalSold"));
        assertEquals(0, new java.math.BigDecimal("300.00").compareTo((java.math.BigDecimal) out.get("recentAmount")));
        assertEquals(4L, out.get("recentOrders"));
        // 环比：(300-200)/200 = 50%
        assertEquals(50.0, (Double) out.get("chainGrowthPct"), 0.001);
        // 客单价：300/4 = 75.00
        assertEquals(0, new java.math.BigDecimal("75.00").compareTo((java.math.BigDecimal) out.get("avgOrderValue")));
        // 动销率：2/4 = 50.0%
        assertEquals(0, new java.math.BigDecimal("50.0").compareTo((java.math.BigDecimal) out.get("activeRatePct")));
    }

    @Test
    void overviewChainGrowthNullWhenPrevZero() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        when(trafficMapper.countOnSale(7L)).thenReturn(1L);
        when(trafficMapper.countActiveProducts(7L)).thenReturn(0L);
        Map<String, Object> recent = new HashMap<>();
        recent.put("amount", java.math.BigDecimal.ZERO);
        recent.put("orders", 0L);
        when(trafficMapper.sumRecent(7L, 30)).thenReturn(recent);
        when(trafficMapper.sumPrevPeriod(7L, 30, 60)).thenReturn(new HashMap<>()); // 上期 0
        when(trafficMapper.sumTotal(7L)).thenReturn(new HashMap<>());
        Map<String, Object> out = service.overview(2L);
        assertNull(out.get("chainGrowthPct"), "上期销售额为 0 时环比不可计算（应为 null）");
    }

    @Test
    void trendFillsZeroDays() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        // 仅返回 1 天数据，请求 7 天 → 应补零到 7 行且按日期升序
        java.time.LocalDate today = java.time.LocalDate.now();
        Map<String, Object> row = new HashMap<>();
        row.put("date", today.toString());
        row.put("amount", new java.math.BigDecimal("66.00"));
        row.put("orders", 2L);
        when(trafficMapper.trendByDay(7L, 7)).thenReturn(List.of(row));

        List<Map<String, Object>> out = service.trend(2L, 7);
        assertEquals(7, out.size(), "补零后应为 7 天");
        assertEquals(today.minusDays(6).toString(), out.get(0).get("date"));
        assertEquals(0, new java.math.BigDecimal("0").compareTo(new java.math.BigDecimal(String.valueOf(out.get(0).get("amount")))));
        assertEquals(today.toString(), out.get(6).get("date"));
        assertEquals(0, new java.math.BigDecimal("66.00").compareTo(new java.math.BigDecimal(String.valueOf(out.get(6).get("amount")))));
    }

    @Test
    void channelsPassesThrough() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        Map<String, Object> ch = new HashMap<>();
        ch.put("channel", "wallet");
        ch.put("orders", 3L);
        ch.put("amount", new java.math.BigDecimal("150.00"));
        when(trafficMapper.channelStats(7L, 30)).thenReturn(List.of(ch));
        List<Map<String, Object>> out = service.channels(2L, 30);
        assertEquals(1, out.size());
        assertEquals("wallet", out.get(0).get("channel"));
    }

    @Test
    void trendCapsDaysToMax() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        when(trafficMapper.trendByDay(eq(7L), anyInt())).thenReturn(List.of());
        service.trend(2L, 500);   // 请求 500 天 → 收敛到 90
        verify(trafficMapper).trendByDay(7L, 90);
        service.trend(2L, null);  // 默认 30 天
        verify(trafficMapper).trendByDay(7L, 30);
    }

    @Test
    void nonMerchantRejected() {
        when(merchantService.getByUser(2L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.overview(2L));
        verifyNoInteractions(trafficMapper);
    }

    @Test
    void productRankingPassesThrough() {
        when(merchantService.getByUser(2L)).thenReturn(merchant());
        Map<String, Object> row = new HashMap<>();
        row.put("prodId", 1L);
        row.put("amount", new java.math.BigDecimal("66.00"));
        when(trafficMapper.productRanking(7L, 30)).thenReturn(List.of(row));
        List<Map<String, Object>> rows = service.productRanking(2L, 30);
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).get("prodId"));
    }

    // ---- P0-7：eventType=ERROR（sourceType=frontend_error）落库语义 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureInserted() {
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(trafficMapper).insertEvent(captor.capture());
        return captor.getValue();
    }

    private jakarta.servlet.http.HttpServletRequest requestWithUser(Long userId) {
        com.lanlink.shopping.entity.User u = new com.lanlink.shopping.entity.User();
        u.setUserId(userId);
        jakarta.servlet.http.HttpSession session = mock(jakarta.servlet.http.HttpSession.class);
        when(session.getAttribute(any())).thenReturn(u);
        jakarta.servlet.http.HttpServletRequest req = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(req.getSession(anyBoolean())).thenReturn(session);
        return req;
    }

    @Test
    void frontendErrorStoredAsPlatformEventWithCombinedDetail() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("eventType", "ERROR");
        raw.put("sourceType", "frontend_error");
        raw.put("sessionId", "ll-test-session-0001");
        raw.put("pageUrl", "/product/detail/9");
        raw.put("message", "Cannot read properties of undefined");
        raw.put("stack", "TypeError: Cannot read properties of undefined\n    at Mall.vue:120:5");
        raw.put("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        service.track(raw, null);

        Map<String, Object> e = captureInserted();
        assertEquals("ERROR", e.get("eventType"));
        assertEquals("frontend_error", e.get("sourceType"));
        // 平台级事件：merchantId 以 0 哨兵兜底，userId 不回填（零 PII）
        assertEquals(0, ((Number) e.get("merchantId")).longValue());
        assertNull(e.get("userId"));
        String detail = String.valueOf(e.get("sourceDetail"));
        assertTrue(detail.contains("Cannot read properties of undefined"), "detail 应含 message");
        assertTrue(detail.contains("at Mall.vue:120:5"), "detail 应含 stack");
        assertTrue(detail.contains("Mozilla/5.0"), "detail 应含 userAgent");
        assertEquals("/product/detail/9", e.get("pageUrl"));
    }

    @Test
    void errorDetailClippedAt2000Chars() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("eventType", "ERROR");
        raw.put("sourceType", "frontend_error");
        raw.put("sessionId", "ll-test-session-0001");
        raw.put("message", "m".repeat(500));
        raw.put("stack", "s".repeat(1500));
        raw.put("userAgent", "u".repeat(200)); // 合计 2202 → 总护栏截到 2000

        service.track(raw, null);

        Map<String, Object> e = captureInserted();
        assertEquals(2000, String.valueOf(e.get("sourceDetail")).length());
    }

    @Test
    void errorEventRejectsForeignSourceType() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("eventType", "ERROR");
        raw.put("sourceType", "search");
        raw.put("sessionId", "ll-test-session-0001");
        assertThrows(BusinessException.class, () -> service.track(raw, null));
        verifyNoInteractions(trafficMapper);
    }

    @Test
    void unknownEventTypeStillRejected() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("eventType", "FOO");
        raw.put("sessionId", "ll-test-session-0001");
        assertThrows(BusinessException.class, () -> service.track(raw, null));
        verifyNoInteractions(trafficMapper);
    }

    @Test
    void errorEventSkipsUserIdBackfillUnlikeVisit() {
        jakarta.servlet.http.HttpServletRequest req = requestWithUser(88L);

        Map<String, Object> visit = new HashMap<>();
        visit.put("eventType", "VISIT");
        visit.put("sessionId", "ll-test-session-0001");
        service.track(visit, req);
        Map<String, Object> storedVisit = captureInserted();
        assertEquals(88L, ((Number) storedVisit.get("userId")).longValue(), "VISIT 登录态应回填 userId");

        org.mockito.Mockito.clearInvocations(trafficMapper);
        Map<String, Object> error = new HashMap<>();
        error.put("eventType", "ERROR");
        error.put("sourceType", "frontend_error");
        error.put("sessionId", "ll-test-session-0001");
        error.put("message", "boom");
        service.track(error, req);
        Map<String, Object> storedError = captureInserted();
        assertNull(storedError.get("userId"), "ERROR 属运维遥测，不回填 userId（零 PII）");
    }

    @Test
    void businessEventSourceDetailStillClippedTo255() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("eventType", "VISIT");
        raw.put("sessionId", "ll-test-session-0001");
        raw.put("sourceDetail", "k".repeat(300)); // 业务事件保持既有 255 裁剪语义

        service.track(raw, null);

        Map<String, Object> e = captureInserted();
        assertEquals(255, String.valueOf(e.get("sourceDetail")).length());
    }
}

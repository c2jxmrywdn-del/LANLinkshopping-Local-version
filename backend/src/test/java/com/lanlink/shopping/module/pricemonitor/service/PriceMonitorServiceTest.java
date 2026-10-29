package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.service.MessageService;
import com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark;
import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;
import com.lanlink.shopping.module.pricemonitor.entity.PriceHistory;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketBenchmarkMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceAlertMapper;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 价格智能监控单测：留痕/预警触发/24h 防打扰/订阅校验/智能建议等级。
 */
class PriceMonitorServiceTest {

    private PriceHistoryMapper historyMapper;
    private MarketBenchmarkMapper benchmarkMapper;
    private PriceAlertMapper alertMapper;
    private ProductMapper productMapper;
    private IndustryMapper industryMapper;
    private MessageService messageService;
    private com.lanlink.shopping.mapper.MerchantMapper merchantMapper;
    private PriceMonitorService service;

    @BeforeEach
    void setUp() {
        historyMapper = mock(PriceHistoryMapper.class);
        benchmarkMapper = mock(MarketBenchmarkMapper.class);
        alertMapper = mock(PriceAlertMapper.class);
        productMapper = mock(ProductMapper.class);
        industryMapper = mock(IndustryMapper.class);
        messageService = mock(MessageService.class);
        merchantMapper = mock(com.lanlink.shopping.mapper.MerchantMapper.class);
        service = new PriceMonitorService(historyMapper, benchmarkMapper, alertMapper,
                productMapper, industryMapper, messageService, merchantMapper);
    }

    private Product product() {
        Product p = new Product();
        p.setProdId(1L);
        p.setMerId(9L);
        p.setIndId(1L);
        p.setTitle("螺纹钢 HRB400");
        p.setPrice(new BigDecimal("3850.00"));
        return p;
    }

    private PriceAlert alert(String cond, String threshold, LocalDateTime lastTriggered) {
        PriceAlert a = new PriceAlert();
        a.setId(11L);
        a.setUserId(7L);
        a.setProdId(1L);
        a.setConditionType(cond);
        a.setThreshold(new BigDecimal(threshold));
        a.setStatus(1);
        a.setLastTriggeredTime(lastTriggered);
        return a;
    }

    @Test
    void recordPriceChangeWritesHistoryWithPct() {
        service.recordPriceChange(product(), new BigDecimal("4000"), new BigDecimal("3800"), "MERCHANT", null);

        ArgumentCaptor<PriceHistory> cap = ArgumentCaptor.forClass(PriceHistory.class);
        verify(historyMapper).insert(cap.capture());
        assertEquals(0, new BigDecimal("-5.00").compareTo(cap.getValue().getChangePct()));
        assertEquals("MERCHANT", cap.getValue().getSource());
        assertEquals(1L, cap.getValue().getProdId());
    }

    @Test
    void recordSkipsWhenPriceUnchanged() {
        service.recordPriceChange(product(), new BigDecimal("100"), new BigDecimal("100"), "MERCHANT", null);
        verify(historyMapper, never()).insert(any(PriceHistory.class));
    }

    @Test
    void belowAlertTriggersWhenCrossingThreshold() {
        when(alertMapper.selectList(any())).thenReturn(List.of(alert("BELOW", "3900", null)));

        service.recordPriceChange(product(), new BigDecimal("4000"), new BigDecimal("3800"), "MERCHANT", null);

        verify(messageService).send(eq(7L), eq("price"), anyString(), contains("低于"), anyString());
        ArgumentCaptor<PriceAlert> cap = ArgumentCaptor.forClass(PriceAlert.class);
        verify(alertMapper).updateById((PriceAlert) cap.capture());        assertNotNull(cap.getValue().getLastTriggeredTime());
    }

    @Test
    void belowAlertSkipsWithin24hAntispam() {
        when(alertMapper.selectList(any()))
                .thenReturn(List.of(alert("BELOW", "3900", LocalDateTime.now().minusHours(2))));

        service.recordPriceChange(product(), new BigDecimal("4000"), new BigDecimal("3800"), "MERCHANT", null);

        verify(messageService, never()).send(any(), anyString(), anyString(), anyString(), anyString());
        verify(alertMapper, never()).updateById(any(PriceAlert.class));
    }

    @Test
    void dropPctAlertTriggersOnSteepCut() {
        when(alertMapper.selectList(any())).thenReturn(List.of(alert("DROP_PCT", "8", null)));

        // 4000 → 3600 = -10% 降幅 ≥ 8%
        service.recordPriceChange(product(), new BigDecimal("4000"), new BigDecimal("3600"), "MERCHANT", null);

        verify(messageService).send(eq(7L), eq("price"), anyString(), contains("降幅"), anyString());
    }

    @Test
    void subscribeValidatesAndDedups() {
        when(productMapper.selectById(1L)).thenReturn(product());

        service.subscribe(7L, 1L, "BELOW", new BigDecimal("3000"));
        ArgumentCaptor<PriceAlert> cap = ArgumentCaptor.forClass(PriceAlert.class);
        verify(alertMapper).insert(cap.capture());
        assertEquals("BELOW", cap.getValue().getConditionType());

        assertThrows(BusinessException.class, () -> service.subscribe(7L, 1L, "HACK", BigDecimal.ONE));
        // 并发重复由唯一键兜底：insert 抛 DuplicateKeyException → 转业务提示
        when(alertMapper.insert(any(PriceAlert.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        assertThrows(BusinessException.class, () -> service.subscribe(7L, 1L, "BELOW", new BigDecimal("3000")));
    }

    @Test
    void analyzeHoldsWhenNearBenchmark() {
        when(productMapper.selectById(1L)).thenReturn(product());
        when(benchmarkMapper.selectList(any())).thenReturn(List.of(
                bench(LocalDate.now().minusDays(2), "3840"),
                bench(LocalDate.now().minusDays(1), "3855"),
                bench(LocalDate.now(), "3860")));

        Map<String, Object> m = service.analyze(1L);
        assertEquals("HOLD", m.get("level"));
        assertEquals(0, new BigDecimal("3860.00").compareTo((BigDecimal) m.get("benchmarkPrice")));
    }

    @Test
    void analyzeUrgentWhenFarAboveBenchmark() {
        when(productMapper.selectById(1L)).thenReturn(product());
        when(benchmarkMapper.selectList(any())).thenReturn(List.of(
                bench(LocalDate.now().minusDays(1), "3000"),
                bench(LocalDate.now(), "3000")));

        Map<String, Object> m = service.analyze(1L);
        assertEquals("URGENT", m.get("level"));
        assertTrue(String.valueOf(m.get("advice")).contains("下调"));
    }

    @Test
    void refreshBenchmarksIdempotentPerIndustry() {
        when(productMapper.distinctIndustryIds()).thenReturn(List.of(1L));
        when(benchmarkMapper.avgPriceByIndustry(1L)).thenReturn(new BigDecimal("3900.00"));
        when(benchmarkMapper.selectOne(any())).thenReturn(null); // 无历史基准 & 今日不存在

        int n = service.refreshBenchmarks();

        assertEquals(1, n);
        ArgumentCaptor<MarketBenchmark> cap = ArgumentCaptor.forClass(MarketBenchmark.class);
        verify(benchmarkMapper).insert(cap.capture());
        assertEquals(0, new BigDecimal("3900.00").compareTo(cap.getValue().getBenchmark()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cap.getValue().getChangePct()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trendBuildsContinuousOhlcAndRange() {
        Product p = product();
        p.setSales(300);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(historyMapper.dailyLastPrices(1L)).thenReturn(List.of());
        when(benchmarkMapper.selectList(any())).thenReturn(List.of());

        LocalDate today = LocalDate.now();
        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("day", today.minusDays(2).toString());
        d1.put("open", new BigDecimal("90"));
        d1.put("high", new BigDecimal("100"));
        d1.put("low", new BigDecimal("90"));
        d1.put("close", new BigDecimal("95"));
        d1.put("trades", 2);
        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("day", today.minusDays(1).toString());
        d2.put("open", new BigDecimal("95"));
        d2.put("high", new BigDecimal("100"));
        d2.put("low", new BigDecimal("95"));
        d2.put("close", new BigDecimal("100"));
        d2.put("trades", 1);
        when(historyMapper.dailyOhlc(1L)).thenReturn(List.of(d1, d2));

        Map<String, Object> m = service.trend(1L, 7);

        List<Map<String, Object>> ohlc = (List<Map<String, Object>>) m.get("ohlc");
        assertEquals(7, ohlc.size());
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) ohlc.get(6).get("close")));
        Map<String, Object> lowest = (Map<String, Object>) m.get("lowest");
        Map<String, Object> highest = (Map<String, Object>) m.get("highest");
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) lowest.get("price")));
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) highest.get("price")));
        assertEquals(0, new BigDecimal("11.11").compareTo((BigDecimal) m.get("rangePct")));
        assertEquals(Boolean.TRUE, m.get("volumeSimulated"));
    }

    private MarketBenchmark bench(LocalDate day, String v) {
        MarketBenchmark b = new MarketBenchmark();
        b.setIndId(1L);
        b.setDay(day);
        b.setBenchmark(new BigDecimal(v));
        return b;
    }
}

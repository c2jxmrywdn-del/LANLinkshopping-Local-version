package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper;
import com.lanlink.shopping.support.OutboundModelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 五维因子量化 + 英文规则回退（保证英文界面零中文）。 */
class PriceMonitorAiServiceTest {

    private ProductMapper productMapper;
    private PriceHistoryMapper historyMapper;
    private IndustryMapper industryMapper;
    private MarketNewsService newsService;
    private PriceMonitorService monitorService;
    private OutboundModelConfigService configService;
    private PriceMonitorAiService service;

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        historyMapper = mock(PriceHistoryMapper.class);
        industryMapper = mock(IndustryMapper.class);
        newsService = mock(MarketNewsService.class);
        monitorService = mock(PriceMonitorService.class);
        configService = mock(OutboundModelConfigService.class);
        service = new PriceMonitorAiService(productMapper, historyMapper, industryMapper,
                newsService, monitorService, configService);
    }

    private Product product() {
        Product p = new Product();
        p.setProdId(1L);
        p.setTitle("螺纹钢 HRB400");
        p.setPrice(new BigDecimal("3850.00"));
        return p;
    }

    private MarketNews news(String title, String summary, String category, int importance) {
        MarketNews n = new MarketNews();
        n.setTitle(title);
        n.setSummary(summary);
        n.setCategory(category);
        n.setSource("测试来源");
        n.setImportance(importance);
        n.setPublishedAt(LocalDateTime.now().minusHours(2));
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> factor(Map<String, Object> out, String key) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("factors");
        return list.stream().filter(f -> key.equals(f.get("key"))).findFirst().orElseThrow();
    }

    @Test
    void factorsScoreSupplyTighteningAsBullish() {
        when(productMapper.selectById(1L)).thenReturn(product());
        when(newsService.latest(40)).thenReturn(List.of(
                news("钢材库存下降，供给偏紧", "库存连续下降，现货报价走强", "supply", 3),
                news("下游需求放缓", "订单减少，采购观望", "demand", 2)));

        Map<String, Object> out = service.factors(1L);

        Map<String, Object> supply = factor(out, "supply");
        assertEquals("up", supply.get("impact"));
        assertTrue((int) supply.get("score") >= 18);
        assertTrue((int) supply.get("strength") > 50);
        assertNotNull(out.get("dominant"));
        int idx = (int) out.get("sentimentIndex");
        assertTrue(idx >= 0 && idx <= 100);
        assertEquals(5, ((List<?>) out.get("factors")).size());
    }

    @Test
    void englishRuleAnalysisHasNoChinese() {
        when(productMapper.selectById(1L)).thenReturn(product());
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("level", "HOLD");
        rule.put("advice", "hold");
        when(monitorService.analyze(1L)).thenReturn(rule);
        when(historyMapper.selectList(any())).thenReturn(List.of());
        when(newsService.latest(6)).thenReturn(List.of());
        when(newsService.latest(40)).thenReturn(List.of());
        when(configService.activeChannels()).thenReturn(List.of());

        Map<String, Object> out = service.analysis(1L, "en");

        assertEquals("RULE", out.get("source"));
        StringBuilder text = new StringBuilder();
        text.append(out.get("summary")).append(' ').append(out.get("suggestion")).append(' ')
                .append(out.get("notice"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drivers = (List<Map<String, Object>>) out.get("drivers");
        for (Map<String, Object> d : drivers) {
            text.append(' ').append(d.get("label")).append(' ').append(d.get("evidence"));
        }
        assertFalse(CJK.matcher(text.toString()).find(), "English rule output must not contain Chinese");
    }
}

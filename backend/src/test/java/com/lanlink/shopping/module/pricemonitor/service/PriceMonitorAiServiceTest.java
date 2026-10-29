package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
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

/**
 * 五维因子「按商品」系统性计算 + 英文规则回退（保证英文界面零中文）。
 * 覆盖：同一批资讯下不同商品因子必须不同、资讯来源链接、英文输出零中文。
 */
class PriceMonitorAiServiceTest {

    private ProductMapper productMapper;
    private PriceHistoryMapper historyMapper;
    private IndustryMapper industryMapper;
    private CategoryMapper categoryMapper;
    private MarketNewsService newsService;
    private PriceMonitorService monitorService;
    private OutboundModelConfigService configService;
    private PriceMonitorAiService service;

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final List<String> FACTOR_ORDER =
            List.of("sentiment", "supply", "material", "international", "politics");

    @BeforeEach
    void setUp() {
        productMapper = mock(ProductMapper.class);
        historyMapper = mock(PriceHistoryMapper.class);
        industryMapper = mock(IndustryMapper.class);
        categoryMapper = mock(CategoryMapper.class);
        newsService = mock(MarketNewsService.class);
        monitorService = mock(PriceMonitorService.class);
        configService = mock(OutboundModelConfigService.class);
        service = new PriceMonitorAiService(productMapper, historyMapper, industryMapper,
                newsService, monitorService, configService, new NewsLinkService(), categoryMapper);
        // 默认：无价格历史、无基准、无 AI 渠道（各用例按需覆盖）
        when(historyMapper.selectList(any())).thenReturn(List.of());
        when(monitorService.analyze(any())).thenReturn(new LinkedHashMap<>());
        when(configService.activeChannels()).thenReturn(List.of());
    }

    // ==================== 测试数据 ====================

    private Product product(long prodId, long indId, long catId, String title, String price) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setIndId(indId);
        p.setCatId(catId);
        p.setTitle(title);
        p.setPrice(new BigDecimal(price));
        return p;
    }

    /** 建筑钢材 HRB400 螺纹钢 Φ20（行业 1 建筑 / 分类 1 建筑钢材）。 */
    private Product steelProduct() {
        return product(1L, 1L, 1L, "建筑钢材 HRB400 螺纹钢 Φ20", "3850.00");
    }

    /** 全棉坯布 40*40 133*72（行业 2 纺织 / 分类 4 面料坯布）。 */
    private Product fabricProduct() {
        return product(4L, 2L, 4L, "全棉坯布 40*40 133*72", "8.60");
    }

    private void stubCatalog(long indId, String industryName, long catId, String categoryName) {
        Industry industry = new Industry();
        industry.setIndId(indId);
        industry.setName(industryName);
        when(industryMapper.selectById(indId)).thenReturn(industry);
        Category category = new Category();
        category.setCatId(catId);
        category.setName(categoryName);
        when(categoryMapper.selectById(catId)).thenReturn(category);
    }

    private MarketNews news(String title, String summary, String category, String source,
                            String tags, int importance, int hoursAgo) {
        MarketNews n = new MarketNews();
        n.setTitle(title);
        n.setSummary(summary);
        n.setCategory(category);
        n.setSource(source);
        n.setTags(tags);
        n.setImportance(importance);
        n.setPublishedAt(LocalDateTime.now().minusHours(hoursAgo));
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> factor(Map<String, Object> out, String key) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("factors");
        return list.stream().filter(f -> key.equals(f.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> factorList(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("factors");
    }

    /** 收集所有维度的 newsRefs（便于断言某条来源资讯的链接字段）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allNewsRefs(Map<String, Object> out) {
        List<Map<String, Object>> refs = new java.util.ArrayList<>();
        for (Map<String, Object> f : factorList(out)) {
            Object v = f.get("newsRefs");
            if (v instanceof List<?> l) {
                for (Object o : l) refs.add((Map<String, Object>) o);
            }
        }
        return refs;
    }

    private Map<String, Object> analyzeRule(String benchmarkChangePct, String volatilityPct, int changeCount) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("level", "HOLD");
        rule.put("advice", "hold");
        if (benchmarkChangePct != null) rule.put("benchmarkChangePct", new BigDecimal(benchmarkChangePct));
        if (volatilityPct != null) rule.put("volatilityPct", new BigDecimal(volatilityPct));
        rule.put("changeCount", changeCount);
        return rule;
    }

    // ==================== 既有用例（适配按商品计算） ====================

    @Test
    void factorsScoreSupplyTighteningAsBullish() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        when(newsService.latest(40)).thenReturn(List.of(
                news("钢材库存下降，供给偏紧", "库存连续下降，现货报价走强", "supply", "钢铁资讯",
                        "钢材,库存", 3, 2),
                news("下游需求放缓", "订单减少，采购观望", "demand", "行业观察", null, 2, 5)));

        Map<String, Object> out = service.factors(1L);

        Map<String, Object> supply = factor(out, "supply");
        assertEquals("up", supply.get("impact"));
        assertTrue((int) supply.get("score") >= 18);
        assertTrue((int) supply.get("strength") > 50);
        assertNotNull(out.get("dominant"));
        int idx = (int) out.get("sentimentIndex");
        assertTrue(idx >= 0 && idx <= 100);
        assertEquals(5, factorList(out).size());
        // 新契约字段
        assertNotNull(supply.get("newsScore"));
        assertNotNull(supply.get("behaviorScore"));
        assertNotNull(supply.get("weight"));
        assertTrue((int) supply.get("productNewsCount") >= 1);
        assertFalse(((List<?>) supply.get("productSignals")).isEmpty());
        assertNotNull(supply.get("basis"));
        assertNotNull(out.get("productProfile"));
        assertNotNull(out.get("systematicAnalysis"));
    }

    @Test
    void englishRuleAnalysisHasNoChinese() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        when(newsService.latest(40)).thenReturn(List.of());

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

    // ==================== 新增用例 ====================

    /**
     * 核心回归：同一批市场资讯 + 不同商品（建筑钢材 vs 全棉坯布），
     * 五维因子必须产生差异（score / productNewsCount / evidence 至少一项不同），
     * 修复"每个商品 factors 完全一样"的线上缺陷。
     */
    @Test
    void factorsDifferPerProduct() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        when(productMapper.selectById(4L)).thenReturn(fabricProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        stubCatalog(2L, "纺织行业", 4L, "面料坯布");
        // 同一批资讯：一条钢材、一条纺织、一条泛市场
        List<MarketNews> sharedNews = List.of(
                news("螺纹钢库存下降，钢材现货走强",
                        "主要城市螺纹钢库存连续下降，贸易商报价上调 20-40 元/吨",
                        "supply", "钢铁资讯", "螺纹钢,库存,钢材", 3, 2),
                news("纺织原料：涤纶长丝价格回调，坯布补库放缓",
                        "涤纶 POY 报价回落 1.5%，织造企业开工率下降",
                        "price", "纺织信息网", "涤纶,纺织,坯布", 2, 3),
                news("制造业PMI重回扩张区间，企业补库意愿增强",
                        "制造业采购经理指数回到荣枯线上方", "market", "宏观数据", "PMI,补库", 2, 4));
        when(newsService.latest(40)).thenReturn(sharedNews);
        // 商品 1 有行业基准（价格行为分参与），商品 4 无基准数据（null 安全路径）
        when(monitorService.analyze(1L)).thenReturn(analyzeRule("5.00", "2.00", 3));
        when(monitorService.analyze(4L)).thenReturn(new LinkedHashMap<>());

        Map<String, Object> steel = service.factors(1L);
        Map<String, Object> fabric = service.factors(4L);

        List<Map<String, Object>> steelFactors = factorList(steel);
        List<Map<String, Object>> fabricFactors = factorList(fabric);
        assertEquals(5, steelFactors.size());
        assertEquals(5, fabricFactors.size());
        assertNotEquals(steelFactors, fabricFactors, "different products must not return identical factors");

        boolean anyDimensionDifferent = false;
        for (int i = 0; i < FACTOR_ORDER.size(); i++) {
            assertEquals(FACTOR_ORDER.get(i), steelFactors.get(i).get("key"), "factor order must be stable");
            Map<String, Object> a = steelFactors.get(i);
            Map<String, Object> b = fabricFactors.get(i);
            if (!java.util.Objects.equals(a.get("score"), b.get("score"))
                    || !java.util.Objects.equals(a.get("newsScore"), b.get("newsScore"))
                    || !java.util.Objects.equals(a.get("behaviorScore"), b.get("behaviorScore"))
                    || !java.util.Objects.equals(a.get("productNewsCount"), b.get("productNewsCount"))
                    || !java.util.Objects.equals(a.get("evidence"), b.get("evidence"))) {
                anyDimensionDifferent = true;
            }
        }
        assertTrue(anyDimensionDifferent, "at least one factor must differ between products");
        // 商品 1 的钢材资讯相关度应高于商品 4（商品 4 的纺织资讯反之）
        assertTrue((int) factor(steel, "supply").get("productNewsCount")
                >= (int) factor(fabric, "supply").get("productNewsCount"));
        assertTrue((int) factor(fabric, "material").get("newsScore") < 0,
                "fabric product should price textile weakness as bearish raw-material pressure");

        // 确定性约束：score 恒在 -100..100，顶层系统性分析字段齐备
        for (Map<String, Object> f : steelFactors) {
            int score = (int) f.get("score");
            assertTrue(score >= -100 && score <= 100);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> systematic = (Map<String, Object>) steel.get("systematicAnalysis");
        assertNotNull(systematic.get("factorContributions"));
        assertNotNull(systematic.get("conclusion"));
        assertNotNull(systematic.get("drivers"));
        assertEquals(3, systematic.get("changeCount"));
        // 商品 1 无价格历史（动量 0）、基准 +5% → 相对行业 -5.00 个百分点
        assertEquals(new BigDecimal("-5.00"), systematic.get("relativePct"));
    }

    /** analyze 返回 null / 价格历史返回 null / 资讯返回 null 时必须降级为空数据而不是 NPE。 */
    @Test
    void factorsAreNullSafeWhenDependenciesReturnNull() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        when(monitorService.analyze(1L)).thenReturn(null);
        when(historyMapper.selectList(any())).thenReturn(null);
        when(newsService.latest(40)).thenReturn(null);

        Map<String, Object> out = service.factors(1L);

        assertEquals(5, factorList(out).size());
        for (Map<String, Object> f : factorList(out)) {
            int score = (int) f.get("score");
            assertTrue(score >= -100 && score <= 100);
            assertTrue((int) f.get("strength") >= 12);
            assertEquals(0, (int) f.get("behaviorScore"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> systematic = (Map<String, Object>) out.get("systematicAnalysis");
        assertEquals(0, systematic.get("changeCount"));
        assertEquals(new BigDecimal("0.00"), systematic.get("priceChange30dPct"));
    }

    /** newsRefs 增补来源链接：钢铁资讯 → 官网首页；平台公告 → 站内价格情报台。 */
    @Test
    void newsRefsCarrySourceLinks() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        when(newsService.latest(40)).thenReturn(List.of(
                news("钢材市场库存回落，螺纹钢现货报价小幅走强",
                        "主要城市建材库存连续两周下降，螺纹钢现货报价上调 20-40 元/吨",
                        "supply", "钢铁资讯", "螺纹钢,库存,钢材", 3, 2),
                news("平台公告：价格监控上线，支持行业基准与波动预警",
                        "价格情报台现已支持商品价格走势、行业基准对比与 AI 因果分析",
                        "market", "平台公告", "价格监控,上线", 3, 3)));

        Map<String, Object> out = service.factors(1L);
        List<Map<String, Object>> refs = allNewsRefs(out);
        assertFalse(refs.isEmpty());

        Map<String, Object> steelRef = refs.stream()
                .filter(r -> "钢铁资讯".equals(r.get("source"))).findFirst().orElseThrow();
        assertEquals("http://www.csteelnews.com/", steelRef.get("url"));
        assertEquals("source", steelRef.get("linkType"));
        assertNotNull(steelRef.get("searchUrl"));
        assertNotNull(steelRef.get("relevance"));

        Map<String, Object> internalRef = refs.stream()
                .filter(r -> "平台公告".equals(r.get("source"))).findFirst().orElseThrow();
        assertEquals("/price-monitor", internalRef.get("url"));
        assertEquals("internal", internalRef.get("linkType"));
    }

    /** lang=en 时 summary / conclusion / productSignals 不得含中文（资讯标题、来源可保留中文）。 */
    @Test
    void englishFactorsAreFreeOfChinese() {
        when(productMapper.selectById(1L)).thenReturn(steelProduct());
        stubCatalog(1L, "建筑行业", 1L, "建筑钢材");
        when(newsService.latest(40)).thenReturn(List.of(
                news("钢材市场库存回落，螺纹钢现货报价小幅走强", "螺纹钢现货报价上调 20-40 元/吨",
                        "supply", "钢铁资讯", "螺纹钢,库存,钢材", 3, 2)));
        when(monitorService.analyze(1L)).thenReturn(analyzeRule("5.00", "2.00", 3));

        Map<String, Object> out = service.factors(1L, "en");

        StringBuilder text = new StringBuilder();
        text.append(out.get("summary"));
        @SuppressWarnings("unchecked")
        Map<String, Object> systematic = (Map<String, Object>) out.get("systematicAnalysis");
        text.append(' ').append(systematic.get("conclusion"));
        for (Map<String, Object> f : factorList(out)) {
            text.append(' ').append(f.get("label")).append(' ').append(f.get("basis"));
            Object signals = f.get("productSignals");
            if (signals instanceof List<?> l) {
                for (Object s : l) text.append(' ').append(s);
            }
        }
        assertFalse(CJK.matcher(text.toString()).find(),
                "English factor output must not contain Chinese: " + text);
    }
}

package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketNewsMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 每日资讯信息流测试：基础字段 + 信息源头链接解析
 * （数据库原文 → 平台自产站内 → 来源官网 → 必应检索兜底）。
 */
class MarketNewsServiceTest {

    private static final String BING_PREFIX = "https://www.bing.com/news/search?q=";

    private MarketNewsMapper mapper;
    private MarketNewsService service;
    private final List<MarketNews> rows = new ArrayList<>();
    private Wrapper<MarketNews> capturedWrapper;

    @BeforeAll
    static void initEntityTableInfo() {
        // 纯单元测试不启动 MyBatis，需手动初始化实体元数据，LambdaQueryWrapper 才能解析列名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "market-news-test"), MarketNews.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(MarketNewsMapper.class);
        rows.clear();
        capturedWrapper = null;
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            capturedWrapper = (Wrapper<MarketNews>) invocation.getArgument(0);
            return new ArrayList<>(rows);
        });
        service = new MarketNewsService(mapper, new NewsLinkService());
    }

    private static MarketNews news(long id, String title, String category, String source, String url,
                                   LocalDateTime publishedAt) {
        MarketNews n = new MarketNews();
        n.setId(id);
        n.setTitle(title);
        n.setSummary(title + "，价格与供需出现新变化");
        n.setCategory(category);
        n.setSource(source);
        n.setUrl(url);
        n.setTags("价格,供需");
        n.setImportance(2);
        n.setPublishedAt(publishedAt);
        n.setCreatedAt(publishedAt.plusMinutes(5));
        return n;
    }

    private static MarketNews news(long id, String title, String category, String source, String url) {
        return news(id, title, category, source, url, LocalDateTime.of(2026, 10, 20, 9, 30));
    }

    /** 1) 数据库有 http 原文：优先级最高，但来源官网仍同步返回。 */
    @Test
    void articleUrlWinsWhenDatabaseHasOriginalLink() {
        rows.add(news(1L, "建材价格指数回升", "price", "中国建材报", "  https://news.example.com/article/1  "));

        Map<String, Object> item = service.feed(null, 20).get(0);

        assertEquals("article", item.get("linkType"));
        assertEquals("https://news.example.com/article/1", item.get("url"));
        assertEquals("https://www.cbminfo.com/", item.get("sourceUrl"));
        assertTrue(String.valueOf(item.get("searchUrl")).startsWith(BING_PREFIX));
    }

    /** 2) 平台自产来源（url 为空）：导向站内价格情报台。 */
    @Test
    void platformProducedNewsLinksToInternalPath() {
        rows.add(news(2L, "平台将于本周更新建材价格指数", "policy", "平台公告", null));

        Map<String, Object> item = service.feed(null, 20).get(0);

        assertEquals("internal", item.get("linkType"));
        assertEquals("/price-monitor", item.get("url"));
        assertNull(item.get("sourceUrl"));
        assertTrue(String.valueOf(item.get("searchUrl")).startsWith(BING_PREFIX));
    }

    /** 3) 已收录来源（url 为空）：导向来源官方站点首页。 */
    @Test
    void knownSourceLinksToOfficialHomepage() {
        rows.add(news(3L, "钢材社会库存继续下降", "supply", "钢铁资讯", null));

        Map<String, Object> item = service.feed(null, 20).get(0);

        assertEquals("source", item.get("linkType"));
        assertEquals("http://www.csteelnews.com/", item.get("url"));
        assertEquals("http://www.csteelnews.com/", item.get("sourceUrl"));
        assertTrue(String.valueOf(item.get("searchUrl")).startsWith(BING_PREFIX));
    }

    /** 4) 未收录来源（url 为空）：必应资讯检索兜底，且标题经 URL 编码。 */
    @Test
    void unknownSourceFallsBackToBingNewsSearch() {
        String title = "钢材价格震荡整理";
        String source = "某无名媒体";
        rows.add(news(4L, title, "market", source, null));

        Map<String, Object> item = service.feed(null, 20).get(0);

        assertEquals("search", item.get("linkType"));
        String expectedQuery = URLEncoder.encode(title + " " + source, StandardCharsets.UTF_8);
        assertEquals(BING_PREFIX + expectedQuery, item.get("url"));
        assertEquals(BING_PREFIX + expectedQuery, item.get("searchUrl"));
        assertNull(item.get("sourceUrl"));
        assertFalse(String.valueOf(item.get("url")).contains(title), "链接中不应出现未编码中文");
        assertTrue(String.valueOf(item.get("url")).contains("%E9%92%A2%E6%9D%90"));
    }

    /** 5) feed 返回字段完整且顺序稳定（前端按固定字段消费）。 */
    @Test
    void feedItemContainsAllBaseAndLinkFields() {
        LocalDateTime publishedAt = LocalDateTime.of(2026, 10, 20, 9, 30);
        rows.add(news(7L, "螺纹钢期货小幅收涨", "price", "钢铁资讯", null, publishedAt));

        Map<String, Object> item = service.feed(null, 20).get(0);

        assertEquals(new ArrayList<>(List.of("id", "title", "summary", "category", "source", "tags",
                        "importance", "publishedAt", "createdAt", "url", "sourceUrl", "searchUrl", "linkType")),
                new ArrayList<>(item.keySet()));
        assertEquals(7L, item.get("id"));
        assertEquals("螺纹钢期货小幅收涨", item.get("title"));
        assertEquals("price", item.get("category"));
        assertEquals("钢铁资讯", item.get("source"));
        assertEquals("价格,供需", item.get("tags"));
        assertEquals(2, item.get("importance"));
        assertEquals(publishedAt, item.get("publishedAt"));
        assertEquals(publishedAt.plusMinutes(5), item.get("createdAt"));
        assertNotNull(item.get("url"));
        assertNotNull(item.get("searchUrl"));
        assertEquals("source", item.get("linkType"));
    }

    /** 6) 分类过滤下推给 mapper，limit 原样进入 SQL。 */
    @Test
    void categoryFilterIsPassedThroughToMapper() {
        rows.add(news(5L, "政策速递：建材行业碳达峰方案发布", "policy", "政策速递", null));

        List<Map<String, Object>> items = service.feed("policy", 20);

        assertEquals(1, items.size());
        assertEquals("policy", items.get(0).get("category"));
        assertNotNull(capturedWrapper);
        // getSqlSegment() 触发条件渲染，参数占位符此时才写入 paramNameValuePairs
        String sql = capturedWrapper.getSqlSegment();
        assertTrue(sql.contains("category"), sql);
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) capturedWrapper).getParamNameValuePairs();
        assertTrue(params.containsValue("policy"), "分类条件应下推 SQL");
        assertTrue(sql.contains("LIMIT 20"), sql);
    }

    /** 6b) limit 越界 clamp 到 1..50。 */
    @Test
    void limitIsClampedToOneThroughFifty() {
        service.feed(null, 0);
        assertNotNull(capturedWrapper);
        assertTrue(capturedWrapper.getSqlSegment().contains("LIMIT 1"), capturedWrapper.getSqlSegment());

        service.feed(null, 999);
        assertTrue(capturedWrapper.getSqlSegment().contains("LIMIT 50"), capturedWrapper.getSqlSegment());
    }

    /** 7) 兼容性：latest/list 仍返回实体列表（AI 知识库 ingest 依赖）。 */
    @Test
    void legacyListAndLatestStillReturnEntities() {
        rows.add(news(6L, "有色金属价格上行", "price", "有色行情", null));

        List<MarketNews> latest = service.latest(50);
        assertEquals(1, latest.size());
        assertSame(rows.get(0), latest.get(0));
        assertEquals("有色金属价格上行", service.list("price", 10).get(0).getTitle());
    }

    // ==================== 首页每日资讯：与商品解耦、永不空 ====================

    /** 8) 库内有数据时直接返回，且不需要任何商品上下文。 */
    @Test
    void dailyFeedReturnsStoredNewsWithoutProductContext() {
        rows.add(news(10L, "钢材库存回落", "supply", "钢铁资讯", null));

        List<Map<String, Object>> items = service.dailyFeed(null, 8);

        assertFalse(items.isEmpty());
        // 库内真实资讯必须排在最前；不足 8 条时由平台观察条目补齐
        assertEquals("钢材库存回落", items.get(0).get("title"));
    }

    /** 9) 指定分类无数据时放宽到全量，而不是返回空。 */
    @Test
    void dailyFeedFallsBackToAllCategoriesWhenCategoryEmpty() {
        rows.add(news(11L, "化工品供给偏紧", "supply", "行业观察", null));

        List<Map<String, Object>> items = service.dailyFeed("policy", 8);

        assertFalse(items.isEmpty(), "分类为空时应放宽到全量，避免整块空白");
        assertEquals("化工品供给偏紧", items.get(0).get("title"),
                "真实资讯应排在平台观察兜底之前");
    }

    /** 10) 库内完全为空时返回静态兜底条目（全新库/RSS 尚未抓取）。 */
    @Test
    void dailyFeedNeverReturnsEmptyWhenDatabaseIsEmpty() {
        List<Map<String, Object>> items = service.dailyFeed(null, 8);

        assertFalse(items.isEmpty(), "每日资讯不允许为空，必须有兜底内容");
        assertNotNull(items.get(0).get("title"));
        assertNotNull(items.get(0).get("summary"));
        assertNotNull(items.get(0).get("publishedAt"));
    }

    /** 11) 业务资讯优先：科技/国际综合不应挤掉价格/供给类资讯。 */
    @Test
    void dailyFeedPrefersBusinessCategories() {
        rows.add(news(20L, "某旗舰手机发布", "tech", "科技媒体", null));
        rows.add(news(21L, "国际综合要闻", "international", "国际媒体", null));
        rows.add(news(22L, "钢材价格小幅上行", "price", "钢铁资讯", null));

        List<Map<String, Object>> items = service.dailyFeed(null, 1);

        assertEquals(1, items.size());
        assertEquals("price", items.get(0).get("category"),
                "首页应优先展示业务类资讯而非科技/国际综合");
    }

    // ==================== 按商品定向取资讯（五维因子候选集） ====================

    /**
     * 商品词必须下推 SQL：只取关键词命中的资讯，而不是全局最新 N 条。
     *
     * <p>回归缺陷：因子看板原先只取 latest(40)，宏观快讯占满候选集，
     * 水泥/钢材类商品长期「0 条相关」。
     */
    @Test
    void relatedToPushesProductKeywordsIntoQuery() {
        rows.add(news(30L, "华东水泥价格上调 30 元/吨", "price", "中国水泥网", null));

        List<MarketNews> items = service.relatedTo(List.of("水泥", "钢材"), 20);

        assertEquals(1, items.size());
        assertEquals("华东水泥价格上调 30 元/吨", items.get(0).getTitle());
        assertNotNull(capturedWrapper, "必须真正查库");
        String sql = capturedWrapper.getSqlSegment();
        assertTrue(sql.toLowerCase().contains("title"), sql);
        assertTrue(sql.toLowerCase().contains("summary"), sql);
        assertTrue(sql.contains("LIMIT 20"), sql);
        String params = String.valueOf(paramValues());
        assertTrue(params.contains("水泥"), "关键词必须作为 SQL 参数下推: " + params);
    }

    /** 关键词为空 / 全为单字噪声时不下推 SQL，直接返回空，避免全表扫描。 */
    @Test
    void relatedToReturnsEmptyWithoutUsableKeywords() {
        assertTrue(service.relatedTo(null, 10).isEmpty());
        assertTrue(service.relatedTo(List.of(), 10).isEmpty());
        assertTrue(service.relatedTo(List.of("钢"), 10).isEmpty(),
                "单字关键词下推会命中过多无关稿，应交给内存相关性判定");
        assertNull(capturedWrapper, "无可用关键词时不应发起查询");
    }

    /** 重复关键词只下推一次，且最多 12 个（防止 SQL 过长）。 */
    @Test
    void relatedToDeduplicatesAndCapsKeywords() {
        List<String> many = new ArrayList<>();
        many.add("水泥");
        many.add("水泥");
        for (int i = 0; i < 30; i++) many.add("品类" + i);

        service.relatedTo(many, 10);

        assertNotNull(capturedWrapper);
        String sql = capturedWrapper.getSqlSegment();
        assertTrue(sql.contains("LIMIT 10"), sql);
        String params = String.valueOf(paramValues());
        assertTrue(params.contains("水泥"), params);
        // 12 个关键词 × (title + summary) = 24 个 LIKE 参数，加 1 个时间窗参数
        assertEquals(25, paramValues().size(), "关键词必须去重并截断到 12 个: " + paramValues().size());
    }

    // ==================== 首页资讯噪声过滤 ====================

    /** 家电促销稿含「设备/一体机」等工业词，也必须被拦在首页之外。 */
    @Test
    void dailyFeedRejectsRetailPromotions() {
        rows.add(news(40L, "小户型一步到位：卡萨帝 10kg 热泵洗烘一体机 10498 → 6104 元破冰新低",
                "policy", "IT之家", null));
        rows.add(news(41L, "长城欧拉 7 更多信息公布：双电机布局 + 激光雷达，将于近期上市",
                "policy", "IT之家", null));
        rows.add(news(42L, "国机精工：航天特种轴承相关业务正逐步带来新的增量",
                "market", "36氪", null));

        List<Map<String, Object>> items = service.dailyFeed(null, 5);

        List<String> titles = items.stream().map(i -> String.valueOf(i.get("title"))).toList();
        assertTrue(titles.stream().anyMatch(t -> t.contains("轴承")), "真实工业资讯必须保留: " + titles);
        assertTrue(titles.stream().noneMatch(t -> t.contains("洗烘")), "家电促销不得上首页: " + titles);
        assertTrue(titles.stream().noneMatch(t -> t.contains("欧拉")), "乘用车上市稿不得上首页: " + titles);
    }

    /**
     * 候选池必须一次按时间倒序跨分类取（SQL 下推），
     * 不能"每个分类 LIMIT 30 再按分类顺序拼接"。
     */
    @Test
    void dailyFeedPoolQueriesAcrossCategoriesOrderedByTime() {
        rows.add(news(50L, "钢材库存回落", "supply", "钢铁资讯", null));

        service.dailyFeed(null, 5);

        assertNotNull(capturedWrapper);
        String sql = capturedWrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("category in"), "必须一次跨业务分类查询: " + sql);
        assertTrue(sql.contains("order by published_at desc"), "必须按发布时间倒序: " + sql);
        assertTrue(sql.contains("limit 200"), "候选池需足够大，避免被单分类占满: " + sql);
    }

    /** MyBatis-Plus 把 Lambda 条件参数放在 AbstractWrapper#paramNameValuePairs 里。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paramValues() {
        return ((AbstractWrapper<MarketNews, ?, ?>) capturedWrapper).getParamNameValuePairs();
    }
}

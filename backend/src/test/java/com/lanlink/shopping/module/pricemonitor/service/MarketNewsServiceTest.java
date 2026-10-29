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
}

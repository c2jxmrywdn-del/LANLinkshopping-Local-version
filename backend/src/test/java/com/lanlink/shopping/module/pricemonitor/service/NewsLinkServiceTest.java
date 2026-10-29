package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资讯来源链接解析测试：来源官网目录、平台自产站内落地、必应检索兜底、URL 安全规整。
 *
 * <p>只测纯静态行为（无 Spring 上下文），保证「每条资讯都能导向一个可点击的信息源头」。
 */
class NewsLinkServiceTest {

    private static final String BING_PREFIX = "https://www.bing.com/news/search?q=";

    private final NewsLinkService linkService = new NewsLinkService();

    private static MarketNews news(String title, String source, String url) {
        MarketNews n = new MarketNews();
        n.setTitle(title);
        n.setSource(source);
        n.setUrl(url);
        return n;
    }

    // ---- 来源目录：官网首页 ----

    @Test
    void knownSourceResolvesToOfficialHomepage() {
        assertEquals("http://www.csteelnews.com/", NewsSourceCatalog.homeOf("钢铁资讯"));
        assertEquals("http://www.csteelnews.com/", NewsSourceCatalog.homeOf(" 中国钢铁新闻网 "));
        assertEquals("https://www.dcement.com/", NewsSourceCatalog.homeOf("中国水泥网"));
        assertEquals("https://www.smm.cn/", NewsSourceCatalog.homeOf("有色行情"));
    }

    @Test
    void unknownOrNullSourceHasNoHomepage() {
        assertNull(NewsSourceCatalog.homeOf("某无名媒体"));
        assertNull(NewsSourceCatalog.homeOf(null));
        assertNull(NewsSourceCatalog.homeOf("  "));
    }

    // ---- 来源目录：平台自产来源走站内 ----

    @Test
    void platformProducedSourceIsInternal() {
        assertTrue(NewsSourceCatalog.isInternal("平台公告"));
        assertTrue(NewsSourceCatalog.isInternal("LANLink 数据"));
        assertTrue(NewsSourceCatalog.isInternal("LANLink 调研"));
        assertEquals("/price-monitor", NewsSourceCatalog.internalPathOf("平台公告"));
        assertEquals("/price-monitor", NewsSourceCatalog.internalPathOf("LANLink 数据"));
        assertFalse(NewsSourceCatalog.isInternal("钢铁资讯"));
        assertNull(NewsSourceCatalog.internalPathOf("钢铁资讯"));
        assertNull(NewsSourceCatalog.internalPathOf(null));
    }

    @Test
    void internalSourceDetectionIsCaseInsensitive() {
        assertTrue(NewsSourceCatalog.isInternal("lanlink 数据"));
        assertTrue(NewsSourceCatalog.isInternal("LANLINK"));
        assertTrue(NewsSourceCatalog.isInternal("LanLink 调研"));
        assertTrue(NewsSourceCatalog.isInternal(" 平台公告 "));
        assertEquals("/price-monitor", NewsSourceCatalog.internalPathOf("lanlink 调研"));
        assertFalse(NewsSourceCatalog.isInternal("钢联数据"));
        assertFalse(NewsSourceCatalog.isInternal("某无名媒体"));
    }

    // ---- 检索兜底 ----

    @Test
    void searchUrlPointToBingNewsAndEncodeChinese() {
        String url = NewsSourceCatalog.searchUrl("钢材价格震荡", "某无名媒体");

        String expectedQuery = URLEncoder.encode("钢材价格震荡 某无名媒体", StandardCharsets.UTF_8);
        assertEquals(BING_PREFIX + expectedQuery, url);
        assertTrue(url.startsWith(BING_PREFIX));
        assertFalse(url.contains("钢材价格震荡"), "中文必须经过 URL 编码");
        assertTrue(url.contains("%E9%92%A2%E6%9D%90"), "应包含钢材的 UTF-8 编码");
    }

    @Test
    void searchUrlHasStableFallbackWhenQueryIsBlank() {
        String url = NewsSourceCatalog.searchUrl(null, "  ");

        assertTrue(url.startsWith(BING_PREFIX));
        assertEquals(BING_PREFIX + URLEncoder.encode("大宗商品 价格 行情", StandardCharsets.UTF_8), url);
    }

    // ---- URL 安全规整 ----

    @Test
    void normalizeHttpUrlRejectsUnsafeOrRelativeLinks() {
        assertNull(NewsSourceCatalog.normalizeHttpUrl(null));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("   "));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("javascript:alert(1)"));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("JavaScript:alert(1)"));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("/price-monitor"));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("www.csteelnews.com"));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("ftp://example.com/a"));
        assertNull(NewsSourceCatalog.normalizeHttpUrl("https://example.com/" + "a".repeat(500)));
    }

    @Test
    void normalizeHttpUrlKeepsHttpLinksAndSanitizesWhitespace() {
        assertEquals("https://example.com/a?b=1",
                NewsSourceCatalog.normalizeHttpUrl("  https://example.com/a?b=1  "));
        assertEquals("http://www.csteelnews.com/",
                NewsSourceCatalog.normalizeHttpUrl("http://www.csteelnews.com/"));
        assertEquals("https://example.com/a%20b",
                NewsSourceCatalog.normalizeHttpUrl("https://example.com/a b"));
        assertEquals("https://example.com/a/b",
                NewsSourceCatalog.normalizeHttpUrl("https://example.com\\a\\b"));
    }

    // ---- NewsLinkService 统一出口 ----

    @Test
    void unsafeDatabaseUrlIsIgnoredAndFallsBackToSourceHomepage() {
        Map<String, Object> link = linkService.linkOf(news("钢材库存下降", "钢铁资讯", "javascript:alert(1)"));

        assertEquals("source", link.get("linkType"));
        assertEquals("http://www.csteelnews.com/", link.get("url"));
        assertEquals("http://www.csteelnews.com/", link.get("sourceUrl"));
        assertTrue(String.valueOf(link.get("searchUrl")).startsWith(BING_PREFIX));
    }

    @Test
    void nullNewsStillGetsClickableSearchUrl() {
        Map<String, Object> link = linkService.linkOf(null);

        assertEquals("search", link.get("linkType"));
        assertNotNull(link.get("url"));
        assertNotNull(link.get("searchUrl"));
        assertEquals(link.get("searchUrl"), link.get("url"));
        assertNull(link.get("sourceUrl"));
    }
}

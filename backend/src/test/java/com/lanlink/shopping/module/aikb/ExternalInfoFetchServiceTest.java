package com.lanlink.shopping.module.aikb;

import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 外部资讯抓取单测：只测纯函数（RSS 解析/HTML 清洗/分类/时间）与去重入库分支，
 * 不发起任何真实网络请求（fetchFeed 用子类覆盖）。
 */
class ExternalInfoFetchServiceTest {

    /** 真实风格 RSS：CDATA 链接与 HTML 摘要（36氪式整篇 HTML）+ 实体 + 非 http 链接 */
    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>IT之家</title>
              <link>https://www.ithome.com/</link>
              <item>
                <title><![CDATA[水泥价格连续三周上行，华东地区领涨]]></title>
                <link><![CDATA[https://www.ithome.com/0/123/456.htm]]></link>
                <pubDate>Tue, 27 Oct 2026 08:15:00 +0800</pubDate>
                <description><![CDATA[<p>受<b>错峰生产</b>影响，P.O42.5 袋装水泥现货均价环比上涨 3.2%，华东区域涨幅居前。</p>]]></description>
              </item>
              <item>
                <title>环保督察持续，多地限产通知发布</title>
                <link>https://example.com/news?id=1&amp;source=rss</link>
                <pubDate>2026-10-27 14:40:24  +0800</pubDate>
                <description>多地发布通知，部分化工品供给偏紧。</description>
              </item>
              <item>
                <title>仅有标题的条目</title>
                <link>javascript:alert(1)</link>
                <pubDate>2026-10-27 09:00:00 +0800</pubDate>
              </item>
            </channel></rss>
            """;

    /**
     * 相关性过滤用例：娱乐/消费电子/会员促销 vs 工业/商业资讯（含 block+business 的产业新闻）。
     *
     * <p>发布时间必须落在时效窗内（默认 7 天），否则会被 {@code isFresh} 先行过滤，
     * 测不到相关性分支——因此这里按「昨天」动态生成，避免用例随日历过期。
     */
    private static String relevanceRss() {
        String pubDate = LocalDateTime.now().minusDays(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " +0800";
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>测试源</title>
              <item>
                <title>《刺客信条》新作正式公布，玩法细节曝光</title>
                <link>https://example.com/1</link>
                <pubDate>%1$s</pubDate>
                <description>育碧公布新款游戏，玩家反响热烈。</description>
              </item>
              <item>
                <title>百度网盘会员限时促销，京东 PLUS 年卡五折</title>
                <link>https://example.com/2</link>
                <pubDate>%1$s</pubDate>
                <description>网盘会员与办公软件联合促销活动。</description>
              </item>
              <item>
                <title>某品牌旗舰手机新品发布会定档</title>
                <link>https://example.com/3</link>
                <pubDate>%1$s</pubDate>
                <description>消费数码新品发布会，手机耳机平板齐发。</description>
              </item>
              <item>
                <title>钢材社会库存连续下降，现货价格小幅上行</title>
                <link>https://example.com/4</link>
                <pubDate>%1$s</pubDate>
                <description>螺纹钢报价上调，贸易商采购节奏加快。</description>
              </item>
              <item>
                <title>华为云发布算力方案，上半年营收增长</title>
                <link>https://example.com/5</link>
                <pubDate>%1$s</pubDate>
                <description>企业业务营收与净利均实现增长。</description>
              </item>
            </channel></rss>
            """.formatted(pubDate);
    }

    private static ExternalInfoFetchService newService(MarketNewsService newsService,
                                                       KnowledgeIngestService ingestService,
                                                       EmbeddingService embeddingService,
                                                       boolean llmSummary) {
        return newService(newsService, ingestService, embeddingService, llmSummary, false);
    }

    private static ExternalInfoFetchService newService(MarketNewsService newsService,
                                                       KnowledgeIngestService ingestService,
                                                       EmbeddingService embeddingService,
                                                       boolean llmSummary,
                                                       boolean relevanceFilter) {
        return new ExternalInfoFetchService(newsService, ingestService, embeddingService,
                true, "https://www.ithome.com/rss/", 30, llmSummary, relevanceFilter);
    }

    // ==================== RSS 解析 ====================

    @Test
    void parseRssExtractsFieldsAndCleansHtml() {
        List<Map<String, String>> items = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        assertEquals(3, items.size());
        Map<String, String> first = items.get(0);
        assertEquals("水泥价格连续三周上行，华东地区领涨", first.get("title"));
        assertEquals("https://www.ithome.com/0/123/456.htm", first.get("url"), "CDATA 中的原文链接必须保留");
        assertEquals("IT之家", first.get("source"), "来源取 RSS channel title");
        assertEquals("price", first.get("category"));
        assertEquals("2026-10-27T08:15:00", first.get("publishedAt"), "RFC1123 发布时间解析");
        assertEquals("2", first.get("importance"));
        assertTrue(first.get("tags").contains("价格"), first.get("tags"));
        assertFalse(first.get("summary").contains("<"), "摘要必须去除 HTML 标签");
        assertTrue(first.get("summary").contains("错峰生产"), first.get("summary"));

        Map<String, String> second = items.get(1);
        assertEquals("https://example.com/news?id=1&source=rss", second.get("url"), "XML 实体解码后的链接");
        assertEquals("2026-10-27T14:40:24", second.get("publishedAt"), "36氪式 yyyy-MM-dd HH:mm:ss +0800");
        assertEquals("policy", second.get("category"));

        Map<String, String> third = items.get(2);
        assertEquals("", third.get("url"), "非 http(s) 链接必须丢弃");
    }

    @Test
    void parseRssFallsBackToFeedDomainWhenChannelTitleMissing() {
        String xml = "<rss><channel><item><title>某条目</title>"
                + "<link>https://example.com/a</link></item></channel></rss>";
        List<Map<String, String>> items = ExternalInfoFetchService.parseRss(xml, "https://rss.example.com/feed");
        assertEquals(1, items.size());
        assertEquals("rss.example.com", items.get(0).get("source"));
        assertEquals("market", items.get(0).get("category"), "无关键词时默认 market");
        assertEquals("", items.get(0).get("publishedAt"), "缺失时间由入库时 now 兜底");
    }

    @Test
    void parseRssRejectsDoctypeAndExternalEntities() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<rss><channel><title>T</title><item><title>&xxe;</title></item></channel></rss>";
        List<Map<String, String>> items = ExternalInfoFetchService.parseRss(evil, "https://example.com/rss");
        assertTrue(items.isEmpty(), "禁用 DOCTYPE/外部实体后必须解析失败而不是读取本地文件");
        assertTrue(ExternalInfoFetchService.parseRss(null, "").isEmpty());
        assertTrue(ExternalInfoFetchService.parseRss("", "").isEmpty());
        assertTrue(ExternalInfoFetchService.parseRss("not xml at all", "https://example.com/rss").isEmpty());
    }

    // ==================== HTML 清洗 ====================

    @Test
    void stripHtmlRemovesTagsEntitiesAndCompressesWhitespace() {
        assertEquals("水泥价格上行", ExternalInfoFetchService.stripHtml("<p>水泥<strong>价格</strong>上行</p>"));
        assertEquals("A & B", ExternalInfoFetchService.stripHtml("A &amp; B"));
        assertEquals("你好 世界", ExternalInfoFetchService.stripHtml("<div>你好</div><div>世界</div>"));
        assertEquals("钢价 上涨", ExternalInfoFetchService.stripHtml("<p>钢价&nbsp;&nbsp;上涨</p>"));
        assertEquals("", ExternalInfoFetchService.stripHtml("<script>alert('x')</script>"));
        assertEquals("", ExternalInfoFetchService.stripHtml(null));
        assertEquals("价格 3.2%", ExternalInfoFetchService.stripHtml("<i>价格</i>&nbsp;3.2%"),
                "标签替换为空白后压缩，数字前保留一个空格");
        assertEquals("A&B", ExternalInfoFetchService.stripHtml("A&#38;B"), "数字实体解码");
        assertEquals("“引号”", ExternalInfoFetchService.stripHtml("&ldquo;引号&rdquo;"));
    }

    // ==================== 分类 / 重要度 / 标签 ====================

    @Test
    void classifiesNewsByKeywordPriority() {
        assertEquals("price", ExternalInfoFetchService.classify("水泥价格连续三周上行，华东地区领涨", ""));
        assertEquals("supply", ExternalInfoFetchService.classify("钢材社会库存连续下降", ""));
        assertEquals("policy", ExternalInfoFetchService.classify("环保督察持续，多地限产通知发布", ""));
        assertEquals("international", ExternalInfoFetchService.classify("人民币对美元汇率波动加大", ""));
        assertEquals("demand", ExternalInfoFetchService.classify("制造业补库意愿增强，采购订单回升", ""));
        assertEquals("tech", ExternalInfoFetchService.classify("AI 算力芯片需求回暖", ""));
        assertEquals("market", ExternalInfoFetchService.classify("平台上线新功能", ""));
        assertEquals("tech", ExternalInfoFetchService.classify("", "开源大模型技术迭代加速"));
    }

    @Test
    void importanceAndTagsReflectKeywords() {
        assertEquals(2, ExternalInfoFetchService.importanceOf("纯碱现货报价上调 30 元/吨"));
        assertEquals(1, ExternalInfoFetchService.importanceOf("平台上线新功能"));
        assertEquals("库存,供应", ExternalInfoFetchService.tagsOf("库存下降供应趋紧", ""));
        assertTrue(ExternalInfoFetchService.tagsOf("水泥价格上行", "").startsWith("价格"));
    }

    @Test
    void parsesCommonRssDateFormats() {
        assertEquals(LocalDateTime.of(2026, 10, 27, 8, 15),
                ExternalInfoFetchService.parsePublishedAt("Tue, 27 Oct 2026 08:15:00 +0800"));
        assertEquals(LocalDateTime.of(2026, 10, 27, 14, 40, 24),
                ExternalInfoFetchService.parsePublishedAt("2026-10-27 14:40:24  +0800"));
        assertEquals(LocalDateTime.of(2026, 10, 27, 14, 40),
                ExternalInfoFetchService.parsePublishedAt("2026-10-27T14:40:00+08:00"));
        assertNull(ExternalInfoFetchService.parsePublishedAt("not-a-date"));
        assertNull(ExternalInfoFetchService.parsePublishedAt(""));
    }

    @Test
    void parsesOnlyHttpFeedUrls() {
        assertNotNull(ExternalInfoFetchService.httpUri("https://www.ithome.com/rss/"));
        assertNotNull(ExternalInfoFetchService.httpUri("http://example.com/feed.xml"));
        assertNull(ExternalInfoFetchService.httpUri("file:///etc/passwd"));
        assertNull(ExternalInfoFetchService.httpUri("ftp://example.com/feed"));
        assertNull(ExternalInfoFetchService.httpUri("not a url"));
        assertNull(ExternalInfoFetchService.httpUri(null));
    }

    @Test
    void toNewsMapsParsedRowToEntity() {
        MarketNews news = ExternalInfoFetchService.toNews(
                ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/").get(0));
        assertNotNull(news);
        assertEquals("水泥价格连续三周上行，华东地区领涨", news.getTitle());
        assertEquals("IT之家", news.getSource());
        assertEquals("price", news.getCategory());
        assertEquals(2, news.getImportance());
        assertEquals("https://www.ithome.com/0/123/456.htm", news.getUrl());
        assertEquals(LocalDateTime.of(2026, 10, 27, 8, 15), news.getPublishedAt());
    }

    // ==================== 去重 / 入库 / 向量库刷新 ====================

    @Test
    void skipsDuplicateTitlesAndRefreshesVectorStore() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.existsByTitle(eq("水泥价格连续三周上行，华东地区领涨"))).thenReturn(true);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        when(ingestService.ingestNews()).thenReturn(2);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        ExternalInfoFetchService service = new ExternalInfoFetchService(newsService, ingestService,
                embeddingService, true, "https://www.ithome.com/rss/", 30, false, false) {
            @Override
            List<Map<String, String>> fetchFeed(String feedUrl) {
                return parsed;
            }
        };
        Map<String, Object> stats = service.fetchNow(30);

        assertEquals(3, stats.get("fetched"));
        assertEquals(2, stats.get("inserted"), "重复标题跳过，其余入库");
        assertEquals(1, stats.get("skipped"));
        assertEquals(2, stats.get("ingested"), "有新条目时必须刷新 news 向量源");
        assertEquals(1, stats.get("feeds"));
        assertNotNull(stats.get("lastRunAt"));
        assertEquals(2, ((List<?>) stats.get("items")).size());
        verify(ingestService, times(1)).ingestNews();
        verify(embeddingService, never()).chatText(anyString(), anyString());
    }

    @Test
    void allDuplicatesSkipVectorRefresh() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(true);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");
        ExternalInfoFetchService service = new ExternalInfoFetchService(newsService, ingestService,
                mock(EmbeddingService.class), true, "https://www.ithome.com/rss/", 30, false, false) {
            @Override
            List<Map<String, String>> fetchFeed(String feedUrl) {
                return parsed;
            }
        };

        Map<String, Object> stats = service.fetchNow(30);

        assertEquals(0, stats.get("inserted"));
        assertEquals(3, stats.get("skipped"));
        verify(ingestService, never()).ingestNews();
    }

    @Test
    void respectsPerRunInsertLimit() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                mock(EmbeddingService.class), false);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        Map<String, Object> result = service.storeParsedItems(parsed, 1);

        assertEquals(1, result.get("inserted"));
        assertEquals(0, result.get("skipped"));
    }

    @Test
    void llmSummaryEnhancesShortSummaryButNeverBlocksIngest() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        String generated = "这是一段由本地七B文本模型生成的中文摘要，用于补全过短的资讯摘要内容，"
                + "长度满足八十到一百二十字的要求，失败时保留原始摘要不影响抓取主链路。";
        when(embeddingService.chatText(anyString(), anyString())).thenReturn(generated);
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                embeddingService, true);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        service.storeParsedItems(parsed, 30);

        ArgumentCaptor<MarketNews> captor = ArgumentCaptor.forClass(MarketNews.class);
        verify(newsService, times(3)).saveExternal(captor.capture());
        MarketNews third = captor.getAllValues().stream()
                .filter(n -> "仅有标题的条目".equals(n.getTitle())).findFirst().orElseThrow();
        assertEquals(generated, third.getSummary(), "过短摘要应由本地 7B 补全");
    }

    @Test
    void llmSummaryFailureKeepsOriginalAndStillIngests() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        when(embeddingService.chatText(anyString(), anyString())).thenThrow(new RuntimeException("gateway down"));
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                embeddingService, true);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        service.storeParsedItems(parsed, 30);

        ArgumentCaptor<MarketNews> captor = ArgumentCaptor.forClass(MarketNews.class);
        verify(newsService, times(3)).saveExternal(captor.capture());
        MarketNews third = captor.getAllValues().stream()
                .filter(n -> "仅有标题的条目".equals(n.getTitle())).findFirst().orElseThrow();
        assertEquals("", third.getSummary(), "本地模型失败时保留原始摘要");
    }

    @Test
    void disabledSwitchSkipsFetchWithoutTouchingData() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ExternalInfoFetchService service = new ExternalInfoFetchService(newsService, ingestService,
                embeddingService, false, "https://www.ithome.com/rss/", 30, true, true);

        Map<String, Object> stats = service.fetchNow(30);

        assertEquals(false, stats.get("enabled"));
        assertNotNull(stats.get("notice"));
        verifyNoInteractions(newsService, ingestService, embeddingService);
    }

    // ==================== 业务相关性过滤（娱乐/消费电子/会员促销不入库） ====================

    @Test
    void businessRelevanceRulesAvoidKillingIndustryNews() {
        // 工业/商业信号 → 相关
        assertTrue(ExternalInfoFetchService.isBusinessRelevant("钢材社会库存连续下降", "螺纹钢报价上调，贸易商采购节奏加快"));
        assertTrue(ExternalInfoFetchService.isBusinessRelevant("多地发布限产政策", "水泥与化工品供给收紧"));
        // 纯娱乐/消费电子/会员促销 → 不相关
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("《刺客信条》新作正式公布", "育碧公布新款游戏，玩家反响热烈"));
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("百度网盘会员限时促销", "京东 PLUS 年卡五折"));
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("旗舰手机新品发布会定档", "消费数码耳机平板齐发"));
        // 软性 BLOCK（消费电子）标题：没有核心工业信号时不入库（B2B 平台不追消费数码）
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("华为发布新款手机", "上半年营收与净利均实现增长"));
        // 但核心工业/供应链语境下的标题保留（避免误杀产业新闻）
        assertTrue(ExternalInfoFetchService.isBusinessRelevant(
                "电子元器件厂商补库，面板与连接器交期延长", "上游库存下降、价格上行"));
        // 强娱乐信号 + 商业词：仍必须过滤（修复"泡泡玛特 LABUBU 电影/游戏更新"漏网）
        assertFalse(ExternalInfoFetchService.isBusinessRelevant(
                "泡泡玛特：LABUBU 电影目前还在剧本环节", "公司上半年营收与净利均实现增长，市场关注度高"));
        assertFalse(ExternalInfoFetchService.isBusinessRelevant(
                "《黑神话》游戏销量破千万", "游戏科学营收与市场份额创新高"));
        // 英文外文源
        assertTrue(ExternalInfoFetchService.isBusinessRelevant("Stocks slip as tariffs rise", "Steel demand weakens"));
        assertTrue(ExternalInfoFetchService.isBusinessRelevant("Fed signals rate cuts as investors weigh tariffs", "Treasury yields fall"));
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("New game console launch", "Celebrity streaming show"));
        assertFalse(ExternalInfoFetchService.isBusinessRelevant("New iPhone review: camera and battery test", "A gadget for gamers"));
        // BLOCK 信号识别（日志诊断用）
        assertTrue(ExternalInfoFetchService.hasBlockSignal("手机新品发布会"));
        assertFalse(ExternalInfoFetchService.hasBlockSignal("钢材库存与价格"));
        assertFalse(ExternalInfoFetchService.hasBlockSignal(""));
    }

    @Test
    void relevanceFilterDropsEntertainmentAndConsumerElectronics() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                mock(EmbeddingService.class), false, true);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(relevanceRss(),
                "https://www.ithome.com/rss/");

        Map<String, Object> result = service.storeParsedItems(parsed, 30);

        assertEquals(5, parsed.size());
        assertEquals(2, result.get("inserted"), "钢材/华为营收两条产业资讯应保留");
        assertEquals(3, result.get("skipped"), "skipped 必须计入被过滤条目");
        assertEquals(3, result.get("filtered"), "游戏/网盘会员/手机发布会各过滤 1 条");
        ArgumentCaptor<MarketNews> captor = ArgumentCaptor.forClass(MarketNews.class);
        verify(newsService, times(2)).saveExternal(captor.capture());
        List<String> savedTitles = captor.getAllValues().stream().map(MarketNews::getTitle).toList();
        assertTrue(savedTitles.stream().anyMatch(t -> t.contains("钢材")), savedTitles.toString());
        assertTrue(savedTitles.stream().anyMatch(t -> t.contains("华为云")), savedTitles.toString());
        assertTrue(savedTitles.stream().noneMatch(t -> t.contains("刺客信条") || t.contains("网盘") || t.contains("手机")),
                "娱乐/消费电子/会员促销不得入库: " + savedTitles);
    }

    @Test
    void relevanceFilterStatsIncludedInFetchNow() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        when(ingestService.ingestNews()).thenReturn(2);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(relevanceRss(),
                "https://www.ithome.com/rss/");
        ExternalInfoFetchService service = new ExternalInfoFetchService(newsService, ingestService,
                mock(EmbeddingService.class), true, "https://www.ithome.com/rss/", 30, false, true) {
            @Override
            List<Map<String, String>> fetchFeed(String feedUrl) {
                return parsed;
            }
        };

        Map<String, Object> stats = service.fetchNow(30);

        assertEquals(5, stats.get("fetched"));
        assertEquals(2, stats.get("inserted"));
        assertEquals(3, stats.get("skipped"));
        assertEquals(3, stats.get("filtered"));
        assertEquals(2, stats.get("ingested"), "过滤后仍有新条目时刷新 news 向量源");
        assertEquals(true, stats.get("relevanceFilter"));
        verify(ingestService, times(1)).ingestNews();
    }

    @Test
    void relevanceFilterCanBeDisabled() {
        MarketNewsService newsService = mock(MarketNewsService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                mock(EmbeddingService.class), false, false);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(relevanceRss(),
                "https://www.ithome.com/rss/");

        Map<String, Object> result = service.storeParsedItems(parsed, 30);

        assertEquals(5, result.get("inserted"));
        assertEquals(0, result.get("skipped"));
        assertEquals(0, result.get("filtered"));
    }

    @Test
    void nearDuplicateTitlesAndModelDropDoNotPublish() {
        assertEquals("水泥价格连续三周上行华东地区领涨",
                ExternalInfoFetchService.normalizeTitle("水泥价格连续三周上行，华东地区领涨"));
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        seen.add(ExternalInfoFetchService.normalizeTitle("水泥价格连续三周上行，华东地区领涨"));
        assertTrue(ExternalInfoFetchService.isNearDuplicate(
                ExternalInfoFetchService.normalizeTitle("水泥价格连续三周上行，华东地区领涨！"), seen));
        assertTrue(ExternalInfoFetchService.isModelDrop("DROP"));
        assertTrue(ExternalInfoFetchService.isModelDrop("{\"keep\": false}"));
        assertFalse(ExternalInfoFetchService.isModelDrop("钢材库存下降，贸易商采购放缓，价格随之下调。"));

        MarketNewsService newsService = mock(MarketNewsService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.recentTitles(anyInt())).thenReturn(List.of("水泥价格连续三周上行，华东地区领涨"));
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        when(embeddingService.chatText(anyString(), anyString())).thenReturn("DROP");
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                embeddingService, true);
        List<Map<String, String>> parsed = ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/");

        Map<String, Object> result = service.storeParsedItems(parsed, 30);

        assertEquals(0, result.get("inserted"), "近重复与模型降噪都不应上架");
        verify(newsService, never()).saveExternal(any());
    }

    @Test
    void authoritativeFeedsUseInstitutionNames() {
        assertEquals("新华网", ExternalInfoFetchService.authoritativeSource(
                "http://www.news.cn/fortune/news_fortune.xml", "RSS"));
        assertEquals("中国新闻网", ExternalInfoFetchService.authoritativeSource(
                "https://www.chinanews.com.cn/rss/finance.xml", "RSS"));
        assertEquals("人民网", ExternalInfoFetchService.authoritativeSource(
                "http://www.people.com.cn/rss/finance.xml", "RSS"));
        assertEquals("BBC", ExternalInfoFetchService.authoritativeSource(
                "https://feeds.bbci.co.uk/news/business/rss.xml", "RSS"));
        assertTrue(ExternalInfoFetchService.DEFAULT_FEEDS.contains("news.cn"));
        assertTrue(ExternalInfoFetchService.DEFAULT_FEEDS.contains("chinanews.com.cn"),
                "内置源必须覆盖央媒财经（中新网）");
        assertTrue(ExternalInfoFetchService.DEFAULT_FEEDS.contains("wsj.com")
                        || ExternalInfoFetchService.DEFAULT_FEEDS.contains("dj.com"),
                "内置源必须覆盖国际大宗（WSJ Markets）");
    }

    @Test
    void catalogGateKeepsNewsThatCanAffectMallGoods() {
        assertTrue(ExternalInfoFetchService.affectsCatalog(
                "华东水泥价格上调", "错峰生产后熟料库存下降", List.of("水泥", "钢材")));
        assertFalse(ExternalInfoFetchService.affectsCatalog(
                "旗舰手机新品发布会定档", "消费数码耳机同步开售", List.of("水泥", "钢材", "纺织")));
        assertFalse(ExternalInfoFetchService.affectsCatalog(
                "任何标题", "词表为空时不拦截", List.of()),
                "空词表必须返回 false，由调用方走业务词兜底，不能静默放行");
    }

    // ==================== 时效窗 / 商品闸门（写入侧） ====================

    @Test
    void staleNewsIsRejectedBeforeRelevanceAndDedup() {
        MarketNews stale = new MarketNews();
        stale.setTitle("2019 年钢材市场回顾");
        stale.setPublishedAt(LocalDateTime.now().minusDays(30));
        assertFalse(ExternalInfoFetchService.isFresh(stale, 7), "超过 7 天的旧闻不得上架");
        assertTrue(ExternalInfoFetchService.isFresh(stale, 0), "maxAgeDays=0 表示不限时效");
        MarketNews fresh = new MarketNews();
        fresh.setPublishedAt(LocalDateTime.now().minusHours(2));
        assertTrue(ExternalInfoFetchService.isFresh(fresh, 7));
    }

    @Test
    void toNewsDropsItemsWithoutPublishTime() {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        row.put("title", "无发布时间的历史稿件");
        row.put("summary", "钢材价格");
        assertNull(ExternalInfoFetchService.toNews(row),
                "解析不到发布时间必须丢弃，不能用 now() 伪装成新稿");
    }

    /** URL 内嵌日期必须能修正错误的 pubDate（新华社历史频道把 2022 稿件放进当前 RSS）。 */
    @Test
    void urlDateOverridesMisleadingPublishTime() {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        row.put("title", "多省份基金项目开工率达100%");
        row.put("summary", "基础设施项目建设加快落地");
        row.put("url", "http://www.news.cn/fortune/2022-12/14/c_1129206393.htm");
        // RSS 声称是最近发布，实际是 2022 年稿件
        row.put("publishedAt", LocalDateTime.now().minusHours(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));

        MarketNews news = ExternalInfoFetchService.toNews(row);

        assertNotNull(news);
        assertEquals(2022, news.getPublishedAt().getYear(), "URL 日期必须覆盖错误的 pubDate");
        assertEquals(12, news.getPublishedAt().getMonthValue());
        assertEquals(14, news.getPublishedAt().getDayOfMonth());
        assertFalse(ExternalInfoFetchService.isFresh(news, 7), "2022 年稿件不得进入 7 天时效窗");
    }

    @Test
    void urlDateParsingSupportsCommonLayouts() {
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 0),
                ExternalInfoFetchService.dateFromUrl("https://example.com/2026-11/12/c_1.htm"));
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0),
                ExternalInfoFetchService.dateFromUrl("https://example.com/news/2026-11/index.html"),
                "只有年月时日默认 1 号");
        assertEquals(LocalDateTime.of(2026, 11, 12, 0, 0),
                ExternalInfoFetchService.dateFromUrl("https://example.com/2026/11/12/post.html"));
        assertNull(ExternalInfoFetchService.dateFromUrl("https://example.com/article/123"));
        assertNull(ExternalInfoFetchService.dateFromUrl(null));
        assertNull(ExternalInfoFetchService.dateFromUrl("https://example.com/2099-01/01/x.htm"),
                "未来日期不采信");
    }

    /** 模型把客服话术/商品卡片当摘要写回时必须丢弃，否则会污染商品相关性判定。 */
    @Test
    void modelNoiseIsRejectedSoItCannotPolluteRelevance() {
        assertTrue(ExternalInfoFetchService.isModelNoise(
                "该问题超出知识库范围，可转人工客服（工作日 9:00-18:00）。查询的商品信息如下："
                        + "商品名称：硅酸盐水泥 P.O42.5 袋装 品牌：华新 单价：¥420.00"));
        assertTrue(ExternalInfoFetchService.isModelNoise("抱歉，我无法回答这个问题。"));
        assertTrue(ExternalInfoFetchService.isModelNoise(""));
        assertFalse(ExternalInfoFetchService.isModelNoise(
                "华东水泥价格连续三周上行，错峰生产叠加基建赶工推动袋装水泥报价上调 20 元/吨，"
                        + "预计短期仍将维持强势。"));

        // 端到端：模型返回客服话术时，摘要保持原文而不是被污染
        MarketNewsService newsService = mock(MarketNewsService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(newsService.existsByTitle(anyString())).thenReturn(false);
        when(newsService.saveExternal(any(MarketNews.class))).thenReturn(true);
        when(embeddingService.chatText(anyString(), anyString())).thenReturn(
                "该问题超出知识库范围，可转人工客服。查询的商品信息如下：商品名称：硅酸盐水泥 单价：¥420.00");
        ExternalInfoFetchService service = newService(newsService, mock(KnowledgeIngestService.class),
                embeddingService, true);

        service.storeParsedItems(ExternalInfoFetchService.parseRss(RSS, "https://www.ithome.com/rss/"), 30);

        ArgumentCaptor<MarketNews> captor = ArgumentCaptor.forClass(MarketNews.class);
        verify(newsService, atLeastOnce()).saveExternal(captor.capture());
        for (MarketNews saved : captor.getAllValues()) {
            assertFalse(saved.getSummary().contains("商品名称"),
                    "客服话术不得写入摘要: " + saved.getSummary());
            assertFalse(saved.getSummary().contains("超出知识库范围"), saved.getSummary());
        }
    }

    @Test
    void normalizeTitleStripsTagsMediaSuffixAndWidth() {
        assertEquals("水泥价格连续三周上行华东地区领涨",
                ExternalInfoFetchService.normalizeTitle("【图】水泥价格连续三周上行，华东地区领涨 - 36氪"));
        assertEquals("po425散装水泥", ExternalInfoFetchService.normalizeTitle("Ｐ.Ｏ42.5 散装水泥"),
                "NFKC 全角转半角后去标点");
    }

    @Test
    void nearDuplicateDetectsSameEventWithDifferentWording() {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        seen.add(ExternalInfoFetchService.normalizeTitle("水泥价格连续三周上涨，华东地区领涨"));
        assertTrue(ExternalInfoFetchService.isNearDuplicate(
                        ExternalInfoFetchService.normalizeTitle("水泥价格连续三周上涨 华东地区涨幅居前"),
                        seen),
                "同事件不同措辞（换尾句）必须判重（2-gram Jaccard ≥ 0.62）");
        assertFalse(ExternalInfoFetchService.isNearDuplicate(
                        ExternalInfoFetchService.normalizeTitle("螺纹钢期货小幅回落"),
                        seen),
                "不同品种不得误判为重复");
    }

    // ==================== 多源配额公平（轮询交错） ====================

    @Test
    void interleaveSharesQuotaAcrossFeeds() {
        List<List<Map<String, String>>> feeds = List.of(
                List.of(row("新华网1"), row("新华网2"), row("新华网3")),
                List.of(row("中新网1")),
                List.of(row("36氪1"), row("36氪2")));

        List<Map<String, String>> merged = ExternalInfoFetchService.interleave(feeds, 6);

        assertEquals(6, merged.size());
        // 第一轮必须每个源各取一条，保证小源不被大源挤掉
        assertEquals("新华网1", merged.get(0).get("title"));
        assertEquals("中新网1", merged.get(1).get("title"));
        assertEquals("36氪1", merged.get(2).get("title"));
        assertEquals("新华网2", merged.get(3).get("title"));
        assertEquals("36氪2", merged.get(4).get("title"));
        assertEquals("新华网3", merged.get(5).get("title"));
    }

    @Test
    void interleaveCapsPerFeedAndHandlesEmptyFeeds() {
        List<Map<String, String>> huge = new ArrayList<>();
        for (int i = 0; i < 100; i++) huge.add(row("大源" + i));
        List<List<Map<String, String>>> feeds = new ArrayList<>();
        feeds.add(huge);
        feeds.add(null);
        feeds.add(List.of());
        feeds.add(List.of(row("小源1")));

        List<Map<String, String>> merged = ExternalInfoFetchService.interleave(feeds, 200);

        long bigCount = merged.stream().filter(m -> m.get("title").startsWith("大源")).count();
        assertEquals(ExternalInfoFetchService.MAX_PER_FEED, bigCount,
                "单源必须被 MAX_PER_FEED 截断，避免一个源吃满整轮");
        assertTrue(merged.stream().anyMatch(m -> "小源1".equals(m.get("title"))));
        assertTrue(ExternalInfoFetchService.interleave(null, 10).isEmpty());
        assertTrue(ExternalInfoFetchService.interleave(List.of(), 10).isEmpty());
    }

    private static Map<String, String> row(String title) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("title", title);
        return m;
    }
}

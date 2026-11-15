package com.lanlink.shopping.module.aikb;

import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import com.lanlink.shopping.module.pricemonitor.service.NewsSourceCatalog;
import com.lanlink.shopping.module.pricemonitor.service.ProductCatalogTerms;
import com.lanlink.shopping.module.pricemonitor.service.ProductFactorProfile;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部资讯自动抓取（RSS → t_market_news → 向量库 news 源刷新）。
 *
 * <p>链路：AI_EXTERNAL_FEEDS 配置的 RSS（默认含 IT之家 / 36氪 / 虎嗅 / WSJ Markets，
 * 以及新华网、中国新闻网、人民网、BBC Business 等权威机构公开源；可用环境变量覆盖）
 * → JDK HttpClient 抓取（UA=浏览器、connect 5s/request 20s、响应上限 2MB）
 * → JDK DocumentBuilder 安全解析（禁 DTD/外部实体/XInclude/实体展开）→ HTML 清洗 + 关键词归类
 * → 标题精确查重 + 归一化近重复去重
 * → 对照在售商品的标题、品牌、规格、分类和行业：不影响商城商品的资讯不上架
 * → 本地 qwen2.5-7B 降噪：娱乐软文或与在售商品无关则输出 DROP
 * → 通过后写入 t_market_news（上架到每日资讯；url 保留真实原文链接）
 * → 有新条目时调用 {@link KnowledgeIngestService#ingestNews()} 刷新向量库 news 源。
 *
 * <p>摘要与降噪：单轮最多 6 条交给本地 qwen2.5-7B（{@link EmbeddingService#chatText}）。
 * 模型判定为噪音时不入库；否则用 80-120 字摘要覆盖过短正文。失败/超时保留关键词过滤结果，
 * 绝不阻断抓取主链路。
 *
 * <p>调度：每 2 小时自动抓取（启动后 3 分钟首跑）；管理端可 POST /admin/kb/fetch-external 手动触发。
 * 整轮用 AtomicBoolean 互斥，统计字段供 /admin/kb/stats 展示。
 */
@Service
public class ExternalInfoFetchService {

    private static final Logger log = LoggerFactory.getLogger(ExternalInfoFetchService.class);

    /**
     * 内置默认 RSS 源（Lead 已从后端容器实测可访问；可用 AI_EXTERNAL_FEEDS 覆盖，逗号分隔）。
     */
    public static final String DEFAULT_FEEDS =
            // 权威机构公开 RSS（2026-11 实测可解析，容器内可访问）。
            // 抓取失败只跳过该源，不影响其它源；顺序即优先级。
            // 新华网财经：通讯社口径，政策/宏观/大宗价格
            "http://www.news.cn/fortune/news_fortune.xml,"
                    // 中新网财经：产业与区域经济
                    + "https://www.chinanews.com.cn/rss/finance.xml,"
                    // 中新网 IT：工业电子/元器件上游（经业务相关性过滤后仅保留工业侧）
                    + "https://www.chinanews.com.cn/rss/it.xml,"
                    // 36氪 / 虎嗅：产业与供应链深度（过滤后保留工业侧）
                    + "https://www.36kr.com/feed,"
                    + "https://rss.huxiu.com/,"
                    // IT之家：制造业/供应链快讯（过滤后保留工业侧）
                    + "https://www.ithome.com/rss/,"
                    // WSJ Markets：国际大宗与汇率（国际源，仅保留业务相关条目）
                    + "https://feeds.a.dj.com/rss/RSSMarketsMain.xml";

    /** 单次响应体上限 2MB（超过直接跳过，避免恶意/异常大文件拖垮内存） */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** 单个 feed 单轮最多入库条数 */
    static final int MAX_PER_FEED = 15;
    /** 管理端返回的 items 明细上限 */
    static final int MAX_ITEMS_KEPT = 10;
    /** 摘要短于该长度视为"过短"，可交给本地文本模型润色/补全 */
    static final int LLM_SUMMARY_MIN_CHARS = 40;
    /** 单轮最多交给本地模型降噪/补摘要的条数 */
    static final int LLM_SUMMARY_MAX_PER_RUN = 6;

    private static final int MAX_TITLE = 200;
    private static final int MAX_SUMMARY = 1000;
    private static final int MAX_SOURCE = 64;
    private static final int MAX_TAGS = 200;
    private static final String DEFAULT_SOURCE = "外部资讯";

    /** 展示时区：面向中文市场资讯，统一按东八区落库/展示（与 spring.jackson.time-zone=GMT+8 对齐） */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OUT_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter STAT_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 浏览器 UA：部分 RSS 站点对默认 Java UA 直接 403 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 LANLinkShopping/1.0";

    private static final Pattern XML_ENCODING = Pattern.compile(
            "encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']");
    private static final Pattern HTML_ENTITY = Pattern.compile(
            "&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});");
    private static final Map<String, String> NAMED_ENTITIES = buildEntities();

    /**
     * 文章 URL 里的日期（新华社 {@code /2022-12/14/c_xxx.htm}、部分站点 {@code /2026/11/12/}、
     * {@code /20261112/}）。用于交叉校验 RSS 的 pubDate。
     */
    private static final Pattern URL_DATE = Pattern.compile(
            "/(20\\d{2})[-/]?(\\d{2})[-/]?(\\d{2})?/");

    /** 过宽词不拿来判断「是否影响商城商品」，否则几乎所有商业稿都会被留下。 */
    static final java.util.Set<String> CATALOG_STOP = java.util.Set.of(
            "价格", "行情", "市场", "行业", "商品", "采购", "供应", "供给", "需求", "政策", "订单", "公司", "中国", "电子");
    /** 在售商品词表缓存，避免每条资讯都扫一遍商品表。 */
    private static final long CATALOG_TTL_MS = 10 * 60_000L;

    private final MarketNewsService newsService;
    private final KnowledgeIngestService ingestService;
    private final EmbeddingService embeddingService;
    /** 总开关（AI_EXTERNAL_ENABLED，默认开启；关闭后调度与手动触发都不抓取） */
    private final boolean enabled;
    private final List<String> feedUrls;
    /** 单轮最多入库条数（AI_EXTERNAL_LIMIT，默认 30） */
    private final int maxPerRun;
    /** 本地 7B 摘要开关（AI_EXTERNAL_LLM_SUMMARY，默认开启） */
    private final boolean llmSummaryEnabled;
    /** 业务相关性过滤开关（AI_EXTERNAL_RELEVANCE_FILTER，默认开启；关闭后所有条目都尝试入库） */
    private final boolean relevanceFilter;
    /**
     * 商品闸门是否启用。
     *
     * <p>只有 Spring 装配（有商品库依赖）时才启用；纯单测构造器不接商品表，
     * 无法判断相关性，跳过闸门而不是把所有条目判死。
     */
    private final boolean productGateEnabled;
    /** 时效窗（天）：只入库最近 N 天的资讯，0 表示不限制（AI_EXTERNAL_MAX_AGE_DAYS，默认 7） */
    private final int maxAgeDays;
    private final ProductMapper productMapper;
    private final IndustryMapper industryMapper;
    private final CategoryMapper categoryMapper;
    /**
     * 在售商品词表（可选注入）。有它时以它为准，保证写入侧与首页读取侧口径一致；
     * 单测/无 Spring 场景下为 null，回退到本类内置的 {@link #buildCatalog()}。
     */
    private ProductCatalogTerms catalogTermsService;
    private final HttpClient http;
    /** 整轮抓取互斥（调度线程 + 管理端手动触发并发防护） */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 并行抓取线程池：多个 RSS 源并发拉取，整轮耗时 ≈ 最慢源而非各源之和 */
    private final java.util.concurrent.ExecutorService feedPool =
            java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
                Thread t = new Thread(r, "external-feed-fetch");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    void shutdownFeedPool() {
        feedPool.shutdownNow();
    }

    /** 可选依赖：Spring 容器有该 Bean 时注入；纯单测环境不注入，走内置词表构建。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalogTermsService(ProductCatalogTerms catalogTermsService) {
        this.catalogTermsService = catalogTermsService;
    }

    // ==================== 最近一轮运行统计（/admin/kb/stats、/admin/kb/fetch-external） ====================
    private volatile LocalDateTime lastRunAt;
    private volatile int lastFetched;
    private volatile int lastInserted;
    private volatile int lastSkipped;
    private volatile int lastFiltered;
    private volatile int lastIngested;
    private volatile int feedCount;
    private volatile String lastError;
    private volatile long lastElapsedMs;
    private volatile List<Map<String, Object>> lastItems = List.of();

    private volatile List<String> catalogTerms = List.of();
    private volatile long catalogLoadedAt;

    @Autowired
    public ExternalInfoFetchService(MarketNewsService newsService,
                                    KnowledgeIngestService ingestService,
                                    EmbeddingService embeddingService,
                                    ProductMapper productMapper,
                                    IndustryMapper industryMapper,
                                    CategoryMapper categoryMapper,
                                    @Value("${AI_EXTERNAL_ENABLED:true}") boolean enabled,
                                    @Value("${AI_EXTERNAL_FEEDS:" + DEFAULT_FEEDS + "}") String feedConfig,
                                    @Value("${AI_EXTERNAL_LIMIT:30}") int limit,
                                    @Value("${AI_EXTERNAL_LLM_SUMMARY:true}") boolean llmSummaryEnabled,
                                    @Value("${AI_EXTERNAL_RELEVANCE_FILTER:true}") boolean relevanceFilter,
                                    @Value("${AI_EXTERNAL_MAX_AGE_DAYS:7}") int maxAgeDays) {
        this(newsService, ingestService, embeddingService, productMapper, industryMapper, categoryMapper,
                enabled, feedConfig, limit, llmSummaryEnabled, relevanceFilter, maxAgeDays, true);
    }

    /** 单测入口：不接商品库时词表为空，不额外拦截（既有用例保持可跑）。 */
    public ExternalInfoFetchService(MarketNewsService newsService,
                                    KnowledgeIngestService ingestService,
                                    EmbeddingService embeddingService,
                                    boolean enabled,
                                    String feedConfig,
                                    int limit,
                                    boolean llmSummaryEnabled,
                                    boolean relevanceFilter) {
        this(newsService, ingestService, embeddingService, null, null, null,
                // maxAgeDays=0：单测只验证解析/去重/入库分支，不引入随日历漂移的时效窗；
                // 时效窗本身由 isFresh 的独立用例覆盖。
                enabled, feedConfig, limit, llmSummaryEnabled, relevanceFilter, 0, false);
    }

    private ExternalInfoFetchService(MarketNewsService newsService,
                                     KnowledgeIngestService ingestService,
                                     EmbeddingService embeddingService,
                                     ProductMapper productMapper,
                                     IndustryMapper industryMapper,
                                     CategoryMapper categoryMapper,
                                     boolean enabled,
                                     String feedConfig,
                                     int limit,
                                     boolean llmSummaryEnabled,
                                     boolean relevanceFilter,
                                     int maxAgeDays,
                                     boolean springManaged) {
        this.newsService = newsService;
        this.ingestService = ingestService;
        this.embeddingService = embeddingService;
        this.productMapper = productMapper;
        this.industryMapper = industryMapper;
        this.categoryMapper = categoryMapper;
        this.enabled = enabled;
        this.feedUrls = parseFeedConfig(feedConfig);
        this.maxPerRun = limit > 0 ? limit : 30;
        this.llmSummaryEnabled = llmSummaryEnabled;
        this.relevanceFilter = relevanceFilter;
        this.productGateEnabled = springManaged;
        this.maxAgeDays = maxAgeDays;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("[external-fetch] configured {} RSS feeds (enabled={}, maxPerRun={}, llmSummary={}, relevanceFilter={}, catalog={})",
                feedUrls.size(), enabled, maxPerRun, llmSummaryEnabled, relevanceFilter, springManaged && productMapper != null);
    }

    // ==================== 调度 / 手动触发 ====================

    /** 每 2 小时自动抓取（启动后 3 分钟首跑）；开关关闭时跳过 */
    /**
     * 资讯抓取周期：默认 15 分钟（AI_EXTERNAL_POLL_MS 可调）。
     *
     * <p>历史实现为 2 小时，首页「每日资讯」最长滞后 2 小时，不满足"实时"。
     * 15 分钟与前端 120s 刷新配合，可保证首页内容始终是近时段资讯。
     * 多实例部署需配合分布式锁，否则会重复抓取（见 scheduledFetch 的 running 标志）。
     */
    @Scheduled(fixedDelayString = "${AI_EXTERNAL_POLL_MS:900000}", initialDelay = 180_000L)
    public void scheduledFetch() {
        if (!enabled) {
            log.debug("[external-fetch] scheduled run skipped: AI_EXTERNAL_ENABLED=false");
            return;
        }
        try {
            Map<String, Object> stats = fetchNow(maxPerRun);
            log.info("[external-fetch] scheduled run done: fetched={} inserted={} skipped={} filtered={} ingested={} "
                            + "elapsed={}ms error={}", stats.get("fetched"), stats.get("inserted"),
                    stats.get("skipped"), stats.get("filtered"), stats.get("ingested"),
                    stats.get("elapsedMs"), stats.get("error"));
        } catch (Exception e) {
            log.warn("[external-fetch] scheduled run failed: {}", e.getMessage());
        }
    }

    /**
     * 立即抓取一轮（管理端手动触发）。
     *
     * @param limit 本轮最多入库条数（≤0 用 AI_EXTERNAL_LIMIT；超过配置上限时按配置裁剪）
     * @return 本轮统计：lastRunAt/fetched/inserted/skipped/ingested/feeds/feedUrls/elapsedMs/error/items
     */
    public Map<String, Object> fetchNow(int limit) {
        if (!enabled) {
            return statsWithNotice("外部资讯自动抓取已关闭（AI_EXTERNAL_ENABLED=false）");
        }
        if (!running.compareAndSet(false, true)) {
            return statsWithNotice("已有外部资讯抓取任务正在进行，请稍后重试");
        }
        long start = System.currentTimeMillis();
        int fetched = 0;
        int inserted = 0;
        int skipped = 0;
        int filtered = 0;
        int ingested = 0;
        int feedsAttempted = 0;
        String error = null;
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            int effectiveMax = resolveMax(limit);
            // 并行拉取所有 RSS（各源互不依赖），再按源顺序去重入库，整轮耗时≈最慢源
            List<CompletableFuture<List<Map<String, String>>>> pending = new ArrayList<>(feedUrls.size());
            for (String feedUrl : feedUrls) {
                pending.add(CompletableFuture.supplyAsync(() -> fetchFeed(feedUrl), feedPool));
            }
            feedsAttempted = pending.size();
            // LLM 降噪预算按【整轮】共享（历史实现每个 feed 各 6 次，
            // 7 个源最多 42 次；且失败也计数，导致后面的条目完全跳过降噪）。
            java.util.concurrent.atomic.AtomicInteger denoiseBudget =
                    new java.util.concurrent.atomic.AtomicInteger(LLM_SUMMARY_MAX_PER_RUN);
            List<List<Map<String, String>>> parsedByFeed = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                List<Map<String, String>> parsed;
                try {
                    parsed = pending.get(i).get(45, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("[external-fetch] feed {} failed: {}", feedUrls.get(i), e.getMessage());
                    if (error == null) error = "feed 抓取失败: " + e.getMessage();
                    parsed = List.of();
                }
                fetched += parsed.size();
                parsedByFeed.add(parsed);
            }
            // 配额公平：按源轮询交错，避免前两个源（新华网 100+ 条）吃满整轮配额，
            // 导致后面的行业/国际源一条都进不来（历史实现是「按源顺序先到先得」）。
            List<Map<String, String>> interleaved = interleave(parsedByFeed, effectiveMax * 4);
            if (!interleaved.isEmpty()) {
                Map<String, Object> stored = storeParsedItems(interleaved, effectiveMax, items, denoiseBudget);
                inserted += (Integer) stored.get("inserted");
                skipped += (Integer) stored.get("skipped");
                filtered += (Integer) stored.get("filtered");
            }
            if (inserted > 0) {
                try {
                    ingested = ingestService.ingestNews();
                } catch (Exception e) {
                    log.warn("[external-fetch] vector news refresh failed: {}", e.getMessage());
                    if (error == null) error = "向量库刷新失败: " + e.getMessage();
                }
            }
        } catch (Exception e) {
            log.warn("[external-fetch] run failed: {}", e.getMessage());
            error = e.getMessage();
        } finally {
            running.set(false);
            lastRunAt = LocalDateTime.now();
            lastFetched = fetched;
            lastInserted = inserted;
            lastSkipped = skipped;
            lastFiltered = filtered;
            lastIngested = ingested;
            feedCount = feedsAttempted;
            lastError = error;
            lastElapsedMs = System.currentTimeMillis() - start;
            lastItems = items.size() > MAX_ITEMS_KEPT
                    ? List.copyOf(items.subList(0, MAX_ITEMS_KEPT)) : List.copyOf(items);
        }
        Map<String, Object> result = stats();
        log.info("[external-fetch] run done: feeds={} fetched={} inserted={} skipped={} filtered={} ingested={} elapsed={}ms",
                feedsAttempted, fetched, inserted, skipped, filtered, ingested, lastElapsedMs);
        if (filtered > 0) {
            log.info("[external-fetch] relevance filter dropped {} non-business items this run", filtered);
        }
        return result;
    }

    // ==================== 抓取 → 解析 → 去重入库 ====================

    /**
     * 多源轮询交错：第 1 轮取每个源的第 1 条，第 2 轮取每个源的第 2 条……
     *
     * <p>这样在「单轮入库上限 30 条」的约束下，各源按相同权重分享配额，
     * 而不是让排在前面的源（新华网单次可返回 100+ 条）把配额吃光。
     * 单源最多取 {@link #MAX_PER_FEED} 条，避免某源条目过多挤占其它源。
     *
     * @param perFeed 各源已解析条目（顺序与 feedUrls 一致）
     * @param cap     合并后的总条数上限（防止内存放大）
     */
    static List<Map<String, String>> interleave(List<List<Map<String, String>>> perFeed, int cap) {
        List<Map<String, String>> out = new ArrayList<>();
        if (perFeed == null || perFeed.isEmpty()) return out;
        int maxPerFeed = Math.min(MAX_PER_FEED, Math.max(1, cap));
        int longest = 0;
        for (List<Map<String, String>> rows : perFeed) {
            if (rows != null) longest = Math.max(longest, Math.min(rows.size(), maxPerFeed));
        }
        for (int round = 0; round < longest && out.size() < cap; round++) {
            for (List<Map<String, String>> rows : perFeed) {
                if (rows == null || round >= rows.size() || round >= maxPerFeed) continue;
                out.add(rows.get(round));
                if (out.size() >= cap) break;
            }
        }
        return out;
    }

    /** 抓取单个 RSS 并解析为统一字段（网络异常/非 200/超大响应返回空列表，不抛出） */
    List<Map<String, String>> fetchFeed(String feedUrl) {
        URI uri = httpUri(feedUrl);
        if (uri == null) {
            log.warn("[external-fetch] invalid feed url: {}", feedUrl);
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, "
                            + "text/xml;q=0.9, */*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    log.warn("[external-fetch] {} http {}", feedUrl, response.statusCode());
                    return List.of();
                }
                byte[] bytes = readLimited(in, MAX_RESPONSE_BYTES);
                if (bytes == null) {
                    log.warn("[external-fetch] {} response exceeds {} bytes, skipped", feedUrl, MAX_RESPONSE_BYTES);
                    return List.of();
                }
                return parseRss(decodeResponse(bytes), feedUrl);
            }
        } catch (Exception e) {
            log.warn("[external-fetch] fetch {} failed: {}", feedUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * 把解析后的条目做业务相关性过滤 + 去重后入库（package-private：单测直接注入解析结果，不依赖网络）。
     *
     * @return {inserted, skipped, filtered, llmSummaries}；skipped 含被相关性过滤的条目，filtered 为其子集
     */
    Map<String, Object> storeParsedItems(List<Map<String, String>> parsed, int max) {
        return storeParsedItems(parsed, max, new ArrayList<>(),
                new java.util.concurrent.atomic.AtomicInteger(LLM_SUMMARY_MAX_PER_RUN));
    }

    Map<String, Object> storeParsedItems(List<Map<String, String>> parsed, int max,
                                         List<Map<String, Object>> itemViews,
                                         java.util.concurrent.atomic.AtomicInteger denoiseBudget) {
        int inserted = 0;
        int skipped = 0;
        int filtered = 0;
        int llmSummaries = 0;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        if (newsService != null) {
            List<String> recent = newsService.recentTitles(200);
            if (recent != null) {
                for (String existing : recent) {
                    String norm = normalizeTitle(existing);
                    if (!norm.isEmpty()) seen.add(norm);
                }
            }
        }
        if (parsed != null) {
            for (Map<String, String> row : parsed) {
                if (inserted >= max) break;
                MarketNews news = toNews(row);
                if (news == null) {
                    skipped++;
                    continue;
                }
                // 时效窗：旧闻不上架（首页「每日资讯」必须实时）
                if (!isFresh(news, maxAgeDays)) {
                    filtered++;
                    skipped++;
                    log.debug("[external-fetch] stale filtered ({}): {}", news.getPublishedAt(), news.getTitle());
                    continue;
                }
                try {
                    // 业务相关性过滤（AI_EXTERNAL_RELEVANCE_FILTER）：娱乐/消费电子/会员促销等非业务资讯不入库，
                    // 避免污染每日资讯与 RAG 向量库上下文；有 BUSINESS 信号时保留（避免误杀工业/产业新闻）。
                    if (relevanceFilter && !isBusinessRelevant(news.getTitle(), news.getSummary())) {
                        filtered++;
                        skipped++;
                        String reason = hasBlockSignal(news.getTitle() + " " + news.getSummary()) ? "block" : "no-business";
                        log.debug("[external-fetch] relevance filtered ({}) {}", reason, news.getTitle());
                        continue;
                    }
                    // 必须能影响到当前在售商品（标题/品牌/规格/分类/行业）。词表为空时不拦截。
                    List<String> catalog = currentCatalog();
                    if (!productGateEnabled) {
                        // 单测/无商品库场景：无法判断相关性，跳过商品闸门
                    } else if (catalog.isEmpty()) {
                        // 商品词表不可用：不能静默放行（会让消费类资讯上架），
                        // 也不能静默丢弃（全新库还没商品时会一条都进不来）。
                        // 折中：只保留强业务信号条目，并打告警等人工核对。
                        if (!hasBusinessSignal(news.getTitle() + " " + news.getSummary())) {
                            filtered++;
                            skipped++;
                            continue;
                        }
                        log.warn("[external-fetch] catalog empty, keeping business-signal item only: {}",
                                news.getTitle());
                    } else if (!affectsCatalog(news.getTitle(), news.getSummary(), catalog)) {
                        filtered++;
                        skipped++;
                        log.debug("[external-fetch] catalog filtered {}", news.getTitle());
                        continue;
                    }
                    if (newsService.existsByTitle(news.getTitle())) {
                        skipped++;
                        continue;
                    }
                    String norm = normalizeTitle(news.getTitle());
                    if (norm.isEmpty() || isNearDuplicate(norm, seen)) {
                        skipped++;
                        continue;
                    }
                    seen.add(norm);
                    // 本地模型降噪（GPU/4070，经 ai-gateway）。
                    // 预算按【整轮】共享：只有真正调用成功才扣减，避免失败吃掉配额。
                    // 预算耗尽时标记 denoise=skipped（仍上架，但可审计），不再静默跳过。
                    if (llmSummaryEnabled && denoiseBudget.get() > 0) {
                        String enhanced = generateLlmSummary(news.getTitle(), news.getSummary());
                        if (enhanced != null) {
                            denoiseBudget.decrementAndGet();
                            llmSummaries++;
                        }
                        if (isModelDrop(enhanced)) {
                            filtered++;
                            skipped++;
                            log.debug("[external-fetch] llm denoise dropped {}", news.getTitle());
                            continue;
                        }
                        if (enhanced != null && needsLlmSummary(news.getSummary())) {
                            news.setSummary(enhanced);
                        } else if (enhanced != null && enhanced.length() >= LLM_SUMMARY_MIN_CHARS
                                && enhanced.length() <= MAX_SUMMARY) {
                            news.setSummary(enhanced);
                        }
                    } else if (llmSummaryEnabled) {
                        log.debug("[external-fetch] denoise budget exhausted, pass through: {}", news.getTitle());
                    }
                    // 上架：写入每日资讯。唯一键冲突（并发/多实例）计入跳过。
                    if (newsService.saveExternal(news)) {
                        inserted++;
                        if (itemViews != null && itemViews.size() < MAX_ITEMS_KEPT) {
                            itemViews.add(toView(news));
                        }
                    } else {
                        // 唯一键冲突（并发/多实例）：静默计入跳过
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("[external-fetch] store item failed ({}): {}", news.getTitle(), e.getMessage());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("filtered", filtered);
        result.put("llmSummaries", llmSummaries);
        return result;
    }

    /** 解析结果 → MarketNews 实体（字段裁剪/URL 校验/时间兜底） */
    static MarketNews toNews(Map<String, String> row) {
        if (row == null) return null;
        String title = clip(trimToEmpty(row.get("title")), MAX_TITLE);
        if (title.isBlank()) return null;
        MarketNews news = new MarketNews();
        news.setTitle(title);
        news.setSummary(clip(trimToEmpty(row.get("summary")), MAX_SUMMARY));
        String category = trimToEmpty(row.get("category"));
        news.setCategory(category.isBlank() ? "market" : category);
        String source = clip(trimToEmpty(row.get("source")), MAX_SOURCE);
        news.setSource(source.isBlank() ? DEFAULT_SOURCE : source);
        news.setUrl(NewsSourceCatalog.normalizeHttpUrl(row.get("url")));
        news.setTags(clip(trimToEmpty(row.get("tags")), MAX_TAGS));
        news.setImportance(parseInt(row.get("importance"), 1));
        LocalDateTime publishedAt = null;
        String published = trimToEmpty(row.get("publishedAt"));
        if (!published.isBlank()) {
            try {
                publishedAt = LocalDateTime.parse(published, OUT_DATETIME);
            } catch (Exception ignored) {
                publishedAt = parsePublishedAt(published);
            }
        }
        // URL 日期交叉校验：新华社等源的历史频道会把 2022 年的稿件放在当前 RSS 里，
        // 且 pubDate 缺失/错误。此时若 URL 内嵌日期明显早于 pubDate，以 URL 日期为准，
        // 否则旧稿会被当成今日资讯排到首页最前（实测混入 2022-12 的油价快讯）。
        LocalDateTime urlDate = dateFromUrl(news.getUrl());
        if (urlDate != null && (publishedAt == null || urlDate.isBefore(publishedAt.minusDays(1)))) {
            publishedAt = urlDate;
        }
        // 解析不到发布时间时不再用 now() 兜底：否则 2023 年的旧稿会被当成"刚刚发布"
        // 排到首页最前。published_at 是 NOT NULL，因此由调用方（toNews 返回 null）
        // 直接丢弃该条，而不是塞一个假时间。
        if (publishedAt == null) return null;
        news.setPublishedAt(publishedAt);
        news.setCreatedAt(LocalDateTime.now());
        return news;
    }

    /**
     * 从文章 URL 提取发布日期（仅年月也可用，日缺省为 1 号）。
     *
     * @return 解析失败或不是合理日期时返回 null
     */
    static LocalDateTime dateFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher m = URL_DATE.matcher(url);
        if (!m.find()) return null;
        try {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) return null;
            LocalDateTime candidate = LocalDateTime.of(year, month, day, 0, 0);
            // 未来日期（时区/误写）不采信，避免把新稿误判成旧稿
            return candidate.isAfter(LocalDateTime.now().plusDays(1)) ? null : candidate;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 时效窗判定：只接受最近 N 天内发布的资讯。
     *
     * <p>历史缺陷：RSS 里 2023 年的旧闻与当天新闻混在一起入库，首页「每日资讯」
     * 会出现多年前的行情分析，实时性名不副实。
     *
     * <p>边界：解析不到发布时间的条目视为「当前」，避免误杀（部分源不提供 pubDate）。
     */
    static boolean isFresh(MarketNews news, int maxAgeDays) {
        if (news == null || news.getPublishedAt() == null) return true;
        if (maxAgeDays <= 0) return true;
        return news.getPublishedAt().isAfter(LocalDateTime.now().minusDays(maxAgeDays));
    }

    private static Map<String, Object> toView(MarketNews news) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", news.getTitle());
        item.put("source", news.getSource());
        item.put("category", news.getCategory());
        item.put("url", news.getUrl());
        return item;
    }

    private static boolean needsLlmSummary(String summary) {
        return summary == null || stripHtml(summary).length() < LLM_SUMMARY_MIN_CHARS;
    }

    /** 本地 7B 降噪并生成摘要。返回 DROP 表示不上架；null 表示模型失败，调用方保留原摘要并继续上架。 */
    String generateLlmSummary(String title, String summary) {
        if (embeddingService == null) return null;
        try {
            String system = "你是中文市场资讯编辑，服务一家 B2B 商城。"
                    + "若内容是娱乐、数码评测、广告软文、重复通稿，或影响不到商城在售商品"
                    + "（建材、水泥、钢材、纺织、化工、电子元器件、机械、能源、包装、新能源及其原料与价格），只输出 DROP。"
                    + "否则用 80-120 个中文字符写降噪后的摘要，点明它会影响哪类商品。"
                    + "只输出 DROP 或摘要正文，不要标题、引号、解释或 Markdown。";
            String user = "标题：" + clip(trimToEmpty(title), 120) + "\n正文：" + clip(trimToEmpty(summary), 800);
            String generated = CompletableFuture
                    .supplyAsync(() -> embeddingService.chatText(system, user))
                    .get(20, TimeUnit.SECONDS);
            if (generated == null || generated.isBlank()) return null;
            String cleaned = generated.replaceAll("\\s+", " ").trim();
            if (isModelDrop(cleaned)) return "DROP";
            if (isModelNoise(cleaned)) {
                log.debug("[external-fetch] llm returned non-editorial text, keep original: {}", cleaned);
                return null;
            }
            if (cleaned.length() < 20) return null;
            return clip(cleaned, MAX_SUMMARY);
        } catch (Exception e) {
            log.debug("[external-fetch] llm denoise failed, publish with original summary: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 模型输出是否为「非资讯编辑内容」：客服话术、拒答、知识库兜底、商品卡片等。
     *
     * <p>实测污染：模型偶发把客服回答（"该问题超出知识库范围…商品名称：硅酸盐水泥 P.O42.5…"）
     * 当成摘要写回，导致白银行情稿的摘要里出现「硅酸盐水泥」，进而被商品相关性判定
     * 误判成水泥商品的强相关证据。
     */
    static boolean isModelNoise(String text) {
        if (text == null || text.isBlank()) return true;
        String t = text.toLowerCase(Locale.ROOT);
        for (String signal : MODEL_NOISE_SIGNALS) {
            if (t.contains(signal)) return true;
        }
        // 摘要不应出现「商品名称/品牌/规格/单价/库存」这类结构化商品卡片字段
        return t.contains("商品名称：") && t.contains("单价");
    }

    private static final List<String> MODEL_NOISE_SIGNALS = List.of(
            "超出知识库范围", "转人工客服", "人工客服", "无法回答", "我无法", "作为ai",
            "知识库中未", "未找到相关信息", "抱歉，", "抱歉,", "请提供更多", "以下是我的回答",
            "查询的商品信息", "商品名称：", "来源：《");

    /** 词表为空视为「还没读到商品」，不拦截；否则标题或摘要必须命中在售商品/行业词。 */
    static boolean affectsCatalog(String title, String summary, List<String> terms) {
        // 空词表 = 无法判断相关性（商品表为空/查询异常/首次加载）：
        // 历史实现 return true 会让商品闸门整体失效，消费类资讯直接上架。
        // 改为返回 false，由调用方结合业务词兜底并记录告警，避免静默失效。
        if (terms == null || terms.isEmpty()) return false;
        String text = cleanText((title == null ? "" : title) + " " + (summary == null ? "" : summary))
                .toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return false;
        // 单字品类词（钢/铜/铝/锂/锡/镍/锌/煤/油/硅/铁/纸）信息量足够，允许命中
        java.util.Set<String> singleChar = java.util.Set.of(
                "钢", "铜", "铝", "锂", "锡", "镍", "锌", "煤", "油", "硅", "铁", "纸");
        for (String term : terms) {
            if (term == null) continue;
            String needle = term.trim().toLowerCase(Locale.ROOT);
            if (CATALOG_STOP.contains(needle)) continue;
            if (needle.length() < 2 && !singleChar.contains(needle)) continue;
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private List<String> currentCatalog() {
        if (catalogTermsService != null) {
            return catalogTermsService.terms();
        }
        if (productMapper == null) return List.of();
        long now = System.currentTimeMillis();
        List<String> cached = catalogTerms;
        if (cached != null && !cached.isEmpty() && now - catalogLoadedAt < CATALOG_TTL_MS) return cached;
        try {
            List<String> built = buildCatalog();
            catalogTerms = built;
            catalogLoadedAt = now;
            return built;
        } catch (Exception e) {
            log.warn("[external-fetch] catalog load failed, skip product gate this run: {}", e.getMessage());
            return List.of();
        }
    }

    /** 在售且审核通过的商品：标题、品牌、规格、分类、行业，收成匹配词。 */
    private List<String> buildCatalog() {
        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getReviewStatus, 1)
                .select(Product::getTitle, Product::getBrand, Product::getSpec, Product::getIndId, Product::getCatId)
                .last("LIMIT 500"));
        if (products == null || products.isEmpty()) return List.of();
        Map<Long, String> industries = new LinkedHashMap<>();
        if (industryMapper != null) {
            for (Industry industry : industryMapper.selectList(null)) {
                if (industry != null && industry.getIndId() != null && industry.getName() != null) {
                    industries.put(industry.getIndId(), industry.getName());
                }
            }
        }
        Map<Long, String> categories = new LinkedHashMap<>();
        if (categoryMapper != null) {
            for (Category category : categoryMapper.selectList(null)) {
                if (category != null && category.getCatId() != null && category.getName() != null) {
                    categories.put(category.getCatId(), category.getName());
                }
            }
        }
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (Product product : products) {
            String industryName = product.getIndId() == null ? "" : industries.getOrDefault(product.getIndId(), "");
            String categoryName = product.getCatId() == null ? "" : categories.getOrDefault(product.getCatId(), "");
            ProductFactorProfile profile = ProductFactorProfile.of(product, industryName, categoryName);
            for (String word : profile.productKeywords()) addCatalogTerm(terms, word);
            for (String word : profile.industryKeywords()) addCatalogTerm(terms, word);
            if (terms.size() >= 400) break;
        }
        return List.copyOf(terms);
    }

    private static void addCatalogTerm(java.util.Set<String> terms, String word) {
        if (word == null) return;
        String term = word.trim();
        if (term.length() < 2 || CATALOG_STOP.contains(term)) return;
        terms.add(term);
    }
    static boolean isModelDrop(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String compact = raw.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        if (compact.equals("drop") || compact.startsWith("drop。") || compact.startsWith("drop.")) return true;
        return compact.contains("\"keep\":false");
    }

    /** 去标点空白后的标题，用于近重复判断。 */
    /** 标题尾部常见媒体署名/来源后缀（跨源转载时各站会各加一个）。 */
    private static final java.util.regex.Pattern MEDIA_SUFFIX = java.util.regex.Pattern.compile(
            "[-|｜—–]\\s*(36氪|虎嗅|IT之家|新华网|中国新闻网|中新网|人民网|央视|新浪|网易|腾讯|"
                    + "界面新闻|第一财经|财新|澎湃|观察者网|钛媒体|亿邦动力)\\s*$");
    /** 标题前缀标签，如「【图】」「【视频】」「【独家】」。 */
    private static final java.util.regex.Pattern TITLE_TAG_PREFIX = java.util.regex.Pattern.compile(
            "^[【\\[（(][^】\\]）)]{1,8}[】\\]）)]");

    /**
     * 标题归一化（去重键）。
     *
     * <p>相比历史实现（仅去标点+小写），这里额外处理：
     * <ul>
     *   <li>NFKC：全角转半角（Ｐ.Ｏ42.5 → P.O42.5、１２mm → 12mm）</li>
     *   <li>剥离「【图】」类前缀与「-36氪」类媒体后缀，让跨源转载归一</li>
     *   <li>去掉所有标点/空白/零宽字符</li>
     * </ul>
     * 繁简转换需引入第三方库，这里不做（避免为去重增加依赖），
     * 由近似重复（SimHash/Jaccard）兜住繁简差异。
     */
    static String normalizeTitle(String title) {
        if (title == null) return "";
        String text = cleanText(title);
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        text = TITLE_TAG_PREFIX.matcher(text).replaceFirst("");
        text = MEDIA_SUFFIX.matcher(text).replaceFirst("");
        text = text.toLowerCase(Locale.ROOT);
        return text.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s\\u200B-\\u200D\\uFEFF]+", "");
    }

    static boolean isNearDuplicate(String normalized, java.util.Set<String> seen) {
        if (normalized == null || normalized.isEmpty() || seen == null || seen.isEmpty()) return false;
        if (seen.contains(normalized)) return true;
        // 中文短标题（如「钢材价格上调」6 字）同样需要近似判重，阈值从 12 降到 8
        if (normalized.length() < 8) return false;
        java.util.Set<String> grams = bigrams(normalized);
        for (String existing : seen) {
            if (existing.length() < 8) continue;
            if (existing.contains(normalized) || normalized.contains(existing)) return true;
            // 同事件不同措辞（「水泥三周连涨」vs「华东水泥连续第三周上涨」）：
            // 字符 2-gram Jaccard ≥ 0.62 视为同一事件，避免跨源重复上架。
            if (jaccard(grams, bigrams(existing)) >= 0.62) return true;
        }
        return false;
    }

    /** 字符 2-gram 集合（中文无需分词即可捕捉短语级相似）。 */
    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null || s.length() < 2) return out;
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /** Jaccard 相似度：交集 / 并集。 */
    private static double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        java.util.Set<String> small = a.size() <= b.size() ? a : b;
        java.util.Set<String> large = small == a ? b : a;
        int inter = 0;
        for (String g : small) {
            if (large.contains(g)) inter++;
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** 权威机构源用固定来源名，方便每日资讯跳到对应官网。其余沿用 RSS 频道名。 */
    static String authoritativeSource(String feedUrl, String channelTitle) {
        String url = feedUrl == null ? "" : feedUrl.toLowerCase(Locale.ROOT);
        if (url.contains("news.cn") || url.contains("xinhuanet.com")) return "新华网";
        if (url.contains("chinanews.com")) return "中国新闻网";
        if (url.contains("people.com.cn")) return "人民网";
        if (url.contains("bbci.co.uk") || url.contains("bbc.co.uk") || url.contains("bbc.com")) return "BBC";
        if (url.contains("ithome.com")) return "IT之家";
        if (url.contains("36kr.com")) return "36氪";
        if (url.contains("huxiu.com")) return "虎嗅";
        if (url.contains("wsj.com") || url.contains("dj.com") || url.contains("dowjones")) return "华尔街日报";
        return channelTitle == null ? "" : channelTitle;
    }

    // ==================== 统计 ====================

    /** 最近一轮抓取统计（管理端 /admin/kb/stats 的 externalFetch 字段） */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastRunAt", lastRunAt == null ? null : lastRunAt.format(STAT_DATETIME));
        m.put("fetched", lastFetched);
        m.put("inserted", lastInserted);
        m.put("skipped", lastSkipped);
        m.put("filtered", lastFiltered);
        m.put("ingested", lastIngested);
        m.put("feeds", feedCount > 0 ? feedCount : feedUrls.size());
        m.put("feedUrls", feedUrls);
        m.put("elapsedMs", lastElapsedMs);
        m.put("error", lastError);
        m.put("items", lastItems);
        m.put("enabled", enabled);
        m.put("relevanceFilter", relevanceFilter);
        m.put("running", running.get());
        return m;
    }

    private Map<String, Object> statsWithNotice(String notice) {
        Map<String, Object> m = stats();
        m.put("notice", notice);
        return m;
    }

    /** 本轮有效入库上限：显式 limit（≤0 用配置值）与配置值取小，避免管理端放大抓取量 */
    private int resolveMax(int limit) {
        int requested = limit > 0 ? limit : maxPerRun;
        return Math.max(1, Math.min(requested, maxPerRun));
    }

    // ==================== 配置解析 ====================

    private static List<String> parseFeedConfig(String config) {
        List<String> out = new ArrayList<>();
        if (config != null) {
            for (String part : config.split("[,\\n]")) {
                String url = part.trim();
                if (url.isEmpty() || httpUri(url) == null) continue;
                if (!out.contains(url)) out.add(url);
            }
        }
        if (out.isEmpty()) {
            for (String part : DEFAULT_FEEDS.split(",")) {
                String url = part.trim();
                if (httpUri(url) != null) out.add(url);
            }
        }
        return List.copyOf(out);
    }

    // ==================== HTTP / 编码 ====================

    /** 只接受 http(s) 绝对地址（防 SSRF/本地文件等非预期协议） */
    static URI httpUri(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取响应体，超过上限返回 null（不把超大响应读进内存） */
    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 按 BOM / XML 声明 encoding 解码响应字节（国内部分 RSS 为 GBK） */
    static String decodeResponse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        int probeLength = Math.min(bytes.length, 200);
        String probe = new String(bytes, 0, probeLength, StandardCharsets.ISO_8859_1);
        Matcher matcher = XML_ENCODING.matcher(probe);
        if (matcher.find()) {
            try {
                return new String(bytes, Charset.forName(matcher.group(1)));
            } catch (Exception ignored) {
                // 未知编码：按 UTF-8 兜底
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ==================== RSS 安全解析（纯函数，可单测） ====================

    /**
     * 解析 RSS XML（兼容 {@code <item>} 与 CDATA）。
     *
     * <p>安全：禁用 DOCTYPE / 外部通用与参数实体 / XInclude / 实体展开，并对未转义 {@code &}
     * 或非法控制字符做一次宽容清洗重试（不影响 CDATA 中的 HTML 实体文本，后续会被清洗）。
     *
     * @return 统一字段列表：title/url/summary/source/publishedAt(ISO)/category/importance/tags
     */
    static List<Map<String, String>> parseRss(String xml, String feedUrl) {
        List<Map<String, String>> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) return out;
        try {
            Document doc = parseXml(xml);
            Element root = doc.getDocumentElement();
            if (root == null) return out;
            Element channel = "rss".equalsIgnoreCase(localName(root)) ? firstChild(root, "channel") : root;
            if (channel == null) channel = root;
            String sourceName = authoritativeSource(feedUrl,
                    clip(cleanText(childText(channel, "title")), MAX_SOURCE));
            if (sourceName.isBlank()) sourceName = domainOf(feedUrl);
            NodeList items = channel.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node node = items.item(i);
                if (!(node instanceof Element item)) continue;
                String title = clip(cleanText(childText(item, "title")), MAX_TITLE);
                if (title.isBlank()) continue;
                String url = normalizeUrl(childText(item, "link", "guid"));
                String summary = clip(cleanText(childText(item, "description", "encoded", "content", "summary")),
                        MAX_SUMMARY);
                String publishedRaw = childText(item, "pubDate", "date", "published", "updated");
                LocalDateTime publishedAt = parsePublishedAt(publishedRaw);

                Map<String, String> row = new LinkedHashMap<>();
                row.put("title", title);
                row.put("url", url == null ? "" : url);
                row.put("summary", summary);
                row.put("source", sourceName);
                row.put("publishedAt", publishedAt == null ? "" : publishedAt.format(OUT_DATETIME));
                row.put("category", classify(title, summary));
                row.put("importance", String.valueOf(importanceOf(title)));
                row.put("tags", tagsOf(title, summary));
                out.add(row);
            }
        } catch (Exception e) {
            log.warn("[external-fetch] RSS parse failed ({}): {}", feedUrl, e.getMessage());
        }
        return out;
    }

    private static Document parseXml(String xml) throws Exception {
        String content = xml;
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') content = content.substring(1);
        try {
            return newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (org.xml.sax.SAXException first) {
            String sanitized = sanitizeXml(content);
            if (sanitized.equals(content)) throw first;
            return newDocumentBuilder().parse(new InputSource(new StringReader(sanitized)));
        }
    }

    private static javax.xml.parsers.DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setFeature(factory, "http://apache.org/xml/features/xinclude", false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (Exception ignored) {
            // 部分实现不支持该属性，特性开关已足够
        }
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception ignored) {
            // 同上
        }
        // Xerces 默认 ErrorHandler 会把 fatal error 先打到 stderr（如 DOCTYPE 被禁、prolog 非法），
        // 显式接管：warning/error 静默（slf4j 已记录失败原因），fatalError 原样重抛保持失败语义
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder;
    }

    /** XML 安全特性逐项开启（不支持的特性跳过，不影响其他防护） */
    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // 该实现不支持此特性
        }
    }

    /** 宽容清洗：去掉非法控制字符、修复未转义的裸 &（CDATA 内同样处理，HTML 清洗时视觉一致） */
    static String sanitizeXml(String xml) {
        String cleaned = xml.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        return cleaned.replaceAll("&(?!(?:#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});)", "&amp;");
    }

    private static String localName(Node node) {
        String name = node.getNodeName();
        int index = name.indexOf(':');
        return index >= 0 ? name.substring(index + 1) : name;
    }

    private static Element firstChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && localName(node).equalsIgnoreCase(name)) {
                return (Element) node;
            }
        }
        return null;
    }

    /** 取直接子元素的文本（按 localName 匹配，兼容 dc:/content: 等命名空间前缀） */
    private static String childText(Element parent, String... names) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = localName(node);
            for (String candidate : names) {
                if (name.equalsIgnoreCase(candidate)) return node.getTextContent();
            }
        }
        return "";
    }

    // ==================== HTML 清洗 / 字段清洗（纯函数，可单测） ====================

    /** 去 HTML 标签/实体/脚本样式，压缩空白（36氪等源 description 为整篇 HTML 正文） */
    static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = html.replace("<![CDATA[", " ").replace("]]>", " ");
        text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        text = removeTags(text);
        text = decodeEntities(text);
        // 二次去标签：处理 &lt;p&gt; 这类"转义过的 HTML"解码后重新出现的标签
        text = removeTags(text);
        return text.replaceAll("[\\s\\u00a0\\u3000]+", " ").trim();
    }

    /** 块级标签替换为空格（避免段落粘连），行内标签直接移除（避免中文被空格打断） */
    private static String removeTags(String html) {
        String text = html.replaceAll("(?is)</?(?:p|div|br|li|ul|ol|h[1-6]|tr|td|th|table|section|article|blockquote|pre)[^>]*>", " ");
        return text.replaceAll("(?s)<[^>]*>", "");
    }

    /** 与 {@link #stripHtml} 同义（可读别名） */
    static String cleanText(String raw) {
        return stripHtml(raw);
    }

    /** HTML 实体解码（命名实体 + 十进制/十六进制数字实体） */
    static String decodeEntities(String text) {
        if (text == null || text.isEmpty() || text.indexOf('&') < 0) return text == null ? "" : text;
        Matcher matcher = HTML_ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(1);
            String replacement = null;
            if (body.startsWith("#")) {
                try {
                    int codePoint = (body.length() > 1 && (body.charAt(1) == 'x' || body.charAt(1) == 'X'))
                            ? Integer.parseInt(body.substring(2), 16)
                            : Integer.parseInt(body.substring(1));
                    replacement = new String(Character.toChars(codePoint));
                } catch (Exception ignored) {
                    replacement = null;
                }
            } else {
                replacement = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<String, String> buildEntities() {
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("nbsp", " ");
        entities.put("ensp", " ");
        entities.put("emsp", " ");
        entities.put("amp", "&");
        entities.put("lt", "<");
        entities.put("gt", ">");
        entities.put("quot", "\"");
        entities.put("apos", "'");
        entities.put("ldquo", "“");
        entities.put("rdquo", "”");
        entities.put("lsquo", "‘");
        entities.put("rsquo", "’");
        entities.put("mdash", "—");
        entities.put("ndash", "–");
        entities.put("hellip", "…");
        entities.put("middot", "·");
        entities.put("bull", "•");
        entities.put("copy", "©");
        entities.put("reg", "®");
        entities.put("trade", "™");
        entities.put("times", "×");
        entities.put("divide", "÷");
        entities.put("yen", "¥");
        entities.put("euro", "€");
        entities.put("deg", "°");
        return Map.copyOf(entities);
    }

    private static String normalizeUrl(String raw) {
        return NewsSourceCatalog.normalizeHttpUrl(cleanText(raw));
    }

    private static String domainOf(String feedUrl) {
        try {
            URI uri = URI.create(feedUrl == null ? "" : feedUrl.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) return DEFAULT_SOURCE;
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("www.")) normalized = normalized.substring(4);
            return clip(normalized, MAX_SOURCE);
        } catch (Exception e) {
            return DEFAULT_SOURCE;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ==================== 关键词归类 / 重要度 / 标签（纯函数，可单测） ====================

    /** 分类关键词（顺序即同分优先级；英文关键词按词边界匹配，避免 ai 命中 said） */
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = buildCategoryKeywords();

    private static Map<String, List<String>> buildCategoryKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("price", List.of("价格", "报价", "涨", "跌", "上调", "下调", "行情", "涨幅", "跌幅",
                "走高", "回落", "企稳", "坚挺", "涨价", "降价", "收涨", "收跌"));
        keywords.put("supply", List.of("供应", "供给", "库存", "产能", "限产", "交期", "减产", "停产",
                "开工率", "缺货", "错峰生产"));
        keywords.put("policy", List.of("政策", "监管", "补贴", "关税", "环保", "督察", "碳关税", "碳达峰",
                "碳中和", "法规", "通知", "条例", "方案", "国务院"));
        keywords.put("international", List.of("国际", "全球", "汇率", "运价", "出口", "进口", "海外",
                "外贸", "美元", "欧盟", "地缘", "能源", "原油", "海运"));
        keywords.put("demand", List.of("需求", "订单", "采购", "补库", "询盘", "销量", "消费", "备货", "回暖"));
        keywords.put("tech", List.of("芯片", "ai", "技术", "元器件", "算力", "半导体", "光伏", "电池",
                "新能源", "人工智能", "mlcc"));
        return Map.copyOf(keywords);
    }

    /**
     * 资讯分类：price/supply/policy/international/demand/tech，默认 market。
     * 标题关键词权重 ×2（标题更能代表主题），同分按 price→supply→policy→international→demand→tech 优先。
     */
    static String classify(String title, String summary) {
        String titleText = cleanText(title).toLowerCase(Locale.ROOT);
        String summaryText = cleanText(summary).toLowerCase(Locale.ROOT);
        String bestCategory = "market";
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                score += 2 * keywordScore(titleText, keyword) + keywordScore(summaryText, keyword);
            }
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }
        return bestCategory;
    }

    private static int keywordScore(String haystack, String keyword) {
        if (haystack.isEmpty() || keyword.isEmpty()) return 0;
        if (isAsciiWord(keyword)) {
            Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyword) + "(?![a-z0-9])");
            return pattern.matcher(haystack).find() ? keyword.length() : 0;
        }
        return haystack.contains(keyword) ? keyword.length() : 0;
    }

    private static boolean isAsciiWord(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 标题命中"涨/跌/政策/限产/关税/库存"等关键词 → 重要度 2，否则 1 */
    private static final List<String> IMPORTANCE_KEYWORDS = List.of(
            "涨", "跌", "政策", "限产", "关税", "库存", "上调", "下调", "补贴", "监管",
            "停产", "减产", "缺货", "降价", "环保", "督察");

    static int importanceOf(String title) {
        String titleText = cleanText(title).toLowerCase(Locale.ROOT);
        for (String keyword : IMPORTANCE_KEYWORDS) {
            if (keywordScore(titleText, keyword) > 0) return 2;
        }
        return 1;
    }

    /** 标签关键词（命中前 3 个，逗号分隔） */
    private static final List<String> TAG_KEYWORDS = List.of(
            "价格", "报价", "涨", "跌", "上调", "下调", "行情", "库存", "供应", "供给", "产能",
            "限产", "交期", "政策", "监管", "补贴", "关税", "环保", "国际", "全球", "汇率", "运价",
            "出口", "进口", "需求", "订单", "采购", "补库", "芯片", "ai", "算力", "技术", "元器件",
            "光伏", "新能源");

    static String tagsOf(String title, String summary) {
        String text = (cleanText(title) + " " + cleanText(summary)).toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        for (String keyword : TAG_KEYWORDS) {
            if (tags.size() >= 3) break;
            if (tags.contains(keyword)) continue;
            if (keywordScore(text, keyword) > 0) tags.add(keyword);
        }
        return clip(String.join(",", tags), MAX_TAGS);
    }

    // ==================== 业务相关性过滤（纯函数，可单测） ====================
    //
    // 外部 RSS（IT之家/36氪/虎嗅/WSJ 等）含大量娱乐/消费电子/会员促销内容（游戏新作、手机发布、
    // 网盘会员促销…），这些条目进入 t_market_news 后不仅让「每日资讯」偏离 B2B 定位，还会经
    // KnowledgeIngestService 灌入 news 向量源污染 RAG 上下文。故入库前做相关性过滤：
    //   命中 BUSINESS 信号 → 保留（即使同时含「华为/手机/显卡」等词，也可能是产业链新闻）；
    //   不含 BUSINESS 信号（含 BLOCK 娱乐/消费电子或纯无关内容）→ 过滤。
    // 可用 AI_EXTERNAL_RELEVANCE_FILTER=false 关闭。

    /** 工业/商业/平台业务信号（命中即视为与 B2B 市场资讯相关） */
    static final List<String> BUSINESS_SIGNALS = List.of(
            // 大宗/原材料/制造业
            "钢", "钢铁", "水泥", "建材", "化工", "化纤", "纺织", "塑料", "造纸", "纸浆",
            "铜", "铝", "锂", "稀土", "有色", "能源", "原油", "煤炭", "电力", "光伏", "电池",
            "芯片", "半导体", "元器件", "电子", "汽车", "机械", "制造业", "工业", "供应链",
            "原料", "原材料", "大宗", "期货", "现货", "报价", "价格", "涨价", "跌价", "行情",
            "库存", "供应", "供给", "产能", "限产", "停产", "减产", "开工率", "交期", "补库",
            "采购", "订单", "招标", "营收", "净利", "利润", "财报", "业绩", "市值", "股价",
            "证券", "基金", "债券", "银行", "信贷", "保险", "金融", "财政", "货币", "利率",
            "股市", "指数", "大盘", "收盘", "开盘", "涨停", "跌停", "央行", "降息", "加息",
            "黄金", "白银", "贵金属", "天然气", "燃气", "燃油", "汽油", "柴油", "矿产", "矿业",
            "建筑", "施工", "房地产", "房贷", "房价", "业主", "购房", "经销商", "分销", "税",
            // 政策/贸易/物流/宏观
            "政策", "监管", "补贴", "环保", "督察", "关税", "出口", "进口", "外贸", "海外",
            "物流", "运价", "海运", "港口", "汇率", "美元", "融资", "投资", "并购", "上市",
            "市场", "行业", "宏观", "经济", "零售", "电商", "PMI", "GDP", "CPI", "B2B",
            // 英文（WSJ Markets 等外文源）
            "market", "markets", "stock", "stocks", "shares", "earnings", "revenue", "profit",
            "ipo", "funding", "investment", "merger", "acquisition", "supply", "demand", "price",
            "prices", "pricing", "commodity", "commodities", "manufacturing", "factory",
            "industrial", "export", "exports", "import", "imports", "tariff", "tariffs",
            "logistics", "freight", "energy", "oil", "lithium", "copper", "aluminum", "steel",
            "cement", "chemical", "chemicals", "textile", "electronics", "semiconductor",
            "chip", "chips", "procurement", "inventory", "capacity", "policy", "regulation",
            "inflation", "economy", "economic", "trade", "b2b",
            "investor", "investors", "company", "companies", "trading", "trader", "traders",
            "bond", "bonds", "yield", "yields", "dollar", "currency", "federal", "reserve",
            "treasury", "dow", "nasdaq", "index", "indexes", "deal", "deals",
            "gold", "silver", "comex", "crude", "brent", "gas", "natural gas", "metals",
            "metal", "mining", "miner", "miners", "construction", "building", "buildings",
            "distributor", "distributors", "bid", "bids", "tax", "taxes", "bank", "banks",
            "banking", "real estate", "housing", "property", "mortgage", "buyer", "buyers");

    /** 娱乐/消费电子/会员促销等非业务信号（仅用于日志诊断；无 BUSINESS 时一律过滤） */
    static final List<String> BLOCK_SIGNALS = List.of(
            "游戏", "手游", "网游", "电竞", "电影", "电视剧", "综艺", "明星", "偶像", "娱乐",
            "动漫", "显卡", "手机", "耳机", "手表", "平板", "笔记本", "数码", "消费电子",
            "智能家居", "新品发布", "发布会", "会员", "促销", "优惠券", "秒杀", "抽奖", "红包",
            "折扣", "网盘", "评测", "开箱",
            "iphone", "android", "smartphone", "headphone", "earbuds", "smartwatch", "tablet",
            "laptop", "gadget", "console", "playstation", "xbox", "nintendo", "netflix",
            "disney", "spotify", "movie", "movies", "film", "celebrity", "gaming", "game",
            "games", "streaming", "coupon", "promotion", "vip");

    /**
     * 标题级硬过滤：娱乐/消费电子/数码/会员促销/生活方式类资讯一律不入库（哪怕正文含营收、市场、
     * 价格等商业词）。B2B 平台只保留工业/贸易/政策/企业财报类资讯，避免"每日资讯"和 RAG 上下文
     * 被消费类内容污染（实测漏网：刺客信条、泡泡玛特电影、剪映/迅雷会员、手机智驾视频…）。
     */
    static final List<String> HARD_BLOCK_SIGNALS = List.of(
            "游戏", "手游", "网游", "电竞", "主机", "电影", "电视剧", "综艺", "明星", "偶像",
            "动漫", "潮玩", "盲盒", "泡泡玛特", "labubu", "影视", "票房", "剧集", "演唱会",
            "手机", "耳机", "手表", "平板", "笔记本", "显卡", "数码", "消费电子", "智能家居",
            "网盘", "会员", "促销", "优惠券", "秒杀", "抽奖", "红包", "折扣", "新品发布",
            "发布会", "首发", "开箱", "评测", "视频", "直播", "网友", "男子", "女子", "小伙",
            "智驾", "试驾", "车主", "驾驶", "车评", "新车", "壁纸", "上手", "图赏", "体验",
            "game", "games", "gaming", "movie", "movies", "film", "celebrity", "netflix",
            "disney", "spotify", "playstation", "xbox", "nintendo", "anime", "pop mart",
            "iphone", "android", "smartphone", "headphone", "earbuds", "smartwatch", "tablet",
            "laptop", "gadget", "console", "streaming", "coupon", "promotion", "vip");

    /**
     * 是否与 B2B 市场资讯相关：标题命中硬过滤词（娱乐/数码/会员促销/生活方式）→ 直接过滤；
     * 其余条目要求标题+摘要命中 BUSINESS（工业/贸易/政策/企业财报）信号。
     */
    /**
     * B2B 业务相关性判定（写入侧与读取侧共用同一口径）。
     *
     * <p>公开为 {@code public} 以便每日资讯读取侧复用，避免"写入过滤一套、读取展示另一套"
     * 导致历史脏数据（过滤上线前入库的消费类资讯）继续出现在首页。
     */
    public static boolean isBusinessRelevant(String title, String summary) {
        String titleText = cleanText(title).toLowerCase(Locale.ROOT);
        if (containsAnySignal(titleText, HARD_BLOCK_SIGNALS)) return false;
        String text = (cleanText(title) + " " + cleanText(summary)).toLowerCase(Locale.ROOT);
        return containsAnySignal(text, BUSINESS_SIGNALS);
    }

    /** 是否含业务信号（区分「命中 BLOCK 但含业务词」与「完全无业务信号」两类被过滤原因） */
    static boolean hasBusinessSignal(String text) {
        if (text == null || text.isBlank()) return false;
        return containsAnySignal(cleanText(text).toLowerCase(Locale.ROOT), BUSINESS_SIGNALS);
    }

    /** 是否命中娱乐/消费电子/会员促销等非业务信号（日志诊断用） */
    static boolean hasBlockSignal(String text) {
        if (text == null || text.isBlank()) return false;
        return containsAnySignal(cleanText(text).toLowerCase(Locale.ROOT), BLOCK_SIGNALS);
    }

    private static boolean containsAnySignal(String text, List<String> signals) {
        for (String signal : signals) {
            if (keywordScore(text, signal) > 0) return true;
        }
        return false;
    }

    // ==================== 时间解析（纯函数，可单测） ====================

    private static final List<DateTimeFormatter> OFFSET_FORMATTERS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z", Locale.ENGLISH));

    private static final List<DateTimeFormatter> LOCAL_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH));

    /** 去掉星期前缀后的兜底格式（部分 RSS 的星期与日期不一致，严格解析会冲突） */
    private static final List<DateTimeFormatter> WEEKDAYLESS_OFFSET_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss XXX", Locale.ENGLISH));

    /**
     * 解析 RSS 发布时间（RFC1123/RFC822、ISO、36氪"yyyy-MM-dd HH:mm:ss +0800"等常见格式）；
     * 全部失败返回 null（调用方用 now 兜底）。带时区的时间统一换算到东八区。
     */
    static LocalDateTime parsePublishedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace('\u00a0', ' ').replaceAll("\\s+", " ");
        for (DateTimeFormatter formatter : OFFSET_FORMATTERS) {
            try {
                return OffsetDateTime.parse(value, formatter)
                        .atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        // 宽容兜底：星期与日期不一致的 RSS（如 "Mon, 27 Oct 2026 ..." 实际为周二）去掉星期前缀
        String withoutWeekday = value.replaceFirst("^[A-Za-z]{3,9},?\\s+", "");
        if (!withoutWeekday.equals(value)) {
            for (DateTimeFormatter formatter : WEEKDAYLESS_OFFSET_FORMATTERS) {
                try {
                    return OffsetDateTime.parse(withoutWeekday, formatter)
                            .atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
                } catch (Exception ignored) {
                    // 尝试下一种格式
                }
            }
        }
        for (DateTimeFormatter formatter : LOCAL_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception ignored) {
            return null;
        }
    }
}

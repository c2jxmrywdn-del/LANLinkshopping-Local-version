package com.lanlink.shopping.module.aikb;

import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import com.lanlink.shopping.module.pricemonitor.service.NewsSourceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>链路：AI_EXTERNAL_FEEDS 配置的 RSS（默认 IT之家 / 36氪 / 虎嗅 / WSJ Markets，均为后端容器
 * 实测可访问的源）→ JDK HttpClient 抓取（UA=浏览器、connect 5s/request 20s、响应上限 2MB）
 * → JDK DocumentBuilder 安全解析（禁 DTD/外部实体/XInclude/实体展开）→ HTML 清洗 + 关键词归类
 * → 标题查重 → 写入 t_market_news（url 保留真实原文链接，供每日资讯跳转）
 * → 有新条目时调用 {@link KnowledgeIngestService#ingestNews()} 刷新向量库 news 源。
 *
 * <p>摘要增强：摘要为空或过短（&lt;40 字）的条目，单轮最多取前 2 条调用本地 qwen2.5-7B
 * （{@link EmbeddingService#chatText}）生成 80-120 字中文摘要；失败/超时保留原始摘要，
 * 绝不影响抓取主链路。
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
            "https://www.ithome.com/rss/,https://www.36kr.com/feed,"
                    + "https://rss.huxiu.com/,https://feeds.a.dj.com/rss/RSSMarketsMain.xml";

    /** 单次响应体上限 2MB（超过直接跳过，避免恶意/异常大文件拖垮内存） */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    /** 单个 feed 单轮最多入库条数 */
    static final int MAX_PER_FEED = 15;
    /** 管理端返回的 items 明细上限 */
    static final int MAX_ITEMS_KEPT = 10;
    /** 摘要短于该长度视为"过短"，可交给本地文本模型润色/补全 */
    static final int LLM_SUMMARY_MIN_CHARS = 40;
    /** 单轮最多调用本地文本模型生成摘要的条数 */
    static final int LLM_SUMMARY_MAX_PER_RUN = 2;

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
    private final HttpClient http;
    /** 整轮抓取互斥（调度线程 + 管理端手动触发并发防护） */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 并行抓取线程池：多个 RSS 源并发拉取，整轮耗时 ≈ 最慢源而非各源之和 */
    private final java.util.concurrent.ExecutorService feedPool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "external-feed-fetch");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    void shutdownFeedPool() {
        feedPool.shutdownNow();
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

    public ExternalInfoFetchService(MarketNewsService newsService,
                                    KnowledgeIngestService ingestService,
                                    EmbeddingService embeddingService,
                                    @Value("${AI_EXTERNAL_ENABLED:true}") boolean enabled,
                                    @Value("${AI_EXTERNAL_FEEDS:" + DEFAULT_FEEDS + "}") String feedConfig,
                                    @Value("${AI_EXTERNAL_LIMIT:30}") int limit,
                                    @Value("${AI_EXTERNAL_LLM_SUMMARY:true}") boolean llmSummaryEnabled,
                                    @Value("${AI_EXTERNAL_RELEVANCE_FILTER:true}") boolean relevanceFilter) {
        this.newsService = newsService;
        this.ingestService = ingestService;
        this.embeddingService = embeddingService;
        this.enabled = enabled;
        this.feedUrls = parseFeedConfig(feedConfig);
        this.maxPerRun = limit > 0 ? limit : 30;
        this.llmSummaryEnabled = llmSummaryEnabled;
        this.relevanceFilter = relevanceFilter;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("[external-fetch] configured {} RSS feeds (enabled={}, maxPerRun={}, llmSummary={}, relevanceFilter={})",
                feedUrls.size(), enabled, maxPerRun, llmSummaryEnabled, relevanceFilter);
    }

    // ==================== 调度 / 手动触发 ====================

    /** 每 2 小时自动抓取（启动后 3 分钟首跑）；开关关闭时跳过 */
    @Scheduled(fixedDelay = 2 * 3600_000L, initialDelay = 180_000L)
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
                if (inserted >= effectiveMax) continue;
                int perFeedMax = Math.min(MAX_PER_FEED, effectiveMax - inserted);
                if (perFeedMax <= 0) continue;
                Map<String, Object> stored = storeParsedItems(parsed, perFeedMax, items);
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
        return storeParsedItems(parsed, max, new ArrayList<>());
    }

    Map<String, Object> storeParsedItems(List<Map<String, String>> parsed, int max,
                                         List<Map<String, Object>> itemViews) {
        int inserted = 0;
        int skipped = 0;
        int filtered = 0;
        int llmSummaries = 0;
        if (parsed != null) {
            for (Map<String, String> row : parsed) {
                if (inserted >= max) break;
                MarketNews news = toNews(row);
                if (news == null) {
                    skipped++;
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
                    if (newsService.existsByTitle(news.getTitle())) {
                        skipped++;
                        continue;
                    }
                    // 本地 7B 摘要增强：仅对摘要为空/过短的条目，单轮最多 2 条；失败保留原摘要
                    if (llmSummaryEnabled && llmSummaries < LLM_SUMMARY_MAX_PER_RUN
                            && needsLlmSummary(news.getSummary())) {
                        String enhanced = generateLlmSummary(news.getTitle(), news.getSummary());
                        if (enhanced != null) {
                            news.setSummary(enhanced);
                            llmSummaries++;
                        }
                    }
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
        news.setPublishedAt(publishedAt == null ? LocalDateTime.now() : publishedAt);
        news.setCreatedAt(LocalDateTime.now());
        return news;
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

    /** 本地 7B 生成 80-120 字中文摘要（失败/超时返回 null，保留原摘要） */
    String generateLlmSummary(String title, String summary) {
        if (embeddingService == null) return null;
        try {
            String system = "你是中文资讯摘要助手。请用 80-120 个中文字符概括资讯要点，"
                    + "只输出摘要正文，不要标题、不要引号、不要解释、不要 Markdown。";
            String user = "标题：" + clip(trimToEmpty(title), 120) + "\n正文：" + clip(trimToEmpty(summary), 800);
            String generated = CompletableFuture
                    .supplyAsync(() -> embeddingService.chatText(system, user))
                    .get(20, TimeUnit.SECONDS);
            if (generated == null || generated.isBlank()) return null;
            String cleaned = generated.replaceAll("\\s+", " ").trim();
            if (cleaned.length() < 20) return null;
            return clip(cleaned, MAX_SUMMARY);
        } catch (Exception e) {
            log.debug("[external-fetch] llm summary failed, keep original summary: {}", e.getMessage());
            return null;
        }
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
            String sourceName = clip(cleanText(childText(channel, "title")), MAX_SOURCE);
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
        return factory.newDocumentBuilder();
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
    static boolean isBusinessRelevant(String title, String summary) {
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

package com.lanlink.shopping.module.pricemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 市场资讯网络检索（Bing RSS，免 key、国内可达——与图片爬取同源已验证路线）。
 *
 * <p>按"类目名 + 行情"等关键词检索外部市场信息，经 {@link AnalysisSafetyFilter} 过滤有害
 * 内容后返回结构化条目，供五维因子自动分析做信息增强。任何失败返回空列表，绝不抛出。
 */
@Slf4j
@Service
public class MarketWebSearchService {

    public record WebInfoItem(String title, String url, String snippet) {}

    /** 实时新闻条目：标题、原文链接、摘要、媒体名、发布时间。 */
    public record LiveNewsItem(String title, String url, String snippet, String source, String publishedAt) {}

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36";
    private static final int TIMEOUT_SECONDS = 12;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AnalysisSafetyFilter safetyFilter;

    public MarketWebSearchService(AnalysisSafetyFilter safetyFilter) {
        this.safetyFilter = safetyFilter;
    }

    private static final Set<String> QUERY_STOP = Set.of("价格", "行情", "市场", "行业", "商品", "袋装", "散装");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,8}");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]{2,8}");

    /** 用商品标题和行业名收检索词。过宽词丢掉，避免搜出无关稿。 */
    public static List<String> productTerms(String title, String industry) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String raw = title == null ? "" : title;
        Matcher latin = LATIN.matcher(raw);
        while (latin.find()) terms.add(latin.group());
        Matcher cjk = CJK_RUN.matcher(raw);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() <= 6 && !QUERY_STOP.contains(run)) terms.add(run);
            if (run.contains("水泥")) terms.add("水泥");
            if (run.contains("水管")) terms.add("水管");
            if (run.contains("管材")) terms.add("管材");
            if (run.contains("钢材") || run.contains("螺纹")) terms.add("钢材");
        }
        if (industry != null) {
            String core = industry.replace("行业", "").replace("产业", "").trim();
            if (core.length() >= 2 && !QUERY_STOP.contains(core)) terms.add(core);
        }
        terms.removeIf(t -> t == null || t.length() < 2 || QUERY_STOP.contains(t));
        return List.copyOf(terms);
    }

    /** 实时新闻：必应资讯 RSS。条目必须命中商品词，否则丢弃。 */
    public List<LiveNewsItem> searchLiveNews(String query, List<String> mustHit, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return List.of();
        int size = Math.min(Math.max(limit, 1), 8);
        try {
            String encoded = java.net.URLEncoder.encode(q, StandardCharsets.UTF_8);
            List<LiveNewsItem> recent = fetchLiveRss(
                    "https://www.bing.com/news/search?format=rss&setlang=zh-cn&qft=interval%3d%227%22&q=" + encoded,
                    mustHit, size);
            if (!recent.isEmpty()) return recent;
            return fetchLiveRss(
                    "https://www.bing.com/news/search?format=rss&setlang=zh-cn&q=" + encoded,
                    mustHit, size);
        } catch (Exception e) {
            log.warn("[market-news-live] '{}' failed: {}", q, e.getMessage());
            return List.of();
        }
    }

    private List<LiveNewsItem> fetchLiveRss(String url, List<String> mustHit, int size) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("[market-news-live] HTTP {} ", resp.statusCode());
            return List.of();
        }
        return parseLiveRss(resp.body(), mustHit, size);
    }
    public List<WebInfoItem> searchMarketInfo(String query, int limit) {
        String q = (query == null ? "" : query.trim());
        if (q.isEmpty()) return List.of();
        try {
            String url = "https://cn.bing.com/search?format=rss&count=" + Math.min(Math.max(limit, 1), 20)
                    + "&q=" + java.net.URLEncoder.encode(q + " 行情", StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[market-search] HTTP {} for '{}'", resp.statusCode(), q);
                return List.of();
            }
            return parseRss(resp.body(), limit);
        } catch (Exception e) {
            log.warn("[market-search] '{}' failed: {}", q, e.getMessage());
            return List.of();
        }
    }

    /** RSS XML 解析（item/title/link/description），逐条过有害过滤 */
    private List<WebInfoItem> parseRss(String xml, int limit) {
        List<WebInfoItem> out = new ArrayList<>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength() && out.size() < limit; i++) {
                Element el = (Element) items.item(i);
                String title = textOf(el, "title");
                String link = textOf(el, "link");
                String snippet = textOf(el, "description");
                var verdict = safetyFilter.checkText(title + "\n" + snippet);
                if (!verdict.clean()) {
                    log.info("[market-search] dropped harmful item: {}", verdict.reason());
                    continue;
                }
                out.add(new WebInfoItem(
                        clip(title, 160),
                        link,
                        clip(snippet.replaceAll("<[^>]+>", " "), 300)));
            }
        } catch (Exception e) {
            log.warn("[market-search] rss parse failed: {}", e.getMessage());
        }
        return out;
    }

    private List<LiveNewsItem> parseLiveRss(String xml, List<String> mustHit, int limit) {
        List<LiveNewsItem> out = new ArrayList<>();
        List<String> hits = mustHit == null ? List.of() : mustHit.stream()
                .filter(t -> t != null && t.length() >= 2)
                .toList();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength() && out.size() < limit; i++) {
                Element el = (Element) items.item(i);
                String title = textOf(el, "title");
                String link = textOf(el, "link");
                String snippet = textOf(el, "description").replaceAll("<[^>]+>", " ");
                String source = textOf(el, "Source");
                if (source.isBlank()) {
                    NodeList children = el.getChildNodes();
                    for (int c = 0; c < children.getLength(); c++) {
                        if (!(children.item(c) instanceof Element child)) continue;
                        String name = child.getNodeName();
                        if (name != null && (name.equals("Source") || name.endsWith(":Source"))) {
                            source = StringUtils.trimWhitespace(child.getTextContent());
                            break;
                        }
                    }
                }
                if (source.isBlank()) source = "必应资讯";
                String blob = title.toLowerCase(Locale.ROOT);
                boolean matched = hits.isEmpty() || hits.stream().anyMatch(t -> blob.contains(t.toLowerCase(Locale.ROOT)));
                if (!matched || title.isBlank() || link.isBlank()) continue;
                var verdict = safetyFilter.checkText(title + "\n" + snippet);
                if (!verdict.clean()) continue;
                out.add(new LiveNewsItem(clip(title, 160), link, clip(snippet, 300), source, textOf(el, "pubDate")));
            }
        } catch (Exception e) {
            log.warn("[market-news-live] rss parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static String textOf(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        return nl.getLength() > 0 ? StringUtils.trimWhitespace(nl.item(0).getTextContent()) : "";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}

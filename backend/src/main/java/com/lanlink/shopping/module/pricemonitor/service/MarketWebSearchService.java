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
import java.util.List;

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

    /** 检索市场资讯：query 会与"行情"组合；失败返回空列表 */
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

    private static String textOf(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        return nl.getLength() > 0 ? StringUtils.trimWhitespace(nl.item(0).getTextContent()) : "";
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}

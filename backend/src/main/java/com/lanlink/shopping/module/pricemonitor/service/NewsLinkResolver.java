package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资讯源头链接解析器（前端 utils/newsLink.js 的后端契约）：
 *   为每条资讯补齐 { url, sourceUrl, searchUrl, linkType }——
 *   linkType: article 原文 / source 来源站点 / search 检索兜底 / internal 站内。
 * 来源站点映射：覆盖平台资讯的主要信息渠道；未知来源退化为按标题的资讯检索页，
 * 保证每条资讯都可"导向信息源头网页"。
 */
@Service
public class NewsLinkResolver {

    /** 来源名 → 来源站点首页/频道（contains 匹配） */
    private static final Map<String, String> SOURCE_SITES = new LinkedHashMap<>();

    static {
        SOURCE_SITES.put("钢铁", "https://www.lgmi.com");
        SOURCE_SITES.put("兰格", "https://www.lgmi.com");
        SOURCE_SITES.put("有色", "https://www.smm.cn");
        SOURCE_SITES.put("金属", "https://www.smm.cn");
        SOURCE_SITES.put("外汇", "https://www.fx678.com");
        SOURCE_SITES.put("航运", "https://www.ship.sh");
        SOURCE_SITES.put("港口", "https://www.ship.sh");
        SOURCE_SITES.put("环保", "https://www.mee.gov.cn");
        SOURCE_SITES.put("生态", "https://www.mee.gov.cn");
        SOURCE_SITES.put("能源", "https://www.in-en.com");
        SOURCE_SITES.put("油价", "https://www.in-en.com");
        SOURCE_SITES.put("政策", "https://www.gov.cn");
        SOURCE_SITES.put("工业", "https://www.miit.gov.cn");
        SOURCE_SITES.put("PMI", "https://www.stats.gov.cn");
        SOURCE_SITES.put("统计", "https://www.stats.gov.cn");
    }

    /**
     * 解析单条资讯的链接四件套。
     *   url       原文链接（数据自带，可能为空）
     *   sourceUrl 来源站点（按来源名映射站点官网）
     *   searchUrl 检索兜底（按标题的资讯检索页，始终可用）
     *   linkType  article / source / search / internal
     */
    public Map<String, Object> resolve(MarketNews n) {
        String url = blankToNull(n.getUrl());
        String sourceUrl = resolveSourceSite(n.getSource());
        String searchUrl = "https://www.baidu.com/s?wd="
                + URLEncoder.encode(n.getTitle() == null ? "市场资讯" : n.getTitle(), StandardCharsets.UTF_8);
        String linkType;
        if (url != null && url.startsWith("/")) linkType = "internal";
        else if (url != null) linkType = "article";
        else if (sourceUrl != null) linkType = "source";
        else linkType = "search";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("sourceUrl", sourceUrl);
        m.put("searchUrl", searchUrl);
        m.put("linkType", linkType);
        return m;
    }

    /** 来源站点解析：来源名含关键词即命中；未知来源返回 null（由 searchUrl 兜底） */
    public String resolveSourceSite(String source) {
        if (source == null || source.isBlank()) return null;
        for (Map.Entry<String, String> e : SOURCE_SITES.entrySet()) {
            if (source.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

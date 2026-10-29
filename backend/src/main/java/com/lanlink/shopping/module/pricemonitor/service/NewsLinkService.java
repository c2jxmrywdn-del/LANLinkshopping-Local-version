package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资讯来源链接解析（每日资讯"导向信息源头网页"的统一出口）。
 *
 * <p>解析优先级：数据库原文链接（article）→ 平台自产来源站内页（internal）→
 * 来源官方站点首页（source）→ 必应资讯检索（search）。任何一条资讯都能拿到一个可点击的
 * {@code url}，前端按 {@code linkType} 决定新开页签还是站内路由。
 */
@Service
public class NewsLinkService {

    /**
     * 解析一条资讯的来源链接。
     *
     * @return 固定键：url（最终跳转地址，可站内相对路径）、sourceUrl（来源官网，可为 null）、
     *         searchUrl（检索兜底，始终存在）、linkType（article/source/search/internal）
     */
    public Map<String, Object> linkOf(MarketNews news) {
        String source = news == null ? null : news.getSource();
        String title = news == null ? null : news.getTitle();
        String article = NewsSourceCatalog.normalizeHttpUrl(news == null ? null : news.getUrl());
        String home = NewsSourceCatalog.homeOf(source);
        String search = NewsSourceCatalog.searchUrl(title, source);
        String internal = NewsSourceCatalog.internalPathOf(source);

        String url;
        String linkType;
        if (article != null) {
            url = article;
            linkType = "article";
        } else if (internal != null) {
            url = internal;
            linkType = "internal";
        } else if (home != null) {
            url = home;
            linkType = "source";
        } else {
            url = search;
            linkType = "search";
        }

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("url", url);
        link.put("sourceUrl", home);
        link.put("searchUrl", search);
        link.put("linkType", linkType);
        return link;
    }

    /** 把链接字段并入已有 map（键名与 {@link #linkOf} 一致，供五维因子的 newsRefs 复用）。 */
    public void apply(Map<String, Object> target, MarketNews news) {
        if (target == null) return;
        target.putAll(linkOf(news));
    }
}

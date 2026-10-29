package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 市场资讯读取（价格情报台信息流 + AI 分析上下文）+ 外部抓取写入。 */
@Service
public class MarketNewsService {

    private static final Logger log = LoggerFactory.getLogger(MarketNewsService.class);

    private final MarketNewsMapper mapper;
    private final NewsLinkService linkService;

    public MarketNewsService(MarketNewsMapper mapper, NewsLinkService linkService) {
        this.mapper = mapper;
        this.linkService = linkService;
    }

    public List<MarketNews> list(String category, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return mapper.selectList(Wrappers.<MarketNews>lambdaQuery()
                .eq(category != null && !category.isBlank(), MarketNews::getCategory, category)
                .orderByDesc(MarketNews::getPublishedAt)
                .last("LIMIT " + size));
    }

    public List<MarketNews> latest(int limit) {
        return list(null, limit);
    }

    // ==================== 外部资讯自动抓取写入（ExternalInfoFetchService 使用） ====================

    /**
     * 标题查重（对应唯一键 {@code uk_news_title}）。外部 RSS 抓取入库前的幂等判断：
     * 同一 title 已存在则跳过，避免重复灌入同一篇资讯。
     */
    public boolean existsByTitle(String title) {
        if (title == null || title.isBlank()) return false;
        try {
            Long count = mapper.selectCount(Wrappers.<MarketNews>lambdaQuery()
                    .eq(MarketNews::getTitle, title.trim()));
            return count != null && count > 0;
        } catch (Exception e) {
            // 查询失败时按"不存在"继续，最终由唯一键兜底（saveExternal 捕获冲突返回 false）
            log.warn("[market-news] existsByTitle failed, fallback insert: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写入一条外部抓取资讯（外部 RSS → t_market_news）。
     *
     * <p>与既有信息流读取方法（{@link #list}/{@link #latest}/{@link #feed}）完全解耦：
     * 唯一键冲突（并发/重跑/多实例）不抛异常，返回 false 交由调用方计入"跳过"。
     */
    public boolean saveExternal(MarketNews news) {
        if (news == null || news.getTitle() == null || news.getTitle().isBlank()) return false;
        try {
            return mapper.insert(news) > 0;
        } catch (DataIntegrityViolationException e) {
            // uk_news_title 唯一键冲突：已抓过（并发/多实例），静默跳过
            log.debug("[market-news] duplicate external title skipped: {}", news.getTitle());
            return false;
        }
    }

    /**
     * 每日资讯信息流：在 {@link #list(String, int)} 的过滤与条数规则之上，
     * 为每条资讯解析「信息源头网页」链接（url/sourceUrl/searchUrl/linkType）。
     *
     * <p>与 {@link #list} / {@link #latest} 的区别：这里返回前端可直接消费的 Map，
     * 链接字段不写入实体，避免污染 AI 知识库 ingest 的既有契约。
     */
    public List<Map<String, Object>> feed(String category, int limit) {
        List<MarketNews> rows = list(category, limit);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (MarketNews news : rows) {
            items.add(toFeedItem(news));
        }
        return items;
    }

    /** 单条资讯的接口视图：基础字段保持固定顺序，尾部并入来源链接字段。 */
    private Map<String, Object> toFeedItem(MarketNews news) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", news.getId());
        item.put("title", news.getTitle());
        item.put("summary", news.getSummary());
        item.put("category", news.getCategory());
        item.put("source", news.getSource());
        item.put("tags", news.getTags());
        item.put("importance", news.getImportance());
        item.put("publishedAt", news.getPublishedAt());
        item.put("createdAt", news.getCreatedAt());
        // url/sourceUrl/searchUrl/linkType：article 原文优先 → internal 站内 → source 官网 → search 检索
        item.putAll(linkService.linkOf(news));
        return item;
    }
}

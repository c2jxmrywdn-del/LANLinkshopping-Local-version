package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketNewsMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 市场资讯读取（价格情报台信息流 + AI 分析上下文）。 */
@Service
public class MarketNewsService {

    private final MarketNewsMapper mapper;

    public MarketNewsService(MarketNewsMapper mapper) {
        this.mapper = mapper;
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
}

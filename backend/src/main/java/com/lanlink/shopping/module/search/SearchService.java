package com.lanlink.shopping.module.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.aikb.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品搜索（Query 理解 + 双路召回 + RRF 融合）。
 *
 * <p>Query 理解：jieba 分词（com.huaban:jieba-analysis）切词、去停用词、限 6 词——
 * 把"螺纹钢 12mm HRB400"切成词面单元，多词 OR LIKE 召回（替代单串全模糊）。
 * 双路召回：A = 分词 LIKE（词面）；B = bge-m3 语义向量 KNN（词面盲区补足，
 * 如"手机"命中"智能移动电话"）。RRF 融合 score = Σ 1/(60+rank)，对分数尺度不敏感免调参。
 *
 * <p>回退链：向量路不可用（开关关/bge-m3 熔断/索引未建）→ 仅分词 LIKE；分词结果为空 → 原样整串 LIKE；
 * 全空 → 自然排序（调用方保底）。开关 lanlink.search.vector.enabled=false 完全回退 LIKE 现状。
 */
@Slf4j
@Service
public class SearchService {

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "和", "是", "在", "我", "有", "及", "与", "或", "求", "购", "请", "怎么",
            "如何", "什么", "哪些", "一个", "一下", "需要", "想要", "请问");

    private final ProductMapper productMapper;
    private final EmbeddingService embeddingService;
    private final ProductVectorStore vectorStore;

    @Value("${lanlink.search.vector.enabled:true}")
    private boolean vectorEnabled;

    public SearchService(ProductMapper productMapper, EmbeddingService embeddingService,
                         ProductVectorStore vectorStore) {
        this.productMapper = productMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * 语义 + 词面混合搜索分页。keyword 非空时调用；返回 Page（records/total 兼容 IPage 消费方）。
     */
    public Page<Product> search(String keyword, long current, long size) {
        long t0 = System.currentTimeMillis();
        List<String> tokenized = tokenize(keyword);
        // 分词无产出（纯符号等）：整串 LIKE 兜底；lambda 捕获需 effectively final
        final List<String> tokens = tokenized.isEmpty()
                ? List.of(keyword.trim()) : tokenized;

        // 路 A：分词 OR-LIKE（词面）
        List<Product> tokenHits = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                .and(w -> { for (String t : tokens) w.or().like(Product::getTitle, t); })
                .last("LIMIT 40"));

        // 路 B：语义向量 KNN（哈希降级 384 维向量不入索引，也不会作为查询向量）
        List<ProductVectorStore.VectorHit> vecHits = List.of();
        if (vectorEnabled && vectorStore.available()) {
            try {
                float[] qvec = embeddingService.embed(keyword);
                if (qvec != null && qvec.length != EmbeddingService.FALLBACK_DIM) {
                    vecHits = vectorStore.knn(qvec, 40);
                }
            } catch (Exception e) {
                log.warn("[search] vector recall failed, token-only: {}", e.getMessage());
            }
        }

        // RRF 融合：score = Σ 1/(60 + rank)
        Map<Long, Double> rrf = new HashMap<>();
        for (int i = 0; i < tokenHits.size(); i++) {
            rrf.merge(tokenHits.get(i).getProdId(), 1.0 / (60 + i), Double::sum);
        }
        for (int i = 0; i < vecHits.size(); i++) {
            rrf.merge(vecHits.get(i).prodId(), 1.0 / (60 + i), Double::sum);
        }
        if (rrf.isEmpty()) return emptyPage(current, size);

        List<Long> ordered = rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // 物化商品实体（保持融合序），再切页
        Map<Long, Product> byId = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .in(Product::getProdId, ordered))
                .stream().collect(java.util.stream.Collectors.toMap(Product::getProdId, p -> p, (a, b) -> a));
        List<Product> merged = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : ordered) {
            Product p = byId.get(id);
            if (p != null && seen.add(id)) merged.add(p);
        }

        long total = merged.size();
        int from = (int) Math.min((current - 1) * size, Math.max(total, 0));
        int to = (int) Math.min(from + size, total);
        Page<Product> out = new Page<>(current, size, total);
        out.setRecords(new ArrayList<>(merged.subList(from, to)));
        log.info("[search] q='{}' tokens={} tokenHits={} vecHits={} fused={} {}ms",
                keyword, tokens.size(), tokenHits.size(), vecHits.size(), total, System.currentTimeMillis() - t0);
        return out;
    }

    /** jieba 分词 + 停用词过滤 + 限 6 词 */
    static List<String> tokenize(String query) {
        if (!StringUtils.hasText(query)) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (com.huaban.analysis.jieba.SegToken t : new com.huaban.analysis.jieba.JiebaSegmenter()
                .process(query, com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH)) {
            String w = t.word.trim().toLowerCase();
            if (w.length() < 2 || STOPWORDS.contains(w) || !seen.add(w)) continue;
            out.add(w);
            if (out.size() >= 6) break;
        }
        return out;
    }

    private static Page<Product> emptyPage(long current, long size) {
        return new Page<>(current, size, 0);
    }
}

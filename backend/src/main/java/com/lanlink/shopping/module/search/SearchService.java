package com.lanlink.shopping.module.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserHabitMapper;
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
import java.util.stream.Collectors;

/**
 * 商品搜索（Query 理解 + 双路召回 + RRF 融合 + 多目标加权重排）。
 *
 * <p>Query 理解：jieba 分词（com.huaban:jieba-analysis）切词、去停用词、限 6 词——
 * 把"螺纹钢 12mm HRB400"切成词面单元，多词 OR LIKE 召回（替代单串全模糊）。
 * 双路召回：A = 分词 LIKE（词面）；B = bge-m3 语义向量 KNN（词面盲区补足，
 * 如"手机"命中"智能移动电话"）。RRF 融合 score = Σ 1/(60+rank)，对分数尺度不敏感免调参。
 *
 * <p>业务目标层（多目标融合打分）：RRF 分按池内最大值归一为 Rel，叠加 Per（画像类目偏好，
 * 双信号门控）/ Sales（池内销量归一）/ Stock（现货分）四维加权，见 {@link SearchRankScorer}。
 * Per 画像仅在 登录 + ll_analytics=1 Cookie 双信号成立时加载（与 RecommendService 同门控）。
 *
 * <p>回退链：向量路不可用（开关关/bge-m3 熔断/索引未建）→ 仅分词 LIKE；分词结果为空 → 原样整串 LIKE；
 * 全空 → 自然排序（调用方保底）。开关 lanlink.search.vector.enabled=false 完全回退 LIKE 现状；
 * lanlink.search.fusion.enabled=false 关闭多目标层，逐位回退纯 RRF 序。
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
    private final SearchRankScorer rankScorer;
    private final CategoryMapper categoryMapper;
    private final UserHabitMapper habitMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${lanlink.search.vector.enabled:true}")
    private boolean vectorEnabled;

    public SearchService(ProductMapper productMapper, EmbeddingService embeddingService,
                         ProductVectorStore vectorStore, SearchRankScorer rankScorer,
                         CategoryMapper categoryMapper, UserHabitMapper habitMapper) {
        this.productMapper = productMapper;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.rankScorer = rankScorer;
        this.categoryMapper = categoryMapper;
        this.habitMapper = habitMapper;
    }

    /**
     * 语义 + 词面混合搜索分页（无个性化信号版，兼容旧调用）。
     */
    public Page<Product> search(String keyword, long current, long size) {
        return search(keyword, current, size, null, null);
    }

    /**
     * 语义 + 词面混合搜索分页 + 多目标融合重排。keyword 非空时调用；
     * 返回 Page（records/total 兼容 IPage 消费方）。
     *
     * @param userId          登录用户 id（可空，Per 分量双信号之一）
     * @param analyticsCookie ll_analytics Cookie 值（可空，Per 分量双信号之一）
     */
    public Page<Product> search(String keyword, long current, long size, Long userId, String analyticsCookie) {
        long t0 = System.currentTimeMillis();
        List<String> tokenized = tokenize(keyword);
        // 分词无产出（纯符号等）：整串 LIKE 兜底；lambda 捕获需 effectively final
        final List<String> tokens = tokenized.isEmpty()
                ? List.of(keyword.trim()) : tokenized;

        // 路 A：逐 token 召回 + IDF 加权（稀有词权重高——"硅片"(df=2) 应压过"光伏"(df=8)）。
        // 每 token 独立 LIKE（限 60，按销量=相关性代理排序），credit = idf × 1/(60+rank)。
        long corpusSize = productMapper.selectCount(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0));
        Map<Long, Double> rrf = new HashMap<>();
        for (String t : tokens) {
            List<Product> hits = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                    .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                    .like(Product::getTitle, t)
                    .orderByDesc(Product::getSales)
                    .last("LIMIT 60"));
            double idf = Math.log(1 + corpusSize / (double) Math.max(hits.size(), 1));
            for (int i = 0; i < hits.size(); i++) {
                rrf.merge(hits.get(i).getProdId(), idf / (60 + i), Double::sum);
            }
        }

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

        // RRF 融合：语义路 rank 权重与词面路等价（idf 均值 ~4，量级一致）
        for (int i = 0; i < vecHits.size(); i++) {
            rrf.merge(vecHits.get(i).prodId(), 4.0 / (60 + i), Double::sum);
        }
        if (rrf.isEmpty()) return emptyPage(current, size);

        // 物化商品实体（融合打分需池内 sales/stock/catId 信号，统一在排序前物化），再切页
        Map<Long, Product> byId = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .in(Product::getProdId, rrf.keySet()))
                .stream().collect(Collectors.toMap(Product::getProdId, p -> p, (a, b) -> a));

        // Per 信号：双信号（登录 + ll_analytics=1）成立才加载画像类目偏好，否则空表（Per=0）
        Map<Long, Double> catPref = SearchRankScorer.perEligible(userId, analyticsCookie)
                ? loadCategoryPref(userId) : Map.of();

        // 多目标融合重排（fusion 开关关闭时逐位回退纯 RRF 序）
        List<Long> ordered = rankScorer.rank(rrf, byId, catPref, userId, analyticsCookie);

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
        log.info("[search] q='{}' tokens={} tokenPool={} vecHits={} fused={} fusion={} per={} {}ms",
                keyword, tokens.size(), rrf.size(), vecHits.size(), total,
                rankScorer.fusionEnabled(), !catPref.isEmpty(), System.currentTimeMillis() - t0);
        return out;
    }

    /**
     * 画像类目偏好（Per 信号源）：t_user_habit.payload.categories 类目名 → cat_id 精确反查
     * （与 RecommendService.resolveCategoryIds 同机制），权重 = 该类目次数 / 类目总次数。
     * 仅 consent=1 画像可用（findPayloadConsented 已过滤）；任何异常降级为空表（Per=0）。
     */
    private Map<Long, Double> loadCategoryPref(Long userId) {
        try {
            String payload = habitMapper.findPayloadConsented(userId);
            if (payload == null) return Map.of();
            JsonNode cats = objectMapper.readTree(payload).path("categories");
            if (!cats.isObject() || cats.isEmpty()) return Map.of();
            List<String> names = new ArrayList<>();
            cats.fieldNames().forEachRemaining(n -> { if (names.size() < 8) names.add(n); });
            if (names.isEmpty()) return Map.of();
            Map<String, Long> idByName = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                            .in(Category::getName, names)).stream()
                    .collect(Collectors.toMap(Category::getName, Category::getCatId, (a, b) -> a));
            Map<Long, Long> counts = new HashMap<>();
            cats.fields().forEachRemaining(en -> {
                Long catId = idByName.get(en.getKey());
                if (catId != null) counts.merge(catId, en.getValue().asLong(0), Long::sum);
            });
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            Map<Long, Double> pref = new HashMap<>();
            if (total > 0) counts.forEach((k, v) -> pref.put(k, v / (double) total));
            return pref;
        } catch (Exception e) {
            log.debug("[search] category pref load failed, per=0: {}", e.getMessage());
            return Map.of();
        }
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

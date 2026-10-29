package com.lanlink.shopping.module.recommend.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.recommend.mapper.RecommendStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 商品推荐服务：三路召回（content 画像内容 / cf 共现协同过滤 / hot 热度）+ 线性融合。
 *
 * <p>合规边界（重要）：
 * <ul>
 *   <li>个性化召回仅使用"analytics 已授权"用户的画像：t_user_habit.consent=1 且请求携带
 *       ll_analytics=1 Cookie（双信号同时成立）；任一不成立即回退非个性化（热度+新品）；</li>
 *   <li>画像行由前端在 analytics 授权下上报产生，撤回时删除行 + ll_analytics Cookie 置空，
 *       下一次请求自动降级，无需额外代码路径；</li>
 *   <li>所有召回候选必须可售（status=1 AND review_status=1 AND deleted=0）。</li>
 * </ul>
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final RecommendStatMapper statMapper;
    private final com.lanlink.shopping.mapper.UserHabitMapper habitMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${lanlink.recommend.enabled:true}")
    private boolean enabled;

    public RecommendService(ProductMapper productMapper, CategoryMapper categoryMapper,
                            RecommendStatMapper statMapper,
                            com.lanlink.shopping.mapper.UserHabitMapper habitMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.statMapper = statMapper;
        this.habitMapper = habitMapper;
    }

    /** 推荐结果：items + 是否个性化（前端据此展示"热门商品/猜你喜欢"与提示文案） */
    public record Recommendation(boolean personalized, String reason, List<Product> items) {}

    /**
     * 首页推荐。
     *
     * @param userId           登录用户 id（可空）
     * @param analyticsCookie  请求中的 ll_analytics Cookie 值（"1"=已授权）
     * @param limit            返回条数
     */
    public Recommendation home(Long userId, String analyticsCookie, int limit) {
        if (!enabled) return fallback("disabled", limit);
        Map<String, Object> profile = loadProfile(userId, analyticsCookie);
        if (profile == null) return fallback("no_consent_or_profile", limit);

        try {
            Map<Long, Double> scores = new LinkedHashMap<>();
            // 召回A content：画像类目 → 类目在售商品，权重 = 类目次数占比
            Set<Long> prefCatIds = resolveCategoryIds(profile);
            Map<Long, Double> catWeight = categoryWeights(profile, prefCatIds);
            int contentN = 0;
            if (!prefCatIds.isEmpty()) {
                List<Product> byCat = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .in(Product::getCatId, prefCatIds)
                        .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                        .last("LIMIT " + (limit * 4)));
                for (Product p : byCat) {
                    double w = catWeight.getOrDefault(p.getCatId(), 0.2);
                    scores.merge(p.getProdId(), 0.45 * w, Double::sum);
                    contentN++;
                }
            }
            // 召回B cf：最近浏览商品的共现 TopN（分数按共现排名位置衰减）
            List<Long> recent = recentProductIds(profile);
            int cfN = 0;
            for (Long pid : recent) {
                List<Long> sims = statMapper.similarIds(pid, limit * 2);
                for (int i = 0; i < sims.size(); i++) {
                    double score = Math.max(0.1, 0.6 - i * 0.05);
                    scores.merge(sims.get(i), 0.35 * score, Double::sum);
                    cfN++;
                }
            }
            // 召回C hot：热度榜作为基础分兜底
            for (Long hotId : statMapper.hotTopIds(limit * 3)) {
                scores.merge(hotId, 0.20, Double::sum);
            }
            List<Long> exclude = excludeIds(userId, recent);
            List<Product> items = materialize(scores, exclude, limit);
            if (items.size() < Math.min(limit, 4)) {
                // 召回不足：热度/新品补齐（标记仍为个性化，因为是融合结果）
                Set<Long> have = items.stream().map(Product::getProdId).collect(Collectors.toSet());
                for (Product p : fallbackProducts(limit - items.size())) {
                    if (!have.contains(p.getProdId())) items.add(p);
                }
            }
            if (items.isEmpty()) return fallback("empty", limit);
            log.debug("[recommend] personalized home: content={} cf={} exclude={} final={}",
                    contentN, cfN, exclude.size(), items.size());
            return new Recommendation(true, "habit_profile", items);
        } catch (Exception e) {
            log.warn("[recommend] personalized home failed, fallback hot: {}", e.getMessage());
            return fallback("error", limit);
        }
    }

    /** 看了又看：共现 TopN；共现不足回退同类目热度 */
    public List<Product> similar(Long prodId, int limit) {
        List<Long> ids = statMapper.similarIds(prodId, limit);
        List<Product> items = byIdsOrdered(ids);
        if (items.size() < Math.min(limit, 3)) {
            Product self = productMapper.selectById(prodId);
            Set<Long> have = items.stream().map(Product::getProdId).collect(Collectors.toSet());
            have.add(prodId);
            if (self != null && self.getCatId() != null) {
                for (Product p : productMapper.selectList(Wrappers.<Product>lambdaQuery()
                        .eq(Product::getCatId, self.getCatId())
                        .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                        .ne(Product::getProdId, prodId)
                        .orderByDesc(Product::getSales)
                        .last("LIMIT " + limit))) {
                    if (items.size() >= limit) break;
                    if (!have.contains(p.getProdId())) { items.add(p); have.add(p.getProdId()); }
                }
            }
            for (Product p : fallbackProducts(limit - items.size())) {
                if (items.size() >= limit) break;
                if (!have.contains(p.getProdId())) { items.add(p); have.add(p.getProdId()); }
            }
        }
        return items;
    }

    // ------------------------------ 内部 ------------------------------

    /** 画像加载：consent=1 且请求 Cookie ll_analytics=1 双信号；无画像/未授权返回 null */
    private Map<String, Object> loadProfile(Long userId, String analyticsCookie) {
        if (userId == null) return null;
        if (!"1".equals(analyticsCookie)) return null;
        String payload = habitMapper.findPayloadConsented(userId);
        if (payload == null) return null;
        try {
            JsonNode root = objectMapper.readTree(payload);
            Map<String, Object> m = new HashMap<>();
            m.put("categories", root.path("categories"));
            m.put("searchTerms", root.path("searchTerms"));
            m.put("recentProducts", root.path("recentProducts"));
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 画像类目名 → cat_id 集（类目名精确匹配，含父级名称的二级类目按名命中） */
    private Set<Long> resolveCategoryIds(Map<String, Object> profile) {
        Set<Long> ids = new HashSet<>();
        JsonNode cats = (JsonNode) profile.get("categories");
        if (cats == null) return ids;
        Iterator<String> it = cats.fieldNames();
        List<String> names = new ArrayList<>();
        while (it.hasNext() && names.size() < 8) names.add(it.next());
        if (names.isEmpty()) return ids;
        List<Category> rows = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .in(Category::getName, names));
        rows.forEach(c -> ids.add(c.getCatId()));
        return ids;
    }

    /** 类目权重 = 该类目次数 / 类目总次数（归一后用于 content 分） */
    private Map<Long, Double> categoryWeights(Map<String, Object> profile, Set<Long> catIds) {
        Map<Long, Double> w = new HashMap<>();
        JsonNode cats = (JsonNode) profile.get("categories");
        if (cats == null || catIds.isEmpty()) return w;
        Map<String, Long> catIdByName = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .in(Category::getName, namesOf(cats))).stream()
                .collect(Collectors.toMap(Category::getName, Category::getCatId, (a, b) -> a));
        Map<Long, Long> counts = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = cats.fields();
        while (it.hasNext()) {
            var en = it.next();
            Long catId = catIdByName.get(en.getKey());
            if (catId == null) continue;
            counts.merge(catId, en.getValue().asLong(0), Long::sum);
        }
        final long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total > 0) counts.forEach((k, v) -> w.put(k, v / (double) total));
        return w;
    }

    private List<String> namesOf(JsonNode cats) {
        List<String> names = new ArrayList<>();
        cats.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private List<Long> recentProductIds(Map<String, Object> profile) {
        JsonNode arr = (JsonNode) profile.get("recentProducts");
        List<Long> ids = new ArrayList<>();
        if (arr != null) arr.forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    /** 共现分查询（similarIds 只回 id，分数用固定档位近似：Top1=0.6 递减） — 已并入召回B循环 */

    /** 排除集：最近浏览本身 + 近 7 天已购 */
    private List<Long> excludeIds(Long userId, List<Long> recent) {
        List<Long> exclude = new ArrayList<>(recent);
        if (userId != null) {
            try {
                exclude.addAll(productMapper.selectObjs(Wrappers.<Product>query().select("DISTINCT oi.prod_id")
                        .apply("EXISTS (SELECT 1 FROM t_order_item oi JOIN t_order o ON oi.order_no=o.order_no " +
                                "WHERE oi.prod_id=t_product.prod_id AND o.user_id={0} AND o.pay_status=1 " +
                                "AND o.pay_time>=DATE_SUB(NOW(),INTERVAL 7 DAY))", userId))
                        .stream().map(x -> Long.valueOf(String.valueOf(x))).toList());
            } catch (Exception e) {
                log.debug("[recommend] exclude purchased failed: {}", e.getMessage());
            }
        }
        return exclude;
    }

    /** 按融合分降序物化商品实体 */
    private List<Product> materialize(Map<Long, Double> scores, List<Long> exclude, int limit) {
        List<Long> ordered = scores.entrySet().stream()
                .filter(e -> !exclude.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
        return byIdsOrdered(ordered);
    }

    private List<Product> byIdsOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        List<Product> rows = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .in(Product::getProdId, ids)
                .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0));
        Map<Long, Product> byId = rows.stream().collect(Collectors.toMap(Product::getProdId, p -> p, (a, b) -> a));
        return ids.stream().map(byId::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /** 非个性化兜底：热度榜优先，空则销量 */
    private Recommendation fallback(String reason, int limit) {
        List<Product> items = byIdsOrdered(statMapper.hotTopIds(limit));
        if (items.size() < limit) {
            Set<Long> have = items.stream().map(Product::getProdId).collect(Collectors.toSet());
            for (Product p : fallbackProducts(limit - items.size())) {
                if (items.size() >= limit) break;
                if (!have.contains(p.getProdId())) { items.add(p); have.add(p.getProdId()); }
            }
        }
        return new Recommendation(false, reason, items);
    }

    /** 销量兜底候选（热度榜空窗时保证推荐位永不空白） */
    private List<Product> fallbackProducts(int n) {
        if (n <= 0) return new ArrayList<>();
        return productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                .orderByDesc(Product::getSales)
                .last("LIMIT " + n));
    }
}

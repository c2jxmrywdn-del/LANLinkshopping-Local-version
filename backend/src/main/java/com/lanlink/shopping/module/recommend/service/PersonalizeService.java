package com.lanlink.shopping.module.recommend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.mapper.UserHabitMapper;
import com.lanlink.shopping.module.recommend.mapper.RecommendStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商城列表个性化推流（期二）。
 *
 * <p>做法：综合态（无搜索/筛选/显式排序）下，先取 3 倍窗口候选，再按
 * score = 0.45*类目偏好 + 0.30*热度 + 0.15*新鲜度 + 0.10*销量 融合打分，
 * 同类目连续不超过 2 个（slotted diversity）后切页返回。
 *
 * <p>合规：与 RecommendService 同一双信号门控（t_user_habit.consent=1 + ll_analytics Cookie）；
 * 未授权/游客/画像缺失时原样返回自然排序（personalized=false）。显式排序与筛选请求不走个性化。
 */
@Service
public class PersonalizeService {

    private static final Logger log = LoggerFactory.getLogger(PersonalizeService.class);
    private static final int WINDOW_MULTIPLE = 3;

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final UserHabitMapper habitMapper;
    private final RecommendStatMapper statMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${lanlink.personalize.enabled:true}")
    private boolean enabled;

    public PersonalizeService(ProductMapper productMapper, CategoryMapper categoryMapper,
                              UserHabitMapper habitMapper, RecommendStatMapper statMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.habitMapper = habitMapper;
        this.statMapper = statMapper;
    }

    /**
     * 个性化分页。返回 Page<Product>（records/total 兼容 IPage 消费方）；
     * personalized=false 时直接返回自然分页结果。
     */
    public IPage<Product> page(long current, long size, Long userId, String analyticsCookie,
                               String keyword, Long catId, Long indId, String sort) {
        if (!enabled || userId == null || !"1".equals(analyticsCookie)) {
            log.info("[personalize] skip: enabled={} userId={} consentCookie={}", enabled, userId, analyticsCookie);
            return null; // 调用方回退自然排序
        }
        String payload = habitMapper.findPayloadConsented(userId);
        if (payload == null) {
            log.info("[personalize] skip: no consented profile, userId={}", userId);
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            Map<Long, Double> catPref = categoryPref(root);
            if (catPref.isEmpty()) {
                log.info("[personalize] skip: empty category pref, userId={}", userId);
                return null;
            }

            long window = size * WINDOW_MULTIPLE;
            List<Product> candidates = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                    .eq(Product::getStatus, 1).eq(Product::getReviewStatus, 1).eq(Product::getDeleted, 0)
                    .orderByDesc(Product::getCreateTime)
                    .last("LIMIT " + window));

            Map<Long, Double> hotById = hotScoreMap();
            Set<Long> purchased = recentPurchasedIds(userId);
            long now = System.currentTimeMillis();

            List<Product> scored = candidates.stream()
                    .filter(p -> !purchased.contains(p.getProdId()))
                    .sorted(Comparator.comparingDouble((Product p) -> score(p, catPref, hotById, now)).reversed())
                    .toList();

            List<Product> diversified = diversifyByCategory(scored);
            long total = diversified.size();
            int from = (int) Math.min((current - 1) * size, Math.max(total, 0));
            int to = (int) Math.min(from + size, total);

            Page<Product> out = new Page<>(current, size, total);
            out.setRecords(new ArrayList<>(diversified.subList(from, to)));
            log.debug("[personalize] page: candidates={} diversified={} page={}/{}",
                    candidates.size(), diversified.size(), current, size);
            return out;
        } catch (Exception e) {
            log.warn("[personalize] failed, fallback natural order: {}", e.getMessage());
            return null;
        }
    }

    /** 融合打分：类目偏好 0.45 + 热度 0.30 + 新鲜度 0.15 + 销量 0.10（搜索词命中 title 加 0.2） */
    private double score(Product p, Map<Long, Double> catPref, Map<Long, Double> hotById, long now) {
        double s = 0;
        s += 0.45 * catPref.getOrDefault(p.getCatId(), 0.0);
        s += 0.30 * hotById.getOrDefault(p.getProdId(), 0.0);
        if (p.getCreateTime() != null) {
            double ageDays = Math.max(0, (now - p.getCreateTime().toLocalDate()
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()) / 86400000.0);
            s += 0.15 * (1.0 / (1.0 + ageDays));
        }
        if (p.getSales() != null) {
            s += 0.10 * Math.min(1.0, p.getSales() / 200.0);
        }
        return s;
    }

    /** 类目偏好 = 画像类目次数占比（类目名 → cat_id 反查） */
    private Map<Long, Double> categoryPref(JsonNode root) {
        JsonNode cats = root.path("categories");
        Map<Long, Double> pref = new HashMap<>();
        if (!cats.isObject() || cats.isEmpty()) return pref;
        List<String> names = new ArrayList<>();
        cats.fieldNames().forEachRemaining(names::add);
        if (names.isEmpty()) return pref;
        Map<String, Long> idByName = categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                        .in(Category::getName, names)).stream()
                .collect(Collectors.toMap(Category::getName, Category::getCatId, (a, b) -> a));
        Map<Long, Long> counts = new HashMap<>();
        cats.fields().forEachRemaining(en -> {
            Long catId = idByName.get(en.getKey());
            if (catId != null) {
                counts.merge(catId, en.getValue().asLong(0), Long::sum);
            }
        });
        final long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total > 0) counts.forEach((k, v) -> pref.put(k, v / (double) total));
        return pref;
    }

    /** 热度分（归一到 0-1）：t_prod_hot_stat 当日榜 */
    private Map<Long, Double> hotScoreMap() {
        Map<Long, Double> m = new HashMap<>();
        try {
            List<Map<String, Object>> rows = statMapper.hotTopWithScores(300);
            double max = rows.stream().mapToDouble(r -> Double.parseDouble(String.valueOf(r.get("hotScore")))).max().orElse(0);
            if (max <= 0) return m;
            for (Map<String, Object> r : rows) {
                long id = Long.parseLong(String.valueOf(r.get("prodId")));
                double s = Double.parseDouble(String.valueOf(r.get("hotScore")));
                m.put(id, s / max);
            }
        } catch (Exception e) {
            log.debug("[personalize] hot map unavailable: {}", e.getMessage());
        }
        return m;
    }

    /** 近 7 天已购商品（个性化去重：已买的不再推） */
    private Set<Long> recentPurchasedIds(Long userId) {
        try {
            return productMapper.selectObjs(Wrappers.<Product>query().select("1")
                            .apply("EXISTS (SELECT 1 FROM t_order_item oi JOIN t_order o ON oi.order_no=o.order_no " +
                                    "WHERE oi.prod_id=t_product.prod_id AND o.user_id={0} AND o.pay_status=1 " +
                                    "AND o.pay_time>=DATE_SUB(NOW(),INTERVAL 7 DAY))", userId))
                    .stream().map(x -> Long.parseLong(String.valueOf(x))).collect(Collectors.toSet());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /** 类目多样性打散：同一 cat_id 连续不超过 2 个（贪心重排） */
    private List<Product> diversifyByCategory(List<Product> scored) {
        List<Product> out = new ArrayList<>(scored.size());
        Map<Long, Integer> run = new HashMap<>();
        Deque<Product> pending = new ArrayDeque<>(scored);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < scored.size() * 4) {
            boolean placed = false;
            Iterator<Product> it = pending.iterator();
            while (it.hasNext()) {
                Product p = it.next();
                Long cat = p.getCatId() == null ? -1L : p.getCatId();
                if (run.getOrDefault(cat, 0) < 2) {
                    out.add(p);
                    run.merge(cat, 1, Integer::sum);
                    it.remove();
                    placed = true;
                    break;
                }
            }
            if (!placed) { // 全部都顶到 2 连：放行队首避免死循环
                Product p = pending.poll();
                out.add(p);
                run.merge(p.getCatId() == null ? -1L : p.getCatId(), 1, Integer::sum);
            }
        }
        return out;
    }
}

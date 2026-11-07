package com.lanlink.shopping.module.recommend.service;

import com.lanlink.shopping.entity.Product;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 首页推荐 MMR 多样性重排（纯静态函数，无状态、无外部依赖，O(n²) 对千级候选毫秒级）。
 *
 * <p>算法（重排层）：MMR = λ·rel(d) − (1−λ)·max<sub>d'∈已选</sub> sim(d,d')
 * <ul>
 *   <li>rel(d) = 融合分 / 池内最大融合分（池内归一化，避免跨请求量纲漂移）；</li>
 *   <li>sim(d,d') = 同类目 0.6 + 同品牌 0.4（同时成立为 1.0，均不同为 0）；</li>
 *   <li>λ 权衡系数：1=纯相关性（退化为按融合分直排），默认 0.7，经
 *       {@code lanlink.recommend.mmr.lambda} 可配（越界/NaN 时收敛回 [0,1]）。</li>
 * </ul>
 *
 * <p>硬规则（尽力而为松弛语义）：
 * <ul>
 *   <li>类目连续 ≤ 2：不允许连续 3 项同类目；</li>
 *   <li>同品牌连续 ≤ 1：不允许相邻两项同品牌；</li>
 *   <li>每步选优：MMR 最优候选违例且剩余池中存在合规候选时，跳过违例者取次优；
 *       剩余池中已无合规候选时仍收录（松弛收录）——列表永不短于 limit，
 *       池 ≤ limit 时不丢候选仅重排。</li>
 * </ul>
 *
 * <p>catId/brand 为 null 的字段不参与比较（视为不相似、不触发硬规则），防御脏数据。
 */
public final class MmrReranker {

    /** 同类目相似度分量 */
    static final double SIM_SAME_CAT = 0.6;
    /** 同品牌相似度分量 */
    static final double SIM_SAME_BRAND = 0.4;

    private MmrReranker() {}

    /**
     * MMR 多样性重排：从候选池贪心选取至多 limit 条（池 ≤ limit 时全量保留仅重排）。
     *
     * @param pool          候选池（建议按融合分降序传入，用于稳定同分次序）
     * @param fusionScores  prodId → 融合分（池内归一化得到 rel；缺分按 0 处理）
     * @param limit         输出条数上限
     * @param lambda        MMR 权衡系数（越界/NaN 自动收敛到 [0,1]）
     */
    public static List<Product> rerank(List<Product> pool, Map<Long, Double> fusionScores, int limit, double lambda) {
        List<Product> result = new ArrayList<>();
        if (pool == null || pool.isEmpty() || limit <= 0) return result;
        final double lam = Double.isNaN(lambda) ? 0.7 : Math.max(0.0, Math.min(1.0, lambda));
        final Map<Long, Double> scores = fusionScores == null ? Map.of() : fusionScores;

        double max = 0.0;
        for (Product p : pool) {
            if (p == null || p.getProdId() == null) continue;
            double s = scoreOf(scores, p.getProdId());
            if (s > max) max = s;
        }
        final double maxScore = max;

        List<Product> remaining = new ArrayList<>();
        for (Product p : pool) {
            if (p != null && p.getProdId() != null) remaining.add(p);
        }

        while (!remaining.isEmpty() && result.size() < limit) {
            int bestAny = -1;
            double bestAnyMmr = Double.NEGATIVE_INFINITY;
            int bestCompliant = -1;
            double bestCompliantMmr = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                Product d = remaining.get(i);
                double rel = maxScore > 0 ? scoreOf(scores, d.getProdId()) / maxScore : 1.0;
                double maxSim = 0.0;
                for (Product s : result) maxSim = Math.max(maxSim, similarity(d, s));
                double mmr = lam * rel - (1 - lam) * maxSim;
                // 严格大于：同分保留池序（融合分降序）靠前者，保证确定性
                if (mmr > bestAnyMmr) { bestAnyMmr = mmr; bestAny = i; }
                if (!violatesHardRules(result, d) && mmr > bestCompliantMmr) { bestCompliantMmr = mmr; bestCompliant = i; }
            }
            // 松弛收录：剩余池存在合规候选则跳过违例者取次优；否则仍收录违例最优
            int chosen = bestCompliant >= 0 ? bestCompliant : bestAny;
            result.add(remaining.remove(chosen));
        }
        return result;
    }

    /**
     * 补齐候选追加（fallback 热榜/新品补齐用）：逐个尝试追加到 target 尾部，
     * 违例者暂缓、优先收录后续合规候选；合规候选耗尽仍不足 limit 时按序松弛收录，
     * 保证列表长度。target 内已存在的 prodId 跳过（去重）。
     */
    public static void appendRelaxed(List<Product> target, List<Product> candidates, int limit) {
        if (target == null || candidates == null || candidates.isEmpty() || target.size() >= limit) return;
        Set<Long> have = target.stream().map(Product::getProdId).collect(Collectors.toSet());
        List<Product> deferred = new ArrayList<>();
        for (Product p : candidates) {
            if (target.size() >= limit) return;
            if (p == null || p.getProdId() == null || !have.add(p.getProdId())) continue;
            if (violatesHardRules(target, p)) deferred.add(p);
            else target.add(p);
        }
        for (Product p : deferred) {
            if (target.size() >= limit) return;
            target.add(p);
        }
    }

    /**
     * 候选 d 追加到已选序列尾部是否违反硬规则：
     * 与前一项同品牌（品牌连续 ≤1 违例），或与前两项构成同类目三连（类目连续 ≤2 违例）。
     */
    static boolean violatesHardRules(List<Product> selected, Product d) {
        int n = selected.size();
        if (n >= 1 && d.getBrand() != null && d.getBrand().equals(selected.get(n - 1).getBrand())) return true;
        if (n >= 2 && d.getCatId() != null
                && d.getCatId().equals(selected.get(n - 1).getCatId())
                && d.getCatId().equals(selected.get(n - 2).getCatId())) return true;
        return false;
    }

    /** sim(d, d') = 同类目 0.6 + 同品牌 0.4（null 字段不参与，视为不同） */
    static double similarity(Product a, Product b) {
        double s = 0.0;
        if (a.getCatId() != null && a.getCatId().equals(b.getCatId())) s += SIM_SAME_CAT;
        if (a.getBrand() != null && a.getBrand().equals(b.getBrand())) s += SIM_SAME_BRAND;
        return s;
    }

    private static double scoreOf(Map<Long, Double> scores, Long prodId) {
        Double s = scores.get(prodId);
        return (s == null || s.isNaN() || s.isInfinite()) ? 0.0 : s;
    }
}

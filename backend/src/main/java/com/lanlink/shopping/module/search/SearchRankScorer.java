package com.lanlink.shopping.module.search;

import com.lanlink.shopping.entity.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索多目标加权融合打分（业务目标层，叠加在双路 RRF 之上）。
 *
 * <p>报告「相关性与多目标融合打分」的实现：Score = wRel·Rel + wPer·Per + wSales·Sales + wStock·Stock
 * （默认 0.55/0.15/0.20/0.10，@Value 可配）。各维信号均归一到 [0,1]：
 * <ul>
 *   <li>Rel：RRF 分按池内最大值归一——保持相关性主序，权重占主导；</li>
 *   <li>Per：画像类目偏好（该类目次数 / 画像类目总次数）。复用 RecommendService.loadProfile 的
 *       合规双信号门控：登录 userId 与 ll_analytics=1 Cookie 任一不成立，Per 恒为 0；</li>
 *   <li>Sales：销量按池内最大值归一封顶 1（Popularity 与 CVR 代理同源于 t_product.sales，
 *       合并为一个销量维度）；</li>
 *   <li>Stock：现货分，stock&gt;0 → 1.0，否则 0.3——降权不硬剔除，保留 B2B 询货路径
 *       （报告原 Service 维度为店铺评分，t_merchant 无评分列，以库存现货代理）。</li>
 * </ul>
 *
 * <p>开关 lanlink.search.fusion.enabled=false 时 {@link #rank} 逐位复现纯 RRF 序（一键回退）。
 * 两条路径统一使用「分数降序 + prod_id 升序」决胜键，保证同一池任意两次调用输出逐位一致。
 */
@Component
public class SearchRankScorer {

    private final boolean fusionEnabled;
    private final double wRel;
    private final double wPer;
    private final double wSales;
    private final double wStock;

    public SearchRankScorer(
            @Value("${lanlink.search.fusion.enabled:true}") boolean fusionEnabled,
            @Value("${lanlink.search.fusion.w-rel:0.55}") double wRel,
            @Value("${lanlink.search.fusion.w-per:0.15}") double wPer,
            @Value("${lanlink.search.fusion.w-sales:0.20}") double wSales,
            @Value("${lanlink.search.fusion.w-stock:0.10}") double wStock) {
        this.fusionEnabled = fusionEnabled;
        this.wRel = wRel;
        this.wPer = wPer;
        this.wSales = wSales;
        this.wStock = wStock;
    }

    public boolean fusionEnabled() {
        return fusionEnabled;
    }

    /**
     * 个性化合规门控（与 RecommendService.loadProfile 同一双信号）：
     * 登录用户 + ll_analytics=1 Cookie 同时成立才允许 Per 分量非零。
     */
    public static boolean perEligible(Long userId, String analyticsCookie) {
        return userId != null && "1".equals(analyticsCookie);
    }

    /** 现货分：有货 1.0；无货/未知 0.3（降权不剔除） */
    public static double stockScore(Integer stock) {
        return stock != null && stock > 0 ? 1.0 : 0.3;
    }

    /** 销量分：按池内最大销量归一，封顶 1（池最大销量 ≤0 或销量缺失记 0） */
    public static double salesScore(Integer sales, double poolMaxSales) {
        if (sales == null || sales <= 0 || poolMaxSales <= 0) return 0.0;
        return Math.min(1.0, sales / poolMaxSales);
    }

    /** 相关性分：RRF 分按池内最大值归一（池最大值 ≤0 记 0，防除零） */
    public static double relScore(double rrf, double poolMaxRrf) {
        if (poolMaxRrf <= 0) return 0.0;
        return rrf / poolMaxRrf;
    }

    /** 四维加权融合总分 */
    public double score(double rel, double per, double sales, double stock) {
        return wRel * rel + wPer * per + wSales * sales + wStock * stock;
    }

    /**
     * 排序入口。
     *
     * @param rrf             RRF 融合分（prodId → score，即纯相关性序的依据）
     * @param pool            池内商品实体（sales/stock/catId 信号源；实体缺失的商品仅按 Rel 计分）
     * @param catPref         画像类目偏好（catId → 0-1 占比；未授权/无画像为空表）
     * @param userId          登录用户 id（可空）
     * @param analyticsCookie ll_analytics Cookie 值（"1"=已授权）
     * @return 排序后的 prodId 列表（分数降序，prod_id 升序决胜）
     */
    public List<Long> rank(Map<Long, Double> rrf, Map<Long, Product> pool,
                           Map<Long, Double> catPref, Long userId, String analyticsCookie) {
        // 回退：开关关闭逐位复现纯 RRF 序（与历史直排同一语义，仅补 prod_id 决胜键保证确定性）
        if (!fusionEnabled) {
            return rrf.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        boolean perOn = perEligible(userId, analyticsCookie);
        double maxRrf = rrf.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double maxSales = pool.values().stream()
                .mapToDouble(p -> p.getSales() == null ? 0 : p.getSales()).max().orElse(0);

        Map<Long, Double> fused = new HashMap<>(rrf.size());
        for (Map.Entry<Long, Double> e : rrf.entrySet()) {
            double rel = relScore(e.getValue(), maxRrf);
            Product p = pool.get(e.getKey());
            if (p == null) {
                fused.put(e.getKey(), wRel * rel);
                continue;
            }
            double per = perOn && catPref != null
                    ? catPref.getOrDefault(p.getCatId(), 0.0) : 0.0;
            fused.put(e.getKey(), score(rel, per,
                    salesScore(p.getSales(), maxSales), stockScore(p.getStock())));
        }
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }
}

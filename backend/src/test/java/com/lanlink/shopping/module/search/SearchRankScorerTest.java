package com.lanlink.shopping.module.search;

import com.lanlink.shopping.entity.Product;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索多目标加权融合打分单测（纯 JUnit，不依赖 Spring/DB）。
 * 覆盖：四维加权和、Per 双信号门控、stock&lt;=0 现货降权、fusion 开关关闭逐位复现纯 RRF 序。
 */
class SearchRankScorerTest {

    private static final double W_REL = 0.55;
    private static final double W_PER = 0.15;
    private static final double W_SALES = 0.20;
    private static final double W_STOCK = 0.10;

    private static SearchRankScorer enabled() {
        return new SearchRankScorer(true, W_REL, W_PER, W_SALES, W_STOCK);
    }

    private static SearchRankScorer disabled() {
        return new SearchRankScorer(false, W_REL, W_PER, W_SALES, W_STOCK);
    }

    private static Product product(long prodId, Long catId, Integer sales, Integer stock) {
        Product p = new Product();
        p.setProdId(prodId);
        p.setCatId(catId);
        p.setSales(sales);
        p.setStock(stock);
        return p;
    }

    /** 四维加权和：score = 0.55·Rel + 0.15·Per + 0.20·Sales + 0.10·Stock */
    @Test
    void fusedScoreIsWeightedSumOfFourObjectives() {
        SearchRankScorer s = enabled();
        assertEquals(W_REL + W_PER + W_SALES + W_STOCK, s.score(1, 1, 1, 1), 1e-9);
        assertEquals(W_REL * 0.5 + W_PER * 0.2 + W_SALES * 0.4 + W_STOCK * 0.3,
                s.score(0.5, 0.2, 0.4, 0.3), 1e-9);
        // 每个分量独立按权重生效
        assertEquals(W_REL * 0.1, s.score(1.0, 0, 0, 0) - s.score(0.9, 0, 0, 0), 1e-9);
        assertEquals(W_PER * 0.1, s.score(0, 1.0, 0, 0) - s.score(0, 0.9, 0, 0), 1e-9);
        assertEquals(W_SALES * 0.1, s.score(0, 0, 1.0, 0) - s.score(0, 0, 0.9, 0), 1e-9);
        assertEquals(W_STOCK * 0.1, s.score(0, 0, 0, 1.0) - s.score(0, 0, 0, 0.9), 1e-9);
    }

    /** Per 分量仅在 登录 userId + ll_analytics=1 Cookie 双信号同时成立时非零 */
    @Test
    void perComponentOnlyActiveUnderDualConsentSignals() {
        assertTrue(SearchRankScorer.perEligible(7L, "1"));
        assertFalse(SearchRankScorer.perEligible(null, "1"));
        assertFalse(SearchRankScorer.perEligible(7L, null));
        assertFalse(SearchRankScorer.perEligible(7L, "0"));

        SearchRankScorer s = enabled();
        // 池内 rel/sales/stock 完全相同：101 非画像类目、102 命中画像类目（权重 1.0）
        Map<Long, Double> rrf = Map.of(101L, 1.0, 102L, 1.0);
        Map<Long, Product> pool = new HashMap<>();
        pool.put(101L, product(101L, 999L, 10, 5));
        pool.put(102L, product(102L, 200L, 10, 5));
        Map<Long, Double> catPref = Map.of(200L, 1.0);

        // 双信号成立：Per 生效，画像类目商品前移
        assertEquals(List.of(102L, 101L), s.rank(rrf, pool, catPref, 7L, "1"));
        // 任一信号缺失：Per=0，全部同分，prod_id 升序决胜
        assertEquals(List.of(101L, 102L), s.rank(rrf, pool, catPref, null, "1"));
        assertEquals(List.of(101L, 102L), s.rank(rrf, pool, catPref, 7L, "0"));
        assertEquals(List.of(101L, 102L), s.rank(rrf, pool, catPref, null, null));

        // 分值层面：Per 贡献恰为 wPer × 类目偏好
        double base = s.score(0.8, 0, 0.5, 1.0);
        assertEquals(W_PER * 0.6, s.score(0.8, 0.6, 0.5, 1.0) - base, 1e-9);
    }

    /** stock<=0 商品现货分降权（0.3 档），不硬剔除 */
    @Test
    void outOfStockProductGetsDownweightedButNotDropped() {
        assertEquals(1.0, SearchRankScorer.stockScore(5), 1e-9);
        assertEquals(0.3, SearchRankScorer.stockScore(0), 1e-9);
        assertEquals(0.3, SearchRankScorer.stockScore(-3), 1e-9);
        assertEquals(0.3, SearchRankScorer.stockScore(null), 1e-9);

        SearchRankScorer s = enabled();
        double inStock = s.score(0.8, 0, 0.5, SearchRankScorer.stockScore(10));
        double outOfStock = s.score(0.8, 0, 0.5, SearchRankScorer.stockScore(0));
        assertTrue(outOfStock < inStock);
        assertEquals(W_STOCK * (1.0 - 0.3), inStock - outOfStock, 1e-9);

        // 排序层面：相关性略低的现货商品凭 0.07 现货分差反超无货商品
        Map<Long, Double> rrf = new LinkedHashMap<>();
        rrf.put(103L, 1.0);
        rrf.put(102L, 0.98);
        Map<Long, Product> pool = new HashMap<>();
        pool.put(103L, product(103L, 1L, 100, 0));   // 无货，RRF 更高
        pool.put(102L, product(102L, 1L, 100, 5));   // 现货
        List<Long> fusedOrder = s.rank(rrf, pool, Map.of(), null, null);
        assertEquals(List.of(102L, 103L), fusedOrder);
        // 无货商品仍留在池中（不硬剔除，保留 B2B 询货路径）
        assertTrue(fusedOrder.contains(103L));
    }

    /** fusion 开关关闭时输出序与纯 RRF 序逐位一致；开启后同输入序被业务目标改变 */
    @Test
    void fusionOffReproducesPureRrfOrderBitwise() {
        // 构造融合本会改变序的池：102 销量满档 + 画像类目 + 现货，但 RRF 低于 101
        Map<Long, Double> rrf = Map.of(101L, 1.0, 102L, 0.9, 103L, 0.5);
        Map<Long, Product> pool = new HashMap<>();
        pool.put(101L, product(101L, 999L, 0, 1));
        pool.put(102L, product(102L, 200L, 500, 9));
        pool.put(103L, product(103L, 200L, 50, 9));
        Map<Long, Double> catPref = Map.of(200L, 1.0);

        List<Long> expectedPure = rrf.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(List.of(101L, 102L, 103L), expectedPure);

        assertEquals(expectedPure, disabled().rank(rrf, pool, catPref, 7L, "1"));
        assertEquals(expectedPure, disabled().rank(rrf, pool, catPref, null, null));

        // 同输入开启融合：画像类目+销量+现货把 102 顶到第一（证明开关确实在门控）
        assertEquals(List.of(102L, 101L, 103L), enabled().rank(rrf, pool, catPref, 7L, "1"));
        assertNotEquals(expectedPure, enabled().rank(rrf, pool, catPref, 7L, "1"));
    }

    /** 归一辅助函数边界：除零防护与封顶 */
    @Test
    void normalizationHelpersHandleBoundaries() {
        assertEquals(1.0, SearchRankScorer.relScore(5, 5), 1e-9);
        assertEquals(0.5, SearchRankScorer.relScore(2.5, 5), 1e-9);
        assertEquals(0.0, SearchRankScorer.relScore(1.0, 0), 1e-9);
        assertEquals(1.0, SearchRankScorer.salesScore(300, 200), 1e-9);
        assertEquals(0.5, SearchRankScorer.salesScore(100, 200), 1e-9);
        assertEquals(0.0, SearchRankScorer.salesScore(null, 200), 1e-9);
        assertEquals(0.0, SearchRankScorer.salesScore(50, 0), 1e-9);
        assertEquals(0.0, SearchRankScorer.salesScore(-1, 200), 1e-9);
    }

    /** 同分决胜键 prod_id 升序：关闭与开启（无信号差异）两路径序一致且确定 */
    @Test
    void tiesBreakByProductIdDeterministically() {
        Map<Long, Double> rrf = new HashMap<>();
        rrf.put(9L, 1.0);
        rrf.put(4L, 1.0);
        rrf.put(7L, 0.5);
        rrf.put(2L, 0.5);
        List<Long> expected = List.of(4L, 9L, 2L, 7L);
        assertEquals(expected, disabled().rank(rrf, Map.of(), Map.of(), null, null));
        // 开启但池为空：仅 Rel 维度生效，等比缩放不改变序
        assertEquals(expected, enabled().rank(rrf, Map.of(), Map.of(), null, null));
    }
}

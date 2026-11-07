package com.lanlink.shopping.module.recommend.service;

import com.lanlink.shopping.entity.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MMR 多样性重排单元测试（纯 JUnit）：
 * <ul>
 *   <li>a) 违例可避免时硬规则生效；不可避免时松弛收录且列表长度=limit；</li>
 *   <li>b) λ=1 退化：数据不含同品牌/同类目碰撞时，重排退化为按融合分直排；</li>
 *   <li>c) 池 ≤ limit：不丢候选仅重排；</li>
 *   <li>d) 受控商品集形态（跨≥3类目×≥4品牌、limit=8）：无同品牌相邻对、无连续3项同类目；</li>
 *   <li>e) fallback 补齐 appendRelaxed：违例暂缓/松弛收录/去重。</li>
 * </ul>
 */
class MmrRerankerTest {

    private static final double DEFAULT_LAMBDA = 0.7;

    private static Product p(long id, Long catId, String brand, double score) {
        Product p = new Product();
        p.setProdId(id);
        p.setCatId(catId);
        p.setBrand(brand);
        return p;
    }

    // ---- 通用断言 ----

    /** 无相邻同品牌对（brand=null 不参与比较） */
    private static void assertNoAdjacentSameBrand(List<Product> items) {
        for (int i = 1; i < items.size(); i++) {
            String prev = items.get(i - 1).getBrand();
            String cur = items.get(i).getBrand();
            assertFalse(cur != null && cur.equals(prev),
                    "同品牌相邻违例 @" + i + ": " + ids(items));
        }
    }

    /** 无连续 3 项同类目（catId=null 不参与比较） */
    private static void assertNoThreeRunSameCat(List<Product> items) {
        for (int i = 2; i < items.size(); i++) {
            Long a = items.get(i - 2).getCatId();
            Long b = items.get(i - 1).getCatId();
            Long c = items.get(i).getCatId();
            assertFalse(c != null && c.equals(a) && c.equals(b),
                    "同类目三连违例 @" + i + ": " + ids(items));
        }
    }

    private static List<Long> ids(List<Product> items) {
        List<Long> r = new ArrayList<>();
        for (Product p : items) r.add(p.getProdId());
        return r;
    }

    private static Set<Long> idSet(List<Product> items) {
        return new HashSet<>(ids(items));
    }

    // ================= a) 硬规则生效 / 松弛收录 =================

    @Test
    void a1_hardRulesKickInWhenAvoidable() {
        // 融合分直排 Top4 = [1,2,3,4]：1/2 相邻同品牌 A，且 1/2/3 同类目三连——违例可避免
        Product[] pool = {
                p(1, 10L, "A", 1.00), p(2, 10L, "A", 0.95), p(3, 10L, "A", 0.90),
                p(4, 20L, "B", 0.50), p(5, 20L, "C", 0.40), p(6, 30L, "D", 0.30)};
        Map<Long, Double> scores = new LinkedHashMap<>();
        scores.put(1L, 1.00); scores.put(2L, 0.95); scores.put(3L, 0.90);
        scores.put(4L, 0.50); scores.put(5L, 0.40); scores.put(6L, 0.30);

        List<Product> out = MmrReranker.rerank(Arrays.asList(pool), scores, 4, DEFAULT_LAMBDA);

        assertEquals(4, out.size(), "列表长度 = limit");
        assertEquals(1L, out.get(0).getProdId(), "最高分仍居首");
        assertNoAdjacentSameBrand(out);
        assertNoThreeRunSameCat(out);
        assertTrue(idSet(out).contains(6L), "合规候选 6 应顶替违例 3 入选: " + ids(out));
        assertFalse(idSet(out).contains(3L), "同类目三连成员 3 应被硬规则挤出 Top4: " + ids(out));
    }

    @Test
    void a2_relaxedInclusionWhenUnavoidable_keepsLengthEqualLimit() {
        // 同品牌不可避免：池内仅 2 项且同为品牌 A，任意排序必然相邻——仍须全量收录
        Map<Long, Double> brandScores = new LinkedHashMap<>();
        brandScores.put(11L, 1.0); brandScores.put(12L, 0.9);
        List<Product> brandPool = Arrays.asList(p(11, 1L, "A", 1.0), p(12, 2L, "A", 0.9));
        List<Product> outBrand = MmrReranker.rerank(brandPool, brandScores, 2, DEFAULT_LAMBDA);
        assertEquals(2, outBrand.size(), "松弛收录：列表长度 = limit");
        assertEquals(idSet(brandPool), idSet(outBrand), "池内候选不丢");

        // 同类目三连不可避免：3 项同类目、limit=3——仍须全量收录
        Map<Long, Double> catScores = new LinkedHashMap<>();
        catScores.put(21L, 1.0); catScores.put(22L, 0.9); catScores.put(23L, 0.8);
        List<Product> catPool = Arrays.asList(
                p(21, 5L, "B", 1.0), p(22, 5L, "C", 0.9), p(23, 5L, "D", 0.8));
        List<Product> outCat = MmrReranker.rerank(catPool, catScores, 3, DEFAULT_LAMBDA);
        assertEquals(3, outCat.size(), "松弛收录：列表长度 = limit");
        assertEquals(idSet(catPool), idSet(outCat), "池内候选不丢");
        assertNoAdjacentSameBrand(outCat);
    }

    // ================= b) λ=1 退化 =================

    @Test
    void b_lambdaOneDegeneratesToPureFusionOrder() {
        // 数据不含同品牌/同类目碰撞（类目与品牌两两互异）
        Product[] pool = {
                p(41, 11L, "A", 0.95), p(42, 12L, "B", 0.90), p(43, 13L, "C", 0.85),
                p(44, 14L, "D", 0.80), p(45, 15L, "E", 0.75), p(46, 16L, "F", 0.70)};
        Map<Long, Double> scores = new LinkedHashMap<>();
        scores.put(41L, 0.95); scores.put(42L, 0.90); scores.put(43L, 0.85);
        scores.put(44L, 0.80); scores.put(45L, 0.75); scores.put(46L, 0.70);

        List<Product> out = MmrReranker.rerank(Arrays.asList(pool), scores, 4, 1.0);
        assertEquals(Arrays.asList(41L, 42L, 43L, 44L), ids(out), "λ=1 时按融合分直排");

        // λ 越界自动收敛到 [0,1]：5 → 1，同样是纯相关性直排
        List<Product> outClamped = MmrReranker.rerank(Arrays.asList(pool), scores, 4, 5.0);
        assertEquals(Arrays.asList(41L, 42L, 43L, 44L), ids(outClamped), "λ>1 收敛后等价 λ=1");
    }

    // ================= c) 池 ≤ limit 不丢候选仅重排 =================

    @Test
    void c_poolNotLargerThanLimit_keepsAllCandidatesOnlyReorders() {
        Product[] pool = {
                p(51, 1L, "A", 1.0), p(52, 2L, "A", 0.9), p(53, 3L, "B", 0.8),
                p(54, 1L, "C", 0.7), p(55, 2L, "D", 0.6)};
        Map<Long, Double> scores = new LinkedHashMap<>();
        scores.put(51L, 1.0); scores.put(52L, 0.9); scores.put(53L, 0.8);
        scores.put(54L, 0.7); scores.put(55L, 0.6);

        List<Product> out = MmrReranker.rerank(Arrays.asList(pool), scores, 8, DEFAULT_LAMBDA);

        assertEquals(pool.length, out.size(), "池 ≤ limit：不丢候选");
        assertEquals(idSet(Arrays.asList(pool)), idSet(out), "候选集合不变，仅重排");
        assertNoAdjacentSameBrand(out);
        assertNoThreeRunSameCat(out);
    }

    // ================= d) 受控商品集形态（≥3类目×≥4品牌，limit=8） =================

    @Test
    void d_controlledShape_noAdjacentSameBrand_noThreeRunSameCat() {
        // 12 个候选跨 4 类目 × 4 品牌，每品牌 ≥2 类目；直排 Top8 必然违例
        Product[] pool = {
                p(101, 1L, "A", 1.00), p(102, 1L, "A", 0.98), p(103, 1L, "B", 0.96),
                p(104, 2L, "A", 0.94), p(105, 2L, "B", 0.92), p(106, 2L, "C", 0.90),
                p(107, 3L, "A", 0.88), p(108, 3L, "C", 0.86), p(109, 3L, "D", 0.84),
                p(110, 4L, "B", 0.82), p(111, 4L, "C", 0.80), p(112, 4L, "D", 0.78)};
        Map<Long, Double> scores = new LinkedHashMap<>();
        double s = 1.0;
        for (Product product : pool) scores.put(product.getProdId(), s -= 0.02);

        List<Product> out = MmrReranker.rerank(Arrays.asList(pool), scores, 8, DEFAULT_LAMBDA);

        assertEquals(8, out.size(), "limit=8 输出 8 条");
        assertNoAdjacentSameBrand(out);
        assertNoThreeRunSameCat(out);
    }

    // ================= e) fallback 补齐 appendRelaxed =================

    @Test
    void e1_appendRelaxed_defersViolatorsPrefersCompliant() {
        List<Product> target = new ArrayList<>(List.of(p(1, 1L, "A", 0)));
        List<Product> candidates = Arrays.asList(
                p(2, 1L, "A", 0),  // 与尾项同品牌 → 违例，暂缓
                p(3, 2L, "B", 0),  // 合规 → 追加
                p(4, 1L, "A", 0)); // 与尾项(3,B)品牌不同、类目无三连 → 合规追加
        MmrReranker.appendRelaxed(target, candidates, 3);
        assertEquals(Arrays.asList(1L, 3L, 4L), ids(target), "违例 2 暂缓，合规 3/4 优先");
    }

    @Test
    void e2_appendRelaxed_relaxedInclusionWhenCompliantExhausted() {
        List<Product> target = new ArrayList<>(List.of(p(1, 1L, "A", 0)));
        List<Product> candidates = List.of(p(2, 2L, "A", 0)); // 唯一候选违例（同品牌相邻）
        MmrReranker.appendRelaxed(target, candidates, 2);
        assertEquals(2, target.size(), "合规候选耗尽仍松弛收录，列表不缩短");
        assertEquals(Arrays.asList(1L, 2L), ids(target));
    }

    @Test
    void e3_appendRelaxed_dedupAndRespectLimit() {
        List<Product> target = new ArrayList<>(List.of(p(1, 1L, "A", 0), p(2, 2L, "B", 0)));
        List<Product> candidates = Arrays.asList(
                p(1, 1L, "A", 0), p(2, 2L, "B", 0), // 已在列表 → 去重跳过
                p(3, 3L, "C", 0), p(4, 4L, "D", 0));
        MmrReranker.appendRelaxed(target, candidates, 3);
        assertEquals(3, target.size(), "受 limit 约束");
        assertEquals(Arrays.asList(1L, 2L, 3L), ids(target), "去重且不超限");
    }

    // ================= 边界 =================

    @Test
    void edgeCases_emptyPoolAndBadArgs() {
        assertTrue(MmrReranker.rerank(new ArrayList<>(), Map.of(), 8, DEFAULT_LAMBDA).isEmpty(), "空池 → 空结果");
        assertTrue(MmrReranker.rerank(null, null, 8, DEFAULT_LAMBDA).isEmpty(), "null 池 → 空结果");
        Product[] pool = {p(1, 1L, "A", 1.0)};
        Map<Long, Double> scores = new LinkedHashMap<>();
        scores.put(1L, 1.0);
        assertEquals(0, MmrReranker.rerank(Arrays.asList(pool), scores, 0, DEFAULT_LAMBDA).size(), "limit=0 → 空");
        // NaN λ → 默认 0.7，不抛异常
        assertEquals(1, MmrReranker.rerank(Arrays.asList(pool), scores, 1, Double.NaN).size());
    }
}

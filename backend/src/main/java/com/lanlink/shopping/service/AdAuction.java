package com.lanlink.shopping.service;

import com.lanlink.shopping.entity.AdCampaign;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 广告 GSP 拍卖引擎（无状态纯函数，不触库）。
 *
 * <p>完整链路「预估 → 排序 → 竞价 → 计费」：
 * <ul>
 *   <li>预估：质量分 q = Beta 平滑 pCTR × 类目相关性（相关性即质量分门槛——
 *       带类目上下文时，商品类目命中记 1.0、未命中记 0.5，非相关广告的有效出价被对半折减）；</li>
 *   <li>排序：rank_score = bid × q 降序（并列按 campaignId 稳定排序，保证可复现）；</li>
 *   <li>竞价计费（GSP 下一位）：第 k 名点击扣费 p_k = bid_{k+1} × q_{k+1} / q_k；
 *       末位 / 唯一候选无「下一位」，按自身 bid 计费；</li>
 *   <li>钳制：扣费价统一 setScale(2, HALF_UP)，下限 0.01，上限自身 bid（同精度舍入）——
 *       真实 GSP 语义：成交价既不高于自己的报价，也不低于一分钱。</li>
 * </ul>
 */
public final class AdAuction {

    /** 类目相关性系数：命中 1.0 / 未命中 0.5；无类目上下文视为命中（保持原 eCPM 行为） */
    public static final double RELEVANCE_HIT = 1.0;
    public static final double RELEVANCE_MISS = 0.5;

    /** 扣费价下限（分位保底，避免 0 元计费） */
    static final BigDecimal MIN_CHARGE = new BigDecimal("0.01");

    private AdAuction() {
    }

    /** 拍卖候选：推广计划 + 其推广商品的类目（相关性上下文） */
    public record Candidate(AdCampaign campaign, Long catId) {
    }

    /** 拍卖胜出者（列表顺序即排名）：chargePrice 为本次点击的 GSP 扣费价 */
    public record Winner(AdCampaign campaign, BigDecimal chargePrice, double rankScore, double quality) {
    }

    /**
     * 公开入口：按「Beta 平滑 pCTR × 类目相关性」构造质量分后委托 {@link #auction(List, ToDoubleFunction, int)}。
     *
     * @param candidates   拍卖候选（计划 + 商品类目）
     * @param contextCatId 当前浏览类目上下文；null 表示无上下文（全部记相关性命中）
     * @param limit        需要的胜出者数量
     */
    public static List<Winner> auction(List<Candidate> candidates, Long contextCatId, int limit) {
        return auction(candidates, c -> AdCampaignService.betaSmoothedCtr(
                c.campaign().getClicks(), c.campaign().getImpressions()) * relevance(c.catId(), contextCatId), limit);
    }

    /**
     * 包私有 q 注入重载：质量分由外部给定，供精确等式验证与后续质量分升级。
     */
    static List<Winner> auction(List<Candidate> candidates, ToDoubleFunction<Candidate> qualityFn, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        record Scored(Candidate cand, double quality, double rankScore) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c == null || c.campaign() == null || c.campaign().getCpcPrice() == null
                    || c.campaign().getCpcPrice().signum() <= 0) continue; // 无有效出价不参与竞价
            double q = Math.max(0, qualityFn.applyAsDouble(c));
            scored.add(new Scored(c, q, c.campaign().getCpcPrice().doubleValue() * q));
        }
        // rank_score 降序；同分按 campaignId 稳定排序（避免排序不稳定造成排名抖动）
        scored.sort(Comparator.comparingDouble(Scored::rankScore).reversed()
                .thenComparingLong(s -> s.cand().campaign().getCampaignId()));

        int n = Math.min(Math.max(limit, 0), scored.size());
        List<Winner> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            Scored cur = scored.get(k);
            BigDecimal ownBid = cur.cand().campaign().getCpcPrice();
            BigDecimal price;
            if (k + 1 < scored.size()) {
                Scored next = scored.get(k + 1);
                // GSP 下一位：p_k = bid_{k+1} × q_{k+1} / q_k；q_k=0 时无市场含义，按自身出价
                double raw = cur.quality() > 0
                        ? next.cand().campaign().getCpcPrice().doubleValue() * next.quality() / cur.quality()
                        : ownBid.doubleValue();
                price = clampCharge(raw, ownBid);
            } else {
                // 末位 / 唯一候选：下一位不存在，按自身 bid 计费
                price = ownBid.setScale(2, RoundingMode.HALF_UP);
            }
            out.add(new Winner(cur.cand().campaign(), price, cur.rankScore(), cur.quality()));
        }
        return out;
    }

    /** 类目相关性（质量分门槛）：无上下文或命中记 1.0，未命中记 0.5 */
    static double relevance(Long candidateCatId, Long contextCatId) {
        if (contextCatId == null) return RELEVANCE_HIT;
        return candidateCatId != null && candidateCatId.equals(contextCatId) ? RELEVANCE_HIT : RELEVANCE_MISS;
    }

    /** 扣费价钳制：2 位小数 HALF_UP，下限 0.01，上限自身 bid（同精度舍入）。包私有供钳制边界单测 */
    static BigDecimal clampCharge(double raw, BigDecimal ownBid) {
        BigDecimal price = BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(MIN_CHARGE) < 0) price = MIN_CHARGE;
        BigDecimal cap = ownBid.setScale(2, RoundingMode.HALF_UP);
        if (price.compareTo(cap) > 0) price = cap;
        return price;
    }
}

package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.AdCampaign;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.AdCampaignMapper;
import com.lanlink.shopping.mapper.MerchantMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.payment.wallet.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告推广服务（商户购买流量推广的完整闭环）。
 *
 * 生命周期：创建(pending_payment) → 钱包支付冻结(total_budget) → 投放(active，
 * 商城广告位曝光/点击计费) ⇄ 暂停(paused) → 结束(ended) → 剩余预算结算退回钱包。
 * 结束触发：到期(定时任务) / 预算耗尽(计费 SQL 原子置 ended) / 手动终止。
 */
@Service
public class AdCampaignService {

    private static final Logger log = LoggerFactory.getLogger("PAYMENT");
    /** 演示定价：按点击计费 CPM 曝光不计费 */
    public static final BigDecimal DEFAULT_CPC = new BigDecimal("0.50");
    private static final BigDecimal MIN_BUDGET = new BigDecimal("5");
    private static final BigDecimal MAX_BUDGET = new BigDecimal("50000");
    private static final int MAX_DURATION_DAYS = 90;

    private final AdCampaignMapper campaignMapper;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final WalletService walletService;

    public AdCampaignService(AdCampaignMapper campaignMapper, MerchantMapper merchantMapper,
                             ProductMapper productMapper, WalletService walletService) {
        this.campaignMapper = campaignMapper;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.walletService = walletService;
    }

    // ===== 商户端：创建 / 支付 / 暂停 / 恢复 / 终止 =====

    /** 创建推广计划（待支付）：校验商品归属且在售，预算 5 ~ 50000 元 */
    public AdCampaign create(Long userId, Map<String, Object> body) {
        Merchant m = mustMerchant(userId);
        Long prodId = toLong(body.get("prodId"));
        BigDecimal budget = toDecimal(body.get("totalBudget"));
        Integer days = toInt(body.get("durationDays"));
        String name = body.get("planName") == null ? null : String.valueOf(body.get("planName")).trim();

        Product p = productMapper.selectById(prodId);
        if (p == null || !m.getMerId().equals(p.getMerId())) throw new BusinessException("商品不存在或无权推广");
        if (p.getStatus() == null || p.getStatus() != 1 || p.getReviewStatus() == null || p.getReviewStatus() != 1) {
            throw new BusinessException("仅审核通过且在售的商品可推广");
        }
        if (budget == null || budget.compareTo(MIN_BUDGET) < 0 || budget.compareTo(MAX_BUDGET) > 0) {
            throw new BusinessException("总预算需在 " + MIN_BUDGET + " ~ " + MAX_BUDGET + " 元之间");
        }
        if (days == null || days < 1 || days > MAX_DURATION_DAYS) throw new BusinessException("投放天数需在 1 ~ " + MAX_DURATION_DAYS + " 天");
        if (name != null && name.length() > 64) throw new BusinessException("计划名称不能超过 64 个字符");

        AdCampaign c = new AdCampaign();
        c.setMerId(m.getMerId());
        c.setUserId(userId);
        c.setProdId(prodId);
        c.setPlanName(name == null || name.isEmpty() ? p.getTitle() : name);
        c.setTotalBudget(budget);
        c.setSpent(BigDecimal.ZERO);
        c.setClicks(0L);
        c.setImpressions(0L);
        c.setCpcPrice(DEFAULT_CPC);
        c.setDurationDays(days);
        c.setStatus("pending_payment");
        campaignMapper.insert(c);
        return c;
    }

    /** 钱包支付：冻结总预算并开始投放（余额不足抛业务异常） */
    @Transactional
    public AdCampaign pay(Long userId, Long campaignId) {
        AdCampaign c = mustOwn(userId, campaignId);
        if (!"pending_payment".equals(c.getStatus())) throw new BusinessException("该计划不在待支付状态");
        walletService.payFromWallet(userId, c.getTotalBudget(), "AD-" + c.getCampaignId());
        c.setStatus("active");
        c.setStartTime(LocalDateTime.now());
        c.setEndTime(LocalDateTime.now().plusDays(c.getDurationDays()));
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(c);
        log.info("[AD] 推广计划支付并开始投放 campaignId={} userId={} budget={}", campaignId, userId, c.getTotalBudget());
        return c;
    }

    public AdCampaign pause(Long userId, Long campaignId) {
        AdCampaign c = mustOwn(userId, campaignId);
        if (!"active".equals(c.getStatus())) throw new BusinessException("仅投放中的计划可暂停");
        c.setStatus("paused");
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(c);
        return c;
    }

    public AdCampaign resume(Long userId, Long campaignId) {
        AdCampaign c = mustOwn(userId, campaignId);
        if (!"paused".equals(c.getStatus())) throw new BusinessException("仅已暂停的计划可恢复");
        if (c.getEndTime() != null && c.getEndTime().isBefore(LocalDateTime.now())) {
            return settle(c, "expired");
        }
        c.setStatus("active");
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(c);
        return c;
    }

    /** 手动终止：立即结算，剩余预算退回钱包 */
    @Transactional
    public AdCampaign terminate(Long userId, Long campaignId) {
        AdCampaign c = mustOwn(userId, campaignId);
        if (!"active".equals(c.getStatus()) && !"paused".equals(c.getStatus())) {
            throw new BusinessException("该计划已结束");
        }
        return settle(c, "manual");
    }

    // ===== 计费与广告位（商城侧，公开） =====

    /** 点击计费：原子扣减，预算扣满自动置 ended；返回是否计费成功（非投放中返回 false） */
    public boolean trackClick(Long campaignId) {
        AdCampaign c = campaignMapper.selectById(campaignId);
        if (c == null || !"active".equals(c.getStatus())) return false;
        int hit = campaignMapper.chargeClick(campaignId, c.getCpcPrice());
        if (hit == 0) return false;
        // 预算耗尽（SQL 已置 ended）→ 结算退回（spent 已截断到预算，剩余为 0，仅落结算字段）
        AdCampaign after = campaignMapper.selectById(campaignId);
        if ("ended".equals(after.getStatus()) && after.getSettled() == null) {
            settle(after, "budget_exhausted");
        }
        return true;
    }

    /** 曝光计数（不扣费） */
    public void trackImpression(Long campaignId) {
        campaignMapper.countImpression(campaignId);
    }

    /**
     * 商城广告位：eCPM 排序 + 预算 pacing 准入（计费原子链路不变）。
     *
     * <p>eCPM = CPC × pCTR × 1000，pCTR 用 Beta 平滑 (clicks+α)/(impressions+α+β)
     * （α=0.1/β=10：低曝光计划向 1% 先验收缩，抑制小样本噪声）；pacing 按计划
     * 时长进度与花费进度之比节流——预算消耗快于时间进度时概率性让位，避免前快后慢。
     */
    public List<Map<String, Object>> slots(int limit) {
        int n = Math.max(1, Math.min(limit, 6));
        List<AdCampaign> candidates = campaignMapper.activeSlots(50);

        // eCPM 降序
        record Cand(AdCampaign c, double ecpm, double pacing) {}
        List<Cand> ranked = new ArrayList<>();
        for (AdCampaign c : candidates) {
            double pctr = betaSmoothedCtr(c.getClicks(), c.getImpressions());
            double ecpm = c.getCpcPrice() == null ? 0 : c.getCpcPrice().doubleValue() * pctr * 1000;
            ranked.add(new Cand(c, ecpm, pacingFactor(c)));
        }
        ranked.sort((a, b) -> Double.compare(b.ecpm, a.ecpm));

        // pacing 准入：消耗过快（factor < 0.5）的计划按概率跳过；下限 0.2 保底曝光
        List<AdCampaign> picked = new ArrayList<>();
        for (Cand cand : ranked) {
            if (picked.size() >= n) break;
            double factor = Math.max(0.2, cand.pacing());
            if (factor < 1.0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() > factor) {
                continue;
            }
            picked.add(cand.c());
        }
        // 全被 pacing 跳过时回退 eCPM TopN（广告位永不空）
        if (picked.isEmpty()) {
            ranked.stream().limit(n).forEach(cd -> picked.add(cd.c()));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (AdCampaign c : picked) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null) continue;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("campaignId", c.getCampaignId());
            slot.put("prodId", p.getProdId());
            slot.put("title", p.getTitle());
            slot.put("coverUrl", p.getCoverUrl());
            slot.put("price", p.getPrice());
            slot.put("brand", p.getBrand());
            slot.put("sales", p.getSales());
            slot.put("stock", p.getStock());
            slot.put("ad", true);
            out.add(slot);
        }
        return out;
    }

    /** Beta 平滑 CTR：抑制低曝光噪声（α=0.1/β=10 → 先验均值 1%） */
    static double betaSmoothedCtr(Long clicks, Long impressions) {
        double cl = clicks == null ? 0 : clicks;
        double im = impressions == null ? 0 : impressions;
        return (cl + 0.1) / (im + 10.1);
    }

    /** pacing 因子 = 时间进度 / 花费进度（>1 消耗偏慢可放量，<1 消耗偏快应收紧） */
    static double pacingFactor(AdCampaign c) {
        try {
            if (c.getStartTime() == null || c.getEndTime() == null || c.getTotalBudget() == null) return 1.0;
            long start = c.getStartTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            long end = c.getEndTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            long total = end - start;
            if (total <= 0) return 1.0;
            double timeProgress = (System.currentTimeMillis() - start) / (double) total;
            timeProgress = Math.max(0.05, Math.min(1.0, timeProgress));
            double spendProgress = c.getSpent() == null ? 0
                    : c.getSpent().doubleValue() / Math.max(c.getTotalBudget().doubleValue(), 0.01);
            spendProgress = Math.min(1.0, spendProgress);
            return Math.min(1.0, timeProgress / Math.max(spendProgress, 0.05));
        } catch (Exception e) {
            return 1.0;
        }
    }

    // ===== 报表 =====

    /** 商户推广计划列表（附带商品标题与状态文案） */
    public Map<String, Object> myCampaigns(Long userId) {
        Merchant m = mustMerchant(userId);
        Page<AdCampaign> page = campaignMapper.selectPage(new Page<>(1, 50),
                Wrappers.<AdCampaign>lambdaQuery()
                        .eq(AdCampaign::getUserId, userId)
                        .orderByDesc(AdCampaign::getCreateTime));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdCampaign c : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("campaign", c);
            Product p = productMapper.selectById(c.getProdId());
            row.put("prodTitle", p == null ? "商品已下架" : p.getTitle());
            row.put("prodCover", p == null ? null : p.getCoverUrl());
            row.put("remainBudget", c.getTotalBudget().subtract(c.getSpent()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", page.getTotal());
        out.put("records", rows);
        return out;
    }

    /** 推广总览：进行中/累计花费/点击/曝光/CTR/结算退回 + 钱包余额 */
    public Map<String, Object> stats(Long userId) {
        mustMerchant(userId);
        List<AdCampaign> all = campaignMapper.selectList(Wrappers.<AdCampaign>lambdaQuery()
                .eq(AdCampaign::getUserId, userId));
        long active = all.stream().filter(c -> "active".equals(c.getStatus())).count();
        long ended = all.stream().filter(c -> "ended".equals(c.getStatus())).count();
        BigDecimal spent = all.stream().map(AdCampaign::getSpent).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settled = all.stream()
                .map(c -> c.getSettled() == null ? BigDecimal.ZERO : c.getSettled())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long clicks = all.stream().mapToLong(c -> c.getClicks() == null ? 0 : c.getClicks()).sum();
        long impressions = all.stream().mapToLong(c -> c.getImpressions() == null ? 0 : c.getImpressions()).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPlans", all.size());
        out.put("activePlans", active);
        out.put("endedPlans", ended);
        out.put("totalSpent", spent);
        out.put("totalSettled", settled);
        out.put("totalClicks", clicks);
        out.put("totalImpressions", impressions);
        out.put("ctr", impressions == 0 ? 0 : Math.round(clicks * 10000.0 / impressions) / 100.0);
        out.put("cpcPrice", DEFAULT_CPC);
        out.put("walletBalance", walletService.balanceOf(userId));
        return out;
    }

    // ===== 结算与到期 =====

    /** 结算：剩余预算退回钱包并落结算字段（幂等：settled 非空直接返回） */
    @Transactional
    public AdCampaign settle(AdCampaign c, String reason) {
        if ("ended".equals(c.getStatus()) && c.getSettled() != null) return c;
        BigDecimal remain = c.getTotalBudget().subtract(c.getSpent() == null ? BigDecimal.ZERO : c.getSpent());
        if (remain.compareTo(BigDecimal.ZERO) < 0) remain = BigDecimal.ZERO;
        if (remain.compareTo(BigDecimal.ZERO) > 0) {
            walletService.refundToWallet(c.getUserId(), remain, "AD-" + c.getCampaignId(),
                    "推广计划结算退回（" + reasonText(reason) + "）");
        }
        c.setStatus("ended");
        c.setEndReason(reason);
        c.setSettled(remain);
        c.setSettleTime(LocalDateTime.now());
        c.setUpdateTime(LocalDateTime.now());
        campaignMapper.updateById(c);
        log.info("[AD] 推广计划结算 campaignId={} reason={} spent={} refund={}",
                c.getCampaignId(), reason, c.getSpent(), remain);
        return c;
    }

    /** 定时到期检查（每分钟）：过结束时间的投放中/暂停计划自动结算 */
    @Scheduled(fixedDelay = 60_000)
    public void expireCheck() {
        for (AdCampaign c : campaignMapper.expiredCampaigns()) {
            try {
                settle(c, "expired");
            } catch (Exception e) {
                log.warn("[AD] 到期结算失败 campaignId={}: {}", c.getCampaignId(), e.getMessage());
            }
        }
    }

    // ===== 内部 =====

    private Merchant mustMerchant(Long userId) {
        Merchant m = merchantMapper.selectOne(Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
        if (m == null) throw new BusinessException("仅入驻商户可使用流量推广");
        return m;
    }

    private AdCampaign mustOwn(Long userId, Long campaignId) {
        AdCampaign c = campaignMapper.selectById(campaignId);
        if (c == null || !userId.equals(c.getUserId())) throw new BusinessException("推广计划不存在或无权操作");
        return c;
    }

    private static String reasonText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "expired" -> "到期";
            case "budget_exhausted" -> "预算耗尽";
            case "manual" -> "手动结束";
            default -> "结束";
        };
    }

    private static Long toLong(Object o) {
        try { return o == null ? null : Long.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static Integer toInt(Object o) {
        try { return o == null ? null : Integer.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal toDecimal(Object o) {
        try { return o == null ? null : new BigDecimal(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}

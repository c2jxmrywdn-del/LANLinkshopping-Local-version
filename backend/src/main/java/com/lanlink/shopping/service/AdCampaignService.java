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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 演示定价：按点击计费 CPM 曝光不计费；商户可在创建时自定义出价（差异化出价是 GSP 拍卖的市场前提） */
    public static final BigDecimal DEFAULT_CPC = new BigDecimal("0.50");
    private static final BigDecimal MIN_CPC = new BigDecimal("0.10");
    private static final BigDecimal MAX_CPC = new BigDecimal("5.00");
    private static final BigDecimal MIN_BUDGET = new BigDecimal("5");
    private static final BigDecimal MAX_BUDGET = new BigDecimal("50000");
    private static final int MAX_DURATION_DAYS = 90;

    /** 拍卖扣费价记忆 TTL：30 分钟（GSP 价在拍卖时锁定，点击计费时取用；惰性清理） */
    private static final long AUCTION_PRICE_TTL_MS = 30 * 60 * 1000L;
    /** 拍卖价记忆容量上限：超过时触发一次过期剔除 */
    private static final int AUCTION_PRICE_MAX = 256;

    private final AdCampaignMapper campaignMapper;
    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final WalletService walletService;

    /** 拍卖扣费价记忆：campaignId → (GSP 扣费价, 存入时间)，点击计费时优先取用 */
    private record AuctionPrice(BigDecimal price, long at) {
    }

    private final Map<Long, AuctionPrice> auctionPrices = new ConcurrentHashMap<>();

    /**
     * 匿名点击去重窗口：同 IP 对同计划在该窗口内只计费一次（重复上报直接丢弃，不计数不扣费）。
     * 收敛匿名刷点击烧预算的攻击面；正常投放语义不变（窗口外的点击与其它 IP 均正常计费）。
     */
    private static final long CLICK_DEDUP_WINDOW_MS = 10 * 60 * 1000L;
    /** 去重表容量上限：超过时惰性剔除过期项 */
    private static final int CLICK_DEDUP_MAX = 100_000;

    /** key = clientIp|campaignId → 最近一次点击上报时间戳（惰性清理） */
    private final Map<String, Long> recentClicks = new ConcurrentHashMap<>();

    public AdCampaignService(AdCampaignMapper campaignMapper, MerchantMapper merchantMapper,
                             ProductMapper productMapper, WalletService walletService) {
        this.campaignMapper = campaignMapper;
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.walletService = walletService;
    }

    // ===== 商户端：创建 / 支付 / 暂停 / 恢复 / 终止 =====

    /** 创建推广计划（待支付）：校验商品归属且在售，预算 5 ~ 50000 元；点击出价可选（0.10 ~ 5.00 元，缺省 0.50） */
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
        BigDecimal cpc = toDecimal(body.get("cpcPrice"));
        if (cpc == null) cpc = DEFAULT_CPC;
        cpc = cpc.setScale(2, RoundingMode.HALF_UP);
        if (cpc.compareTo(MIN_CPC) < 0 || cpc.compareTo(MAX_CPC) > 0) {
            throw new BusinessException("点击出价需在 " + MIN_CPC + " ~ " + MAX_CPC + " 元之间");
        }

        AdCampaign c = new AdCampaign();
        c.setMerId(m.getMerId());
        c.setUserId(userId);
        c.setProdId(prodId);
        c.setPlanName(name == null || name.isEmpty() ? p.getTitle() : name);
        c.setTotalBudget(budget);
        c.setSpent(BigDecimal.ZERO);
        c.setClicks(0L);
        c.setImpressions(0L);
        c.setCpcPrice(cpc);
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

    /** 点击计费：原子扣减（GSP 拍卖价优先，无新鲜拍卖记录回退计划自身 cpc_price），预算扣满自动置 ended */
    public boolean trackClick(Long campaignId, String clientIp) {
        if (!acquireClickSlot(campaignId, clientIp)) return false;
        AdCampaign c = campaignMapper.selectById(campaignId);
        if (c == null || !"active".equals(c.getStatus())) return false;
        int hit = campaignMapper.chargeClick(campaignId, effectiveClickPrice(c));
        if (hit == 0) return false;
        // 预算耗尽（SQL 已置 ended）→ 结算退回（spent 已截断到预算，剩余为 0，仅落结算字段）
        AdCampaign after = campaignMapper.selectById(campaignId);
        if ("ended".equals(after.getStatus()) && after.getSettled() == null) {
            settle(after, "budget_exhausted");
        }
        return true;
    }

    /** 去重窗口抢占：同 IP 同计划窗口内重复点击返回 false；来源 IP 缺失的匿名点击一律不计费（fail-closed） */
    private boolean acquireClickSlot(Long campaignId, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return false;
        String key = clientIp + "|" + campaignId;
        long now = System.currentTimeMillis();
        Long prev = recentClicks.put(key, now);
        if (prev != null && now - prev < CLICK_DEDUP_WINDOW_MS) return false;
        if (recentClicks.size() > CLICK_DEDUP_MAX) {
            recentClicks.entrySet().removeIf(e -> now - e.getValue() > CLICK_DEDUP_WINDOW_MS);
        }
        return true;
    }

    /** 曝光计数（不扣费） */
    public void trackImpression(Long campaignId) {
        campaignMapper.countImpression(campaignId);
    }

    /**
     * 商城广告位：完整 GSP 拍卖链路（预估 → 排序 → 竞价 → 计费）+ 预算 pacing 前置准入。
     *
     * <p>质量分 q = Beta 平滑 pCTR × 类目相关性（catId 命中 1.0 / 未命中 0.5，无上下文 1.0，
     * 详见 {@link AdAuction}）；pacing 准入仍按 eCPM 预排序（CPC × pCTR × 1000）节流——
     * 预算消耗快于时间进度时概率性让位。准入候选进入 GSP 拍卖定序与定价，
     * 扣费价 p_k = bid_{k+1} × q_{k+1} / q_k（末位按自身 bid），2 位小数 HALF_UP、
     * 下限 0.01、上限自身出价。拍卖价记录在内存（30 分钟 TTL 惰性清理），
     * 点击计费时优先采用（{@link #trackClick}），无记录回退计划自身 cpc_price。
     */
    public List<Map<String, Object>> slots(int limit, Long contextCatId) {
        int n = Math.max(1, Math.min(limit, 6));
        List<AdCampaign> candidates = campaignMapper.activeSlots(50);

        // eCPM 预排序（pacing 准入用）
        record Cand(AdCampaign c, double ecpm) {}
        List<Cand> ranked = new ArrayList<>();
        for (AdCampaign c : candidates) {
            double pctr = betaSmoothedCtr(c.getClicks(), c.getImpressions());
            double ecpm = c.getCpcPrice() == null ? 0 : c.getCpcPrice().doubleValue() * pctr * 1000;
            ranked.add(new Cand(c, ecpm));
        }
        ranked.sort((a, b) -> Double.compare(b.ecpm, a.ecpm));

        // pacing 准入：消耗过快（factor < 0.5）的计划按概率跳过；下限 0.2 保底曝光
        List<AdCampaign> admitted = new ArrayList<>();
        for (Cand cand : ranked) {
            if (admitted.size() >= n) break;
            double factor = Math.max(0.2, pacingFactor(cand.c()));
            if (factor < 1.0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble() > factor) {
                continue;
            }
            admitted.add(cand.c());
        }
        // 全被 pacing 跳过时回退 eCPM TopN（广告位永不空）
        if (admitted.isEmpty()) {
            ranked.stream().limit(n).forEach(cd -> admitted.add(cd.c()));
        }

        // 取商品（相关性 catId + 展示字段），无效商品剔除后进入 GSP 拍卖
        List<AdAuction.Candidate> auctionCandidates = new ArrayList<>();
        Map<Long, Product> prodById = new LinkedHashMap<>();
        for (AdCampaign c : admitted) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null) continue;
            prodById.put(c.getCampaignId(), p);
            auctionCandidates.add(new AdAuction.Candidate(c, p.getCatId()));
        }
        List<AdAuction.Winner> winners = AdAuction.auction(auctionCandidates, contextCatId, n);
        rememberAuctionPrices(winners);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AdAuction.Winner w : winners) {
            Product p = prodById.get(w.campaign().getCampaignId());
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("campaignId", w.campaign().getCampaignId());
            slot.put("prodId", p.getProdId());
            slot.put("title", p.getTitle());
            slot.put("coverUrl", p.getCoverUrl());
            slot.put("price", p.getPrice());
            slot.put("brand", p.getBrand());
            slot.put("sales", p.getSales());
            slot.put("stock", p.getStock());
            slot.put("ad", true);
            slot.put("chargePrice", w.chargePrice()); // 本次点击 GSP 扣费价（新增字段，其余不变）
            out.add(slot);
        }
        return out;
    }

    /** 记录拍卖扣费价（点击计费时优先取用）；超容量时惰性清理过期项 */
    private void rememberAuctionPrices(List<AdAuction.Winner> winners) {
        if (winners.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (AdAuction.Winner w : winners) {
            auctionPrices.put(w.campaign().getCampaignId(), new AuctionPrice(w.chargePrice(), now));
        }
        if (auctionPrices.size() > AUCTION_PRICE_MAX) {
            auctionPrices.entrySet().removeIf(e -> now - e.getValue().at() > AUCTION_PRICE_TTL_MS);
        }
    }

    /** 点击应扣价：30 分钟内的拍卖价优先；无记录或已过期回退计划自身 cpc_price（过期项顺手剔除） */
    private BigDecimal effectiveClickPrice(AdCampaign c) {
        AuctionPrice e = auctionPrices.get(c.getCampaignId());
        if (e == null) return c.getCpcPrice();
        if (System.currentTimeMillis() - e.at() <= AUCTION_PRICE_TTL_MS) return e.price();
        auctionPrices.remove(c.getCampaignId(), e);
        return c.getCpcPrice();
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
        // 出价口径：cpcPrice = 全部计划出价的算术平均（2 位小数 HALF_UP），无计划回退 DEFAULT_CPC；
        // avgActualCpc = totalSpent / max(clicks,1) 的实付均价，无点击回退出价均值（前端单价展示优先实付均价）
        BigDecimal cpcAvg = all.isEmpty() ? DEFAULT_CPC
                : all.stream()
                        .map(c -> c.getCpcPrice() == null ? BigDecimal.ZERO : c.getCpcPrice())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(all.size()), 2, RoundingMode.HALF_UP);
        BigDecimal avgActualCpc = clicks > 0
                ? spent.divide(new BigDecimal(clicks), 2, RoundingMode.HALF_UP)
                : cpcAvg;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPlans", all.size());
        out.put("activePlans", active);
        out.put("endedPlans", ended);
        out.put("totalSpent", spent);
        out.put("totalSettled", settled);
        out.put("totalClicks", clicks);
        out.put("totalImpressions", impressions);
        out.put("ctr", impressions == 0 ? 0 : Math.round(clicks * 10000.0 / impressions) / 100.0);
        out.put("cpcPrice", cpcAvg);
        out.put("avgActualCpc", avgActualCpc);
        out.put("walletBalance", walletService.balanceOf(userId));
        return out;
    }

    // ===== 结算与到期 =====

    /**
     * 结算：剩余预算退回钱包并落结算字段。
     * 并发安全：先以条件 UPDATE 抢占结算权（WHERE settled IS NULL，金额在行锁内按当前行值计算），
     * 抢占成功（affected=1）的一方才退钱包；失败方读到最新已结算行直接返回，杜绝到期定时任务
     * 与手动终止并发时的钱包双退。
     */
    @Transactional
    public AdCampaign settle(AdCampaign c, String reason) {
        if ("ended".equals(c.getStatus()) && c.getSettled() != null) return c;
        int claimed = campaignMapper.claimSettle(c.getCampaignId(), reason);
        if (claimed == 0) {
            AdCampaign latest = campaignMapper.selectById(c.getCampaignId());
            return latest != null ? latest : c;
        }
        AdCampaign settledRow = campaignMapper.selectById(c.getCampaignId());
        BigDecimal remain = settledRow.getSettled() == null ? BigDecimal.ZERO : settledRow.getSettled();
        if (remain.compareTo(BigDecimal.ZERO) > 0) {
            walletService.refundToWallet(c.getUserId(), remain, "AD-" + c.getCampaignId(),
                    "推广计划结算退回（" + reasonText(reason) + "）");
        }
        log.info("[AD] 推广计划结算 campaignId={} reason={} spent={} refund={}",
                c.getCampaignId(), reason, settledRow.getSpent(), remain);
        return settledRow;
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

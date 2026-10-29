package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.entity.PriceHistory;
import com.lanlink.shopping.module.pricemonitor.mapper.PriceHistoryMapper;
import com.lanlink.shopping.support.OutboundModelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 价格情报 AI 服务：
 *  - analysis：结合价格走势、行业基准、波动率与市场资讯，输出结构化洞察；
 *  - ask：回答“为什么/起因/经过/结果/后续”等因果问题。
 *
 * 优先调用平台已配置的外部大模型（OutboundModelConfigService.activeChannels）；
 * 未配置或调用失败时，回退到本地规则引擎，返回同结构结果并标注 source=RULE。
 */
@Service
public class PriceMonitorAiService {

    private static final Logger log = LoggerFactory.getLogger(PriceMonitorAiService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long CACHE_TTL_MS = 3 * 60 * 1000L;

    private final ProductMapper productMapper;
    private final PriceHistoryMapper historyMapper;
    private final IndustryMapper industryMapper;
    private final CategoryMapper categoryMapper;
    private final MarketNewsService newsService;
    private final PriceMonitorService monitorService;
    private final OutboundModelConfigService configService;
    private final NewsLinkService newsLinkService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /**
     * 同一问题/分析的并发请求合并（single-flight）：用户反复点"提问"/多标签同时请求时，
     * 只触发一次本地 7B 推理，其余请求等待同一结果，避免把单卡模型队列堵死。
     */
    private final Map<String, java.util.concurrent.CompletableFuture<Map<String, Object>>> inflight =
            new ConcurrentHashMap<>();
    /** 外部模型熔断：失败后 5 分钟内直接走规则引擎，避免每次请求都等待超时。 */
    private volatile long aiDisabledUntil = 0;

    private record Cached(Object value, long at) { }

    public PriceMonitorAiService(ProductMapper productMapper, PriceHistoryMapper historyMapper,
                                 IndustryMapper industryMapper, MarketNewsService newsService,
                                 PriceMonitorService monitorService, OutboundModelConfigService configService,
                                 NewsLinkService newsLinkService, CategoryMapper categoryMapper) {
        this.productMapper = productMapper;
        this.historyMapper = historyMapper;
        this.industryMapper = industryMapper;
        this.newsService = newsService;
        this.monitorService = monitorService;
        this.configService = configService;
        this.newsLinkService = newsLinkService;
        this.categoryMapper = categoryMapper;
    }

    // ==================== 五维价格因子（按商品） ====================

    /** 五维顺序固定：市场情绪 / 上游供应链 / 基础原材料 / 国际化因素 / 政治与政策。 */
    private static final List<String> FACTOR_KEYS = ProductFactorProfile.FACTOR_KEYS;

    /** 因子定义：市场情绪 / 上游供应链 / 基础原材料 / 国际化因素 / 政治与政策。 */
    private static final Map<String, String> FACTOR_LABELS;
    private static final Map<String, String> FACTOR_LABELS_EN;
    private static final Map<String, List<String>> FACTOR_KEYWORDS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sentiment", "市场情绪");
        labels.put("supply", "上游供应链");
        labels.put("material", "基础原材料");
        labels.put("international", "国际化因素");
        labels.put("politics", "政治与政策");
        FACTOR_LABELS = Collections.unmodifiableMap(labels);

        Map<String, String> labelsEn = new LinkedHashMap<>();
        labelsEn.put("sentiment", "Market sentiment");
        labelsEn.put("supply", "Upstream supply");
        labelsEn.put("material", "Raw materials");
        labelsEn.put("international", "Global factors");
        labelsEn.put("politics", "Policy & politics");
        FACTOR_LABELS_EN = Collections.unmodifiableMap(labelsEn);

        Map<String, List<String>> kw = new LinkedHashMap<>();
        kw.put("sentiment", List.of("情绪", "预期", "看涨", "看跌", "采购", "询盘", "订单", "补库", "需求",
                "景气", "PMI", "成交", "观望", "信心", "价格", "涨价", "报价", "领涨", "领跌"));
        kw.put("supply", List.of("供应", "供应链", "产能", "库存", "停产", "限产", "检修", "交期", "物流",
                "港口", "运力", "舱位", "缺货", "交付", "错峰生产", "运输"));
        kw.put("material", List.of("原材料", "原料", "铜", "铝", "钢", "水泥", "纯碱", "塑料", "树脂", "纸浆",
                "锂", "芯片", "面板", "原油", "油价", "能源", "电价", "涤纶", "MLCC", "碳酸锂", "瓦楞纸"));
        kw.put("international", List.of("国际", "全球", "海外", "出口", "进口", "汇率", "美元", "海运", "运价",
                "关税", "欧盟", "美联储", "跨境", "外贸", "地缘"));
        kw.put("politics", List.of("政策", "监管", "环保", "碳", "补贴", "税费", "地缘", "制裁", "贸易", "限电",
                "督察", "配额", "退税", "政治", "会议", "错峰生产"));
        FACTOR_KEYWORDS = Collections.unmodifiableMap(kw);
    }

    /**
     * 价格行为画像：本商品自己的价格历史 + 行业基准推导出的数值信号，
     * 是「不同商品因子拉开差距」的第二来源（第一来源是个性化资讯相关性）。
     */
    private record PriceBehavior(BigDecimal momentum30dPct, BigDecimal rangePositionPct,
                                 Integer stockCoverageDays, BigDecimal relativePct,
                                 BigDecimal benchmarkChangePct, BigDecimal volatilityPct,
                                 int changeCount, BigDecimal currentPrice, BigDecimal firstPrice,
                                 boolean hasPriceData) { }

    /** 单条资讯在某一维度上的打分：dir 方向 / importance 重要度 / factorRelevance 维度命中度 / productRelevance 商品相关度。 */
    private record ScoredNews(MarketNews news, int dir, int importance, int factorRelevance,
                              double productRelevance) { }

    /** 五维因子看板（中文）：按商品做系统性分析。 */
    public Map<String, Object> factors(Long prodId) {
        return factors(prodId, "zh");
    }

    /**
     * 五维因子看板：把「全局最新资讯」换成「本商品相关资讯加权分」+「本商品价格行为分」融合计算。
     *
     * <p>资讯分：先用维度关键词筛出该维度资讯，再按 {@link ProductFactorProfile#relevanceOf} 加权
     * （商品相关 3 / 同行业 2 / 泛市场 1 / 其他行业 0.4），权重再乘行业暴露度；
     * 行为分：本商品 30 日动量、区间位置、相对行业强弱、库存覆盖等。两者 65% / 35% 合成，
     * 因此不同商品命中不同资讯、价格走势不同，factors 输出必然不同。
     *
     * <p>返回键序固定：prodId/title/industryName/categoryName/productProfile/factors/
     * sentimentIndex/dominant/summary/systematicAnalysis/generatedAt/disclaimer。
     */
    public Map<String, Object> factors(Long prodId, String lang) {
        boolean en = isEnglish(lang);
        Product p = mustProduct(prodId);
        String industryName = industryNameOf(p);
        String categoryName = categoryNameOf(p);
        ProductFactorProfile profile = ProductFactorProfile.of(p, industryName, categoryName);
        Map<String, Object> rule = safeAnalyze(prodId);
        PriceBehavior behavior = priceBehavior(p, rule, safeHistories(prodId));
        List<MarketNews> news = safeNews(40);
        List<Map<String, Object>> list = computeFactors(news, profile, behavior, en);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodId", p.getProdId());
        out.put("title", p.getTitle());
        out.put("industryName", industryName);
        out.put("categoryName", categoryName);
        out.put("productProfile", productProfile(profile, categoryName));
        out.put("factors", list);
        out.put("sentimentIndex", sentimentIndex(list));
        out.put("dominant", dominantFactor(list));
        out.put("summary", productSummary(list, p, en));
        out.put("systematicAnalysis", systematicAnalysis(list, behavior, profile, p, en));
        out.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        out.put("disclaimer", en
                ? "Factors are quantified from recent market news and this product's own price behaviour; for reference only."
                : "因子由近期市场资讯与本商品价格行为量化生成，仅作经营决策参考。");
        return out;
    }

    private static Map<String, Object> productProfile(ProductFactorProfile profile, String categoryName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keywords", profile.productKeywords());
        m.put("industryKeywords", profile.industryKeywords());
        m.put("factorWeights", profile.factorWeights());
        m.put("categoryName", categoryName);
        return m;
    }

    /**
     * 核心：按商品计算五维因子。资讯只查一次、商品相关度只算一次（复杂度 O(资讯数×5)），
     * analysis/ask 的上下文复用同一方法，避免重复查库与口径漂移。
     */
    private List<Map<String, Object>> computeFactors(List<MarketNews> news, ProductFactorProfile profile,
                                                     PriceBehavior behavior, boolean en) {
        Map<String, List<ScoredNews>> grouped = new LinkedHashMap<>();
        for (String key : FACTOR_KEYS) grouped.put(key, new ArrayList<>());
        for (MarketNews n : news) {
            if (n == null) continue;
            String text = textOf(n);
            double productRelevance = profile.relevanceOf(n);
            int importance = n.getImportance() == null ? 1 : Math.max(1, n.getImportance());
            for (String key : FACTOR_KEYS) {
                if (!matchesFactor(key, n.getCategory(), text)) continue;
                grouped.get(key).add(new ScoredNews(n, direction(key, text), importance,
                        relevanceFor(key, n, text), productRelevance));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : FACTOR_KEYS) {
            out.add(buildFactor(key, grouped.get(key), profile, behavior, en));
        }
        return out;
    }

    /** 单个维度：资讯加权分 + 行为分 → 综合分 / 强度 / 证据 / 商品信号。 */
    private Map<String, Object> buildFactor(String key, List<ScoredNews> matched, ProductFactorProfile profile,
                                            PriceBehavior behavior, boolean en) {
        matched.sort((a, b) -> {
            if (Double.compare(b.productRelevance, a.productRelevance) != 0) {
                return Double.compare(b.productRelevance, a.productRelevance);
            }
            int ca = Math.abs(a.dir * a.importance);
            int cb = Math.abs(b.dir * b.importance);
            if (ca != cb) return Integer.compare(cb, ca);
            return compareAtDesc(a.news.getPublishedAt(), b.news.getPublishedAt());
        });

        double weight = profile.weightOf(key);
        double weightedImportance = 0;
        double directedImportance = 0;
        int productNewsCount = 0;
        for (ScoredNews s : matched) {
            double w = s.importance * s.productRelevance;
            weightedImportance += w;
            directedImportance += s.dir * w;
            if (s.productRelevance >= 2.0) productNewsCount++;
        }
        int newsCount = matched.size();
        // 资讯分：Σ(方向*重要度*商品相关度) / Σ(重要度*商品相关度)（-1..1）→ ×60×行业权重
        int newsScore = weightedImportance <= 0 ? 0
                : clampScore(Math.round(directedImportance / weightedImportance * 60.0 * weight));
        int behaviorScore = behaviorScoreOf(key, behavior, newsScore, newsCount, weight);
        // 综合分：资讯 65% + 本商品价格行为 35%
        int score = clampScore(Math.round(newsScore * 0.65 + behaviorScore * 0.35));
        double agreement = weightedImportance <= 0 ? 0
                : Math.min(1.0, Math.abs(directedImportance) / weightedImportance);
        boolean fresh = matched.stream().anyMatch(s -> s.news.getPublishedAt() != null
                && s.news.getPublishedAt().isAfter(LocalDateTime.now().minusDays(3)));
        int strength = factorStrength(newsCount, productNewsCount, agreement, fresh, behaviorCompleteness(behavior));
        String impact = score >= 18 ? "up" : score <= -18 ? "down" : "neutral";
        String label = en ? factorLabelEn(key) : FACTOR_LABELS.get(key);

        List<String> evidence = new ArrayList<>();
        List<Map<String, Object>> newsRefs = new ArrayList<>();
        for (int i = 0; i < Math.min(2, matched.size()); i++) {
            ScoredNews s = matched.get(i);
            MarketNews n = s.news;
            String tag = s.dir > 0 ? (en ? "bullish" : "偏多")
                    : s.dir < 0 ? (en ? "bearish" : "偏空") : (en ? "neutral" : "中性");
            evidence.add(en
                    ? n.getTitle() + " (" + n.getSource() + ", " + tag + ")"
                    : n.getTitle() + "（" + n.getSource() + "，" + tag + "）");
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("title", n.getTitle());
            ref.put("source", n.getSource());
            ref.put("impact", s.dir > 0 ? "up" : s.dir < 0 ? "down" : "neutral");
            ref.put("publishedAt", n.getPublishedAt() == null ? null : n.getPublishedAt().format(TIME_FMT));
            newsLinkService.apply(ref, n);
            ref.put("relevance", s.productRelevance);
            newsRefs.add(ref);
        }
        if (evidence.isEmpty()) {
            evidence.add(en ? "No direct signal for this dimension in recent news - treated as neutral."
                    : "近期资讯中未发现该维度的直接信号，暂按中性处理。");
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("impact", impact);
        f.put("score", score);
        f.put("strength", strength);
        f.put("newsCount", newsCount);
        f.put("productNewsCount", productNewsCount);
        f.put("headline", matched.isEmpty() ? null : matched.get(0).news.getTitle());
        f.put("evidence", evidence);
        f.put("newsRefs", newsRefs);
        f.put("updatedAt", matched.stream().map(ScoredNews::news).map(MarketNews::getPublishedAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo)
                .map(t -> t.format(TIME_FMT)).orElse(null));
        f.put("newsScore", newsScore);
        f.put("behaviorScore", behaviorScore);
        f.put("weight", weight);
        f.put("productSignals", productSignals(key, behavior, newsCount, productNewsCount, en));
        f.put("basis", factorBasis(newsScore, behaviorScore, score, newsCount, productNewsCount, weight, en));
        return f;
    }

    /**
     * 行为分（-100..100，无数据=0）：每个维度都吃「本商品自己的价格行为」，
     * 这是不同商品因子拉开差距的第二来源。映射（写成注释便于调参/复核）：
     * <ul>
     *   <li>sentiment：30 日动量 × 6 + (区间位置 - 50) × 0.8</li>
     *   <li>supply：相对行业强弱 × 6 + (50 - 区间位置) × 0.6 + 库存信号（&lt;15 天 +12；&gt;60 天 -12）</li>
     *   <li>material：(基准 30 日变动 × 6 + 基准波动率 × 1.5) × weight</li>
     *   <li>international：30 日动量 × 4 × weight</li>
     *   <li>politics：波动率 × 2，方向取该维度资讯方向（资讯分&lt;0 取负；无政治类资讯=0）</li>
     * </ul>
     */
    private static int behaviorScoreOf(String factor, PriceBehavior b, int newsScore, int newsCount, double weight) {
        double momentum = b.momentum30dPct().doubleValue();
        double relative = b.relativePct().doubleValue();
        double range = b.rangePositionPct().doubleValue();
        double benchmark = b.benchmarkChangePct() == null ? 0 : b.benchmarkChangePct().doubleValue();
        double volatility = b.volatilityPct() == null ? 0 : b.volatilityPct().doubleValue();
        double stockSignal = b.stockCoverageDays() == null ? 0
                : b.stockCoverageDays() < 15 ? 12 : b.stockCoverageDays() > 60 ? -12 : 0;
        double raw = switch (factor) {
            case "sentiment" -> momentum * 6 + (range - 50) * 0.8;
            case "supply" -> relative * 6 + (50 - range) * 0.6 + stockSignal;
            case "material" -> (benchmark * 6 + volatility * 1.5) * weight;
            case "international" -> momentum * 4 * weight;
            case "politics" -> newsCount == 0 ? 0 : Math.abs(volatility * 2) * (newsScore < 0 ? -1 : 1);
            default -> 0;
        };
        return clampScore(Math.round(raw));
    }

    /** 强度 12~96：本商品相关资讯覆盖 + 泛资讯广度 + 方向一致性 + 行为数据完整度 + 新鲜度。 */
    private static int factorStrength(int newsCount, int productNewsCount, double agreement,
                                      boolean fresh, double completeness) {
        if (newsCount == 0) {
            return (int) Math.max(12, Math.min(24, Math.round(12 + completeness * 12)));
        }
        double coverage = Math.min(1.0, productNewsCount / 5.0);
        double breadth = Math.min(1.0, newsCount / 10.0);
        double v = 22 + coverage * 40 + breadth * 8 + agreement * 18 + completeness * 10 + (fresh ? 6 : 0);
        return (int) Math.max(12, Math.min(96, Math.round(v)));
    }

    /** 行为数据完整度 0..1：自身价格历史 / 行业基准变动 / 库存覆盖 / 调价次数 四项。 */
    private static double behaviorCompleteness(PriceBehavior b) {
        int n = 0;
        if (b.hasPriceData()) n++;
        if (b.benchmarkChangePct() != null) n++;
        if (b.stockCoverageDays() != null) n++;
        if (b.changeCount() > 0) n++;
        return n / 4.0;
    }

    private static int clampScore(long v) {
        return (int) Math.max(-100, Math.min(100, v));
    }

    private static int compareAtDesc(LocalDateTime a, LocalDateTime b) {
        LocalDateTime ta = a == null ? LocalDateTime.MIN : a;
        LocalDateTime tb = b == null ? LocalDateTime.MIN : b;
        return tb.compareTo(ta);
    }


    private static String textOf(MarketNews n) {
        return (n.getTitle() == null ? "" : n.getTitle()) + " "
                + (n.getSummary() == null ? "" : n.getSummary()) + " "
                + (n.getTags() == null ? "" : n.getTags()) + " "
                + (n.getCategory() == null ? "" : n.getCategory());
    }

    private static boolean matchesFactor(String factor, String category, String text) {
        if (categoryFactor(category).equals(factor)) return true;
        for (String k : FACTOR_KEYWORDS.get(factor)) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private static int relevanceFor(String factor, MarketNews n, String text) {
        int score = categoryFactor(n.getCategory()).equals(factor) ? 2 : 0;
        for (String k : FACTOR_KEYWORDS.get(factor)) {
            if (text.contains(k)) score++;
        }
        return score;
    }

    private static String categoryFactor(String category) {
        return switch (category == null ? "" : category) {
            case "supply" -> "supply";
            case "policy", "politics" -> "politics";
            case "international" -> "international";
            case "tech", "material" -> "material";
            default -> "sentiment";
        };
    }

    private static int direction(String factor, String text) {
        int s = 0;
        switch (factor) {
            case "sentiment" -> {
                s += hits(text, "回暖", "上行", "走强", "上涨", "上调", "增长", "扩张", "补库", "订单增加",
                        "询盘上升", "提速", "改善", "看涨", "信心回升", "需求增长", "供不应求", "上升", "坚挺");
                s -= hits(text, "放缓", "收缩", "疲软", "订单减少", "询盘下降", "看跌", "观望", "萎缩",
                        "下行", "走弱", "需求下降", "回落", "回调", "下调", "减少");
            }
            case "supply" -> {
                s += hits(text, "库存回落", "库存下降", "供给收紧", "供给偏紧", "偏紧", "紧缺", "缺货", "停产",
                        "限产", "错峰生产", "交期延长", "运价回升", "舱位趋紧", "加工费走低", "产能收缩");
                s -= hits(text, "库存上升", "库存增加", "供给宽松", "复产", "交期缩短", "运力宽松",
                        "产能释放", "供应充足", "投产");
            }
            case "material" -> {
                s += hits(text, "上行", "上涨", "上调", "涨价", "走高", "走强", "抬升", "坚挺", "趋紧",
                        "偏紧", "成本上升", "成本压力", "领涨");
                s -= hits(text, "回落", "下跌", "下调", "降价", "走低", "回调", "缓解", "趋稳",
                        "成本下降", "领跌");
            }
            case "international" -> {
                s += hits(text, "运价回升", "舱位趋紧", "汇率承压", "成本承压", "关税上调", "出口回暖",
                        "海外需求增长", "进口成本上升", "出口增长", "宽幅震荡");
                s -= hits(text, "运价回落", "汇率企稳", "关税下调", "出口放缓", "海外需求下降", "进口成本下降");
            }
            case "politics" -> {
                s += hits(text, "限产", "督察", "碳关税", "配额收紧", "制裁", "出口管制", "补贴退坡",
                        "环保约束", "监管趋严", "承压", "错峰生产");
                s -= hits(text, "补贴", "退税", "降费", "减税", "支持", "宽松", "优化流程", "改善");
            }
            default -> { }
        }
        return Integer.signum(s);
    }

    private static int hits(String text, String... words) {
        int n = 0;
        for (String w : words) {
            if (text.contains(w)) n++;
        }
        return n;
    }

    private static int sentimentIndex(List<Map<String, Object>> factors) {
        int wsum = 0;
        int ssum = 0;
        for (Map<String, Object> f : factors) {
            int score = (int) f.get("score");
            int strength = (int) f.get("strength");
            wsum += score * strength;
            ssum += strength;
        }
        int idx = ssum == 0 ? 50 : 50 + Math.round((float) wsum / ssum / 2f);
        return Math.max(0, Math.min(100, idx));
    }

    private static Map<String, Object> dominantFactor(List<Map<String, Object>> factors) {
        Map<String, Object> best = null;
        double bestV = -1;
        for (Map<String, Object> f : factors) {
            double v = Math.abs((int) f.get("score")) * ((int) f.get("strength")) / 100.0;
            if (v > bestV) {
                bestV = v;
                best = f;
            }
        }
        if (best == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", best.get("key"));
        m.put("label", best.get("label"));
        m.put("impact", best.get("impact"));
        m.put("score", best.get("score"));
        m.put("strength", best.get("strength"));
        m.put("headline", best.get("headline"));
        m.put("evidence", joinEvidence(best));
        return m;
    }

    private static String factorSummary(List<Map<String, Object>> factors) {
        int idx = sentimentIndex(factors);
        String stance = idx >= 58 ? "偏多" : idx <= 42 ? "偏空" : "中性";
        Map<String, Object> d = dominantFactor(factors);
        if (d == null) return "五维因子综合指数 " + idx + "/100，市场信号" + stance + "。";
        int score = (int) d.get("score");
        String dir = score >= 18 ? "上行压力" : score <= -18 ? "下行压力" : "中性";
        return "五维因子综合指数 " + idx + "/100，市场信号" + stance
                + "；当前主导因子为" + d.get("label") + "（" + dir + "，强度 " + d.get("strength") + "）。";
    }

    private static String joinEvidence(Map<String, Object> f) {
        Object ev = f.get("evidence");
        if (ev instanceof List<?> l) return String.join("；", l.stream().map(String::valueOf).toList());
        return ev == null ? "" : String.valueOf(ev);
    }

    // ---------- 商品信号 / 依据 / 系统性分析 ----------

    /** 单维度「商品级」信号：把本商品价格行为 + 资讯覆盖翻译成给人看的一句话（每条都可追溯到本商品数据）。 */
    private static List<String> productSignals(String factor, PriceBehavior b, int newsCount,
                                               int productNewsCount, boolean en) {
        List<String> out = new ArrayList<>();
        String momentum = en ? "Own price " + signed(b.momentum30dPct()) + "% over 30 days"
                : "自身价格近30日 " + signed(b.momentum30dPct()) + "%";
        String range = b.rangePositionPct().toPlainString();
        switch (factor) {
            case "sentiment" -> {
                if (b.hasPriceData()) {
                    out.add(momentum);
                    out.add(en ? "Current price sits at " + range + "% of the 30-day range"
                            : "现价处于近30日区间 " + range + "% 分位");
                }
            }
            case "supply" -> {
                if (b.hasPriceData()) {
                    BigDecimal rel = b.relativePct();
                    String direction = rel.signum() > 0 ? (en ? "above" : "高于")
                            : rel.signum() < 0 ? (en ? "below" : "低于") : (en ? "in line with" : "持平");
                    String relText = en
                            ? "Own price " + signed(b.momentum30dPct()) + "%, " + direction
                              + " the industry by " + signed(rel.abs()) + " pct"
                            : "自身价格近30日 " + signed(b.momentum30dPct()) + "%，" + direction
                              + "行业 " + signed(rel.abs()) + "pct";
                    out.add(relText);
                }
                if (b.stockCoverageDays() != null) {
                    int days = b.stockCoverageDays();
                    String tag = days < 15 ? (en ? " (tight)" : "（偏紧）")
                            : days > 60 ? (en ? " (heavy)" : "（偏高）") : "";
                    out.add(en ? "Inventory covers about " + days + " days of sales" + tag
                            : "库存覆盖约 " + days + " 天" + tag);
                }
            }
            case "material" -> {
                if (b.benchmarkChangePct() != null) {
                    out.add(en ? "Industry benchmark " + signed(b.benchmarkChangePct()) + "% over 30 days"
                            : "行业基准近30日 " + signed(b.benchmarkChangePct()) + "%");
                }
                out.add(en ? "Benchmark volatility " + b.volatilityPct() + "%"
                        : "行业基准波动率 " + b.volatilityPct() + "%");
            }
            case "international" -> {
                if (b.hasPriceData()) out.add(momentum);
                if (b.benchmarkChangePct() != null) {
                    out.add(en ? "Benchmark " + signed(b.benchmarkChangePct()) + "% vs own price " + signed(b.momentum30dPct()) + "%"
                            : "基准 " + signed(b.benchmarkChangePct()) + "% vs 自身 " + signed(b.momentum30dPct()) + "%");
                }
            }
            case "politics" -> {
                if (newsCount == 0) {
                    out.add(en ? "No policy-related news in the current window" : "近期无政治/政策类资讯");
                } else {
                    out.add(en ? "Benchmark volatility " + b.volatilityPct() + "% with " + newsCount + " policy-related news items"
                            : "行业基准波动率 " + b.volatilityPct() + "%，政治/政策类资讯 " + newsCount + " 条");
                }
            }
            default -> { }
        }
        if (productNewsCount > 0) {
            out.add(en ? "Matched " + productNewsCount + " product/industry-relevant news items"
                    : "匹配 " + productNewsCount + " 条本商品相关资讯");
        } else if (newsCount > 0) {
            out.add(en ? newsCount + " market-wide news items, none specific to this product"
                    : "命中 " + newsCount + " 条泛市场资讯，暂无本商品专属资讯");
        }
        if (out.isEmpty()) {
            out.add(en ? "No price behaviour data yet - scored from news direction only"
                    : "暂无可用价格行为数据，仅按资讯方向计分");
        }
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }

    /** 维度依据：一行说明「资讯分 × 权重 + 行为分 → 综合分」，便于运营/前端解释因子来源。 */
    private static String factorBasis(int newsScore, int behaviorScore, int score, int newsCount,
                                      int productNewsCount, double weight, boolean en) {
        if (en) {
            return "Weighted news score " + newsScore + " from " + newsCount + " items (" + productNewsCount
                    + " relevant to this product/industry) x industry weight " + weight
                    + " + price-behaviour score " + behaviorScore + " -> composite " + score + ".";
        }
        return "资讯加权分 " + newsScore + "（" + newsCount + " 条，其中 " + productNewsCount
                + " 条与本商品/本行业相关）× 行业权重 " + weight
                + " + 本商品价格行为分 " + behaviorScore + " → 综合 " + score + "。";
    }

    /** 顶层 summary（带商品名）。 */
    private static String productSummary(List<Map<String, Object>> factors, Product p, boolean en) {
        int idx = sentimentIndex(factors);
        Map<String, Object> d = dominantFactor(factors);
        if (en) {
            String stance = idx >= 58 ? "bullish" : idx <= 42 ? "bearish" : "neutral";
            StringBuilder sb = new StringBuilder("Product #").append(p.getProdId())
                    .append(": five-factor composite index ").append(idx).append("/100 (").append(stance).append(")");
            if (d != null) {
                int score = (int) d.get("score");
                String dir = score >= 18 ? "upward pressure" : score <= -18 ? "downward pressure" : "neutral";
                sb.append("; dominant factor: ").append(d.get("label")).append(" (").append(dir)
                        .append(", strength ").append(d.get("strength")).append(")");
            }
            return sb.append(".").toString();
        }
        String stance = idx >= 58 ? "偏多" : idx <= 42 ? "偏空" : "中性";
        StringBuilder sb = new StringBuilder("商品「").append(p.getTitle() == null ? "" : p.getTitle())
                .append("」五维因子综合指数 ").append(idx).append("/100，市场信号").append(stance);
        if (d != null) {
            int score = (int) d.get("score");
            String dir = score >= 18 ? "上行压力" : score <= -18 ? "下行压力" : "中性";
            sb.append("；当前主导因子为").append(d.get("label")).append("（").append(dir)
                    .append("，强度 ").append(d.get("strength")).append("）");
        }
        return sb.append("。").toString();
    }

    /** 系统性分析：价格行为关键数值 + 各维度贡献拆解 + 驱动 + 结论。 */
    private static Map<String, Object> systematicAnalysis(List<Map<String, Object>> factors, PriceBehavior b,
                                                          ProductFactorProfile profile, Product p, boolean en) {
        Map<String, Object> sa = new LinkedHashMap<>();
        sa.put("priceChange30dPct", b.momentum30dPct());
        sa.put("relativePct", b.relativePct());
        sa.put("volatilityPct", b.volatilityPct());
        sa.put("changeCount", b.changeCount());
        sa.put("rangePositionPct", b.rangePositionPct());
        sa.put("stockCoverageDays", b.stockCoverageDays());

        double totalWeight = 0;
        for (String key : FACTOR_KEYS) totalWeight += profile.weightOf(key);
        List<Map<String, Object>> contributions = new ArrayList<>();
        for (Map<String, Object> f : factors) {
            int score = (int) f.get("score");
            double weight = ((Number) f.get("weight")).doubleValue();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", f.get("key"));
            c.put("label", f.get("label"));
            c.put("score", score);
            c.put("newsScore", f.get("newsScore"));
            c.put("behaviorScore", f.get("behaviorScore"));
            c.put("weight", weight);
            c.put("contribution", BigDecimal.valueOf(score * weight / totalWeight)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
            contributions.add(c);
        }
        sa.put("factorContributions", contributions);
        sa.put("drivers", systematicDrivers(factors, b, en));
        sa.put("conclusion", systematicConclusion(factors, b, p, en));
        return sa;
    }

    private static List<Map<String, Object>> systematicDrivers(List<Map<String, Object>> factors,
                                                               PriceBehavior b, boolean en) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (b.hasPriceData()) {
            out.add(driver(en ? "Own price movement" : "自身价格变动", impactOf(b.momentum30dPct()),
                    en ? "Price moved from ¥" + b.firstPrice() + " to ¥" + b.currentPrice()
                            + " (" + signed(b.momentum30dPct()) + "%) over 30 days, "
                            + signed(b.relativePct()) + " pct vs the industry."
                            : "近30日价格由 ¥" + b.firstPrice() + " 变为 ¥" + b.currentPrice()
                            + "（" + signed(b.momentum30dPct()) + "%），相对行业 "
                            + signed(b.relativePct()) + " 个百分点。"));
        }
        if (b.benchmarkChangePct() != null) {
            out.add(driver(en ? "Industry benchmark" : "行业基准", impactOf(b.benchmarkChangePct()),
                    en ? "Benchmark moved " + signed(b.benchmarkChangePct()) + "% in 30 days, volatility "
                            + b.volatilityPct() + "%."
                            : "行业基准近30日 " + signed(b.benchmarkChangePct()) + "%，波动率 "
                            + b.volatilityPct() + "%。"));
        }
        if (b.stockCoverageDays() != null) {
            out.add(driver(en ? "Inventory coverage" : "库存覆盖", "neutral",
                    en ? "Current inventory covers about " + b.stockCoverageDays() + " days of average sales."
                            : "当前库存约可覆盖 " + b.stockCoverageDays() + " 天的平均销量。"));
        }
        Map<String, Object> dominant = dominantFactor(factors);
        if (dominant != null && ((int) dominant.get("score")) != 0) {
            int ds = (int) dominant.get("score");
            String dk = String.valueOf(dominant.get("key"));
            int productNews = 0;
            for (Map<String, Object> f : factors) {
                if (dk.equals(f.get("key"))) productNews = (int) f.get("productNewsCount");
            }
            out.add(driver((en ? "Five-factor · " : "五维因子 · ") + dominant.get("label"),
                    ds > 0 ? "up" : "down",
                    en ? "Factor score " + ds + "/100 (strength " + dominant.get("strength") + ") on "
                            + productNews + " product-relevant news items."
                            : "评分 " + ds + "/100（强度 " + dominant.get("strength")
                            + "），本商品相关资讯 " + productNews + " 条。"));
        }
        if (out.isEmpty()) {
            out.add(driver(en ? "Stable pricing" : "价格稳定", "neutral",
                    en ? "No material price change or benchmark move was recorded in the last 30 days."
                            : "近30日未出现明显调价，行业基准也未显著波动。"));
        }
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static String systematicConclusion(List<Map<String, Object>> factors, PriceBehavior b,
                                               Product p, boolean en) {
        int idx = sentimentIndex(factors);
        Map<String, Object> d = dominantFactor(factors);
        if (en) {
            String stance = idx >= 58 ? "bullish" : idx <= 42 ? "bearish" : "neutral";
            StringBuilder sb = new StringBuilder("Product #").append(p.getProdId()).append(" shows a ")
                    .append(stance).append(" five-factor signal (index ").append(idx).append("/100)");
            if (d != null) sb.append(", led by ").append(d.get("label"));
            sb.append("; price over the last 30 days was ").append(signed(b.momentum30dPct()))
                    .append("%, ").append(signed(b.relativePct())).append(" pct vs the industry");
            if (b.stockCoverageDays() != null) {
                sb.append(", with inventory covering about ").append(b.stockCoverageDays()).append(" days");
            }
            return sb.append(".").toString();
        }
        String stance = idx >= 58 ? "偏多" : idx <= 42 ? "偏空" : "中性";
        StringBuilder sb = new StringBuilder("商品「").append(p.getTitle() == null ? "" : p.getTitle())
                .append("」五维因子信号").append(stance).append("（综合指数 ").append(idx).append("/100）");
        if (d != null) sb.append("，主导因子为").append(d.get("label"));
        sb.append("；近30日价格 ").append(signed(b.momentum30dPct())).append("%，相对行业 ")
                .append(signed(b.relativePct())).append(" 个百分点");
        if (b.stockCoverageDays() != null) {
            sb.append("，库存约覆盖 ").append(b.stockCoverageDays()).append(" 天");
        }
        return sb.append("。").toString();
    }

    private static String impactOf(BigDecimal v) {
        if (v == null) return "neutral";
        return v.signum() > 0 ? "up" : v.signum() < 0 ? "down" : "neutral";
    }

    // ---------- 数据读取与价格行为 ----------

    /** 价格行为画像：null 安全（analyze 返回空 Map、无价格历史都能算）。 */
    private PriceBehavior priceBehavior(Product p, Map<String, Object> rule, List<PriceHistory> histories) {
        BigDecimal current = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
        BigDecimal first = current;
        BigDecimal high = null;
        BigDecimal low = null;
        boolean hasPriceData = histories != null && !histories.isEmpty();
        if (hasPriceData) {
            PriceHistory h0 = histories.get(0);
            first = h0.getOldPrice() != null ? h0.getOldPrice()
                    : h0.getNewPrice() != null ? h0.getNewPrice() : current;
            for (PriceHistory h : histories) {
                for (BigDecimal v : new BigDecimal[]{h.getOldPrice(), h.getNewPrice()}) {
                    if (v == null) continue;
                    if (high == null || v.compareTo(high) > 0) high = v;
                    if (low == null || v.compareTo(low) < 0) low = v;
                }
            }
        }
        BigDecimal momentum = pctChange(current, first);
        BigDecimal rangePosition;
        if (high == null || low == null || high.compareTo(low) <= 0) {
            rangePosition = new BigDecimal("50.0");
        } else {
            rangePosition = current.subtract(low).multiply(BigDecimal.valueOf(100))
                    .divide(high.subtract(low), 1, RoundingMode.HALF_UP);
            if (rangePosition.compareTo(BigDecimal.ZERO) < 0) rangePosition = new BigDecimal("0.0");
            if (rangePosition.compareTo(BigDecimal.valueOf(100)) > 0) rangePosition = new BigDecimal("100.0");
        }
        BigDecimal benchmarkChange = dec(rule.get("benchmarkChangePct"));
        BigDecimal volatility = dec(rule.get("volatilityPct"));
        if (volatility == null) volatility = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal relative = momentum
                .subtract(benchmarkChange == null ? BigDecimal.ZERO : benchmarkChange)
                .setScale(2, RoundingMode.HALF_UP);
        Integer counted = intOf(rule.get("changeCount"));
        int changeCount = counted != null ? counted : (hasPriceData ? histories.size() : 0);
        return new PriceBehavior(momentum, rangePosition, stockCoverageDays(p), relative,
                benchmarkChange, volatility, changeCount, current, first, hasPriceData);
    }

    /** 库存可覆盖天数 = stock / max(sales/30, 1)；销量缺失或非正返回 null。 */
    private static Integer stockCoverageDays(Product p) {
        if (p == null || p.getStock() == null || p.getSales() == null || p.getSales() <= 0) return null;
        double dailySales = Math.max(p.getSales() / 30.0, 1.0);
        return (int) Math.round(p.getStock() / dailySales);
    }

    private static BigDecimal pctChange(BigDecimal current, BigDecimal base) {
        if (base == null || base.signum() <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return current.subtract(base).multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object v) {
        BigDecimal d = dec(v);
        return d == null ? null : d.intValue();
    }

    /** 按商品相关度取资讯：>=2（本商品/本行业）优先，不足的用最新资讯补足到 limit 条。 */
    private List<MarketNews> relevantNews(List<MarketNews> all, ProductFactorProfile profile, int limit) {
        List<MarketNews> out = new ArrayList<>();
        List<MarketNews> relevant = new ArrayList<>();
        for (MarketNews n : all) {
            if (n != null && profile.relevanceOf(n) >= 2.0) relevant.add(n);
        }
        relevant.sort((a, b) -> {
            double ra = profile.relevanceOf(a);
            double rb = profile.relevanceOf(b);
            if (Double.compare(rb, ra) != 0) return Double.compare(rb, ra);
            return compareAtDesc(a.getPublishedAt(), b.getPublishedAt());
        });
        out.addAll(relevant.subList(0, Math.min(limit, relevant.size())));
        if (out.size() < limit) {
            List<MarketNews> latest = new ArrayList<>(all);
            latest.sort((a, b) -> compareAtDesc(a.getPublishedAt(), b.getPublishedAt()));
            for (MarketNews n : latest) {
                if (out.size() >= limit) break;
                if (n != null && !out.contains(n)) out.add(n);
            }
        }
        return out;
    }

    private String industryNameOf(Product p) {
        if (p == null || p.getIndId() == null) return null;
        Industry industry = industryMapper.selectById(p.getIndId());
        return industry == null ? null : industry.getName();
    }

    private String categoryNameOf(Product p) {
        if (p == null || p.getCatId() == null || categoryMapper == null) return null;
        Category category = categoryMapper.selectById(p.getCatId());
        return category == null ? null : category.getName();
    }

    /** analyze 为空 Map / null 时按「无基准数据」处理，不阻断因子看板。 */
    private Map<String, Object> safeAnalyze(Long prodId) {
        try {
            Map<String, Object> rule = monitorService.analyze(prodId);
            return rule == null ? new LinkedHashMap<>() : rule;
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[price-ai] analyze failed for prod {}: {}", prodId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private List<PriceHistory> safeHistories(Long prodId) {
        try {
            List<PriceHistory> list = historyMapper.selectList(Wrappers.<PriceHistory>lambdaQuery()
                    .eq(PriceHistory::getProdId, prodId)
                    .ge(PriceHistory::getCreateTime, LocalDateTime.now().minusDays(30))
                    .orderByAsc(PriceHistory::getCreateTime));
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            log.warn("[price-ai] price history unavailable for prod {}: {}", prodId, e.getMessage());
            return List.of();
        }
    }

    private List<MarketNews> safeNews(int limit) {
        try {
            List<MarketNews> list = newsService.latest(limit);
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            log.warn("[price-ai] market news unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 对外 ====================

    public Map<String, Object> analysis(Long prodId) {
        return analysis(prodId, "zh");
    }

    public Map<String, Object> analysis(Long prodId, String lang) {
        boolean en = isEnglish(lang);
        String key = "A:" + prodId + ":" + (en ? "en" : "zh");
        return coalesce(key, () -> doAnalysis(prodId, en));
    }

    public Map<String, Object> ask(Long prodId, String question) {
        return ask(prodId, question, "zh");
    }

    public Map<String, Object> ask(Long prodId, String question, String lang) {
        boolean en = isEnglish(lang);
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) throw new BusinessException(en ? "Please enter a question." : "请输入你想了解的问题");
        if (q.length() > 200) q = q.substring(0, 200);
        String key = "Q:" + prodId + ":" + (en ? "en" : "zh") + ":" + q.hashCode();
        final String finalQ = q;
        return coalesce(key, () -> doAsk(prodId, finalQ, en));
    }

    /** 缓存 + 并发合并：同 key 只执行一次工作，其他线程共享结果。 */
    private Map<String, Object> coalesce(String key, java.util.function.Supplier<Map<String, Object>> work) {
        Map<String, Object> cached = getCached(key);
        if (cached != null) return cached;
        java.util.concurrent.CompletableFuture<Map<String, Object>> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Map<String, Object>> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) {
            try {
                return existing.join();
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause == null ? e : cause);
            }
        }
        try {
            Map<String, Object> out = work.get();
            putCached(key, out);
            mine.complete(out);
            return out;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, mine);
        }
    }

    private static boolean isEnglish(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
    }

    // ==================== 分析 ====================

    private Map<String, Object> doAnalysis(Long prodId, boolean en) {
        Product p = mustProduct(prodId);
        Map<String, Object> rule = safeAnalyze(prodId);
        Ctx ctx = context(p, rule, en);

        Map<String, Object> out = baseOut(p, ctx, en);
        JsonNode ai = callAi(analysisPrompt(ctx, en), en);
        if (ai != null) {
            ai = unwrapNested(ai);
            out.put("source", "AI");
            out.put("summary", text(ai, "summary"));
            List<Map<String, Object>> aiDrivers = driversFrom(ai, en);
            out.put("drivers", dedupeFactorLabelDrivers(aiDrivers, ctx, en));
            out.put("risk", text(ai, "risk"));
            out.put("suggestion", text(ai, "suggestion"));
            out.put("model", ctx.modelName);
        } else if (en) {
            fillRuleAnalysisEn(out, ctx);
        } else {
            fillRuleAnalysis(out, ctx);
        }
        return out;
    }

    /**
     * 小模型偶发把整包 JSON 再塞进 summary 字段（形如 {"summary":"{\"summary\":…}"}）。
     * 守卫：summary 值本身是 JSON 对象文本时二次解析，展开为真正的字段。
     */
    private static JsonNode unwrapNested(JsonNode ai) {
        String s = ai.path("summary").asText("");
        String t = s.trim();
        if (t.startsWith("{") && t.contains("\"summary\"")) {
            JsonNode inner = extractJsonStatic(t);
            if (inner != null && inner.has("summary") && inner.path("summary").isTextual()) {
                return inner;
            }
        }
        return ai;
    }

    private static JsonNode extractJsonStatic(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = JSON.readTree(content.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 小模型常见退化：把“市场情绪/上游供应链/…”五个维度名原样当 drivers 返回。
     * 守卫：剔除纯维度名条目；若剔除后不足 2 条，用本商品自身调价、最强因子（带依据）与
     * 最新资讯补齐，保证每个商品的 drivers 都是具体、差异化的业务事实。
     */
    private List<Map<String, Object>> dedupeFactorLabelDrivers(List<Map<String, Object>> aiDrivers, Ctx ctx, boolean en) {
        List<String> labels = new ArrayList<>(FACTOR_LABELS.values());
        labels.addAll(FACTOR_LABELS_EN.values());
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> d : aiDrivers) {
            String label = String.valueOf(d.get("label")).trim();
            String stripped = label.replaceAll("^(五维因子|Five-factor)\\s*[·•:]?\\s*", "").trim();
            boolean bareFactor = labels.contains(label) || labels.contains(stripped);
            boolean dup = kept.stream().anyMatch(k -> String.valueOf(k.get("label")).equals(label));
            if (!bareFactor && !dup) kept.add(d);
        }
        if (kept.size() >= 2) return kept;

        // 不足 2 条：按规则引擎口径补齐（自身调价 → 最强因子带依据 → 最新资讯）
        List<Map<String, Object>> out = new ArrayList<>(kept);
        if (ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0) {
            out.add(driver(
                    ctx.priceChangePct.signum() > 0
                            ? (en ? "Own price moved up" : "本商品价格上行")
                            : (en ? "Own price moved down" : "本商品价格下行"),
                    ctx.priceChangePct.signum() > 0 ? "up" : "down",
                    en ? "Moved from ¥" + ctx.firstPrice + " to ¥" + ctx.currentPrice + " ("
                            + signed(ctx.priceChangePct) + "%) across " + ctx.changeCount + " changes."
                            : "近30日由 ¥" + ctx.firstPrice + " 变为 ¥" + ctx.currentPrice
                            + "（" + signed(ctx.priceChangePct) + "%），共调价 " + ctx.changeCount + " 次。"));
        }
        Map<String, Object> dominant = dominantFactor(ctx.factorList);
        if (dominant != null && ((int) dominant.get("score")) != 0) {
            out.add(driver((en ? "Five-factor · " : "五维因子 · ") + dominant.get("label"),
                    ((int) dominant.get("score")) > 0 ? "up" : "down",
                    String.valueOf(joinEvidence(dominant))));
        }
        for (MarketNews n : ctx.news.subList(0, Math.min(2, ctx.news.size()))) {
            String label = (en ? categoryNameEn(n.getCategory()) : categoryName(n.getCategory()))
                    + "：" + n.getTitle();
            boolean dup = out.stream().anyMatch(k -> String.valueOf(k.get("label")).equals(label));
            if (!dup) out.add(driver(label, "neutral", n.getSummary() == null ? "" : n.getSummary()));
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    private void fillRuleAnalysis(Map<String, Object> out, Ctx ctx) {
        List<Map<String, Object>> drivers = new ArrayList<>();
        if (ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0) {
            drivers.add(driver(
                    ctx.priceChangePct.signum() > 0 ? "本商品价格上行" : "本商品价格下行",
                    ctx.priceChangePct.signum() > 0 ? "up" : "down",
                    "近30日由 ¥" + ctx.firstPrice + " 变为 ¥" + ctx.currentPrice
                            + "（" + signed(ctx.priceChangePct) + "%），共调价 " + ctx.changeCount + " 次。"));
        }
        if (ctx.benchmarkChangePct != null && ctx.benchmarkChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0
                && (ctx.priceChangePct.signum() == 0 || ctx.benchmarkChangePct.signum() == ctx.priceChangePct.signum())) {
            drivers.add(driver(
                    ctx.benchmarkChangePct.signum() > 0 ? "行业基准上行" : "行业基准下行",
                    ctx.benchmarkChangePct.signum() > 0 ? "up" : "down",
                    "近30日行业基准由 ¥" + ctx.firstBenchmark + " 变为 ¥" + ctx.latestBenchmark
                            + "（" + signed(ctx.benchmarkChangePct) + "%），与商品走势同向。"));
        }
        Map<String, Object> dominant = dominantFactor(ctx.factorList);
        if (dominant != null && ((int) dominant.get("score")) != 0) {
            int ds = (int) dominant.get("score");
            drivers.add(driver("五维因子 · " + dominant.get("label"), ds > 0 ? "up" : "down",
                    "评分 " + ds + "（" + (ds > 0 ? "偏多" : "偏空") + "），强度 "
                            + dominant.get("strength") + "/100；" + dominant.get("evidence")));
        }
        for (MarketNews n : ctx.news.subList(0, Math.min(2, ctx.news.size()))) {
            drivers.add(driver(categoryName(n.getCategory()) + "：" + n.getTitle(), "neutral",
                    n.getSummary() == null ? "" : n.getSummary()));
        }
        if (drivers.isEmpty()) {
            drivers.add(driver("价格保持稳定", "neutral", "近30日未出现明显调价，行业基准也未显著波动。"));
        }

        String risk;
        BigDecimal absRel = ctx.relativePct.abs();
        if (ctx.volatilityPct.compareTo(new BigDecimal("8")) > 0 || absRel.compareTo(new BigDecimal("12")) >= 0) {
            risk = "HIGH";
        } else if (ctx.volatilityPct.compareTo(new BigDecimal("4")) > 0 || absRel.compareTo(new BigDecimal("6")) >= 0) {
            risk = "MEDIUM";
        } else {
            risk = "LOW";
        }

        String summary = "近30日商品价格" + (ctx.priceChangePct.signum() == 0 ? "基本持平"
                : (ctx.priceChangePct.signum() > 0 ? "上涨 " + ctx.priceChangePct + "%" : "下跌 " + ctx.priceChangePct.abs() + "%"))
                + "，行业基准" + (ctx.benchmarkChangePct == null ? "数据不足"
                : signed(ctx.benchmarkChangePct) + "%")
                + "，相对强弱 " + signed(ctx.relativePct) + " 个百分点；当前价 ¥" + ctx.currentPrice
                + "；行业基准波动率 " + ctx.volatilityPct + "%"
                + "；" + factorSummary(ctx.factorList)
                + (ctx.news.isEmpty() ? "" : " 近期需关注：" + ctx.news.get(0).getTitle() + "。");

        StringBuilder suggestion = new StringBuilder();
        if (ctx.relativePct.compareTo(new BigDecimal("8")) >= 0) {
            suggestion.append("走势显著强于行业，具备维持价格或收缩让利的空间，建议保持现价并跟踪竞品。");
        } else if (ctx.relativePct.compareTo(new BigDecimal("-8")) <= 0) {
            suggestion.append("走势显著弱于行业，需警惕需求走弱或低价竞争，建议核查成本、库存与竞品报价。");
        } else {
            suggestion.append("走势与行业基本同步，建议保持现价，按周复查行业基准。");
        }
        if (!ctx.news.isEmpty()) {
            suggestion.append(" 建议持续跟踪「").append(ctx.news.get(0).getSource()).append("」等渠道的")
                    .append(categoryName(ctx.news.get(0).getCategory())).append("动态。");
        }
        if (risk.equals("HIGH")) suggestion.append(" 当前波动偏高，建议缩短复查周期并设置价格预警。");

        out.put("source", "RULE");
        out.put("summary", summary);
        out.put("drivers", drivers);
        out.put("risk", risk);
        out.put("suggestion", suggestion.toString());
        out.put("notice", "外部模型未配置或调用失败，已使用本地规则引擎分析");
    }

    /** 英文规则引擎分析：不引用中文资讯标题，保证英文界面零中文残留。 */
    private void fillRuleAnalysisEn(Map<String, Object> out, Ctx ctx) {
        List<Map<String, Object>> drivers = new ArrayList<>();
        if (ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0) {
            drivers.add(driver("Own price adjustment",
                    ctx.priceChangePct.signum() > 0 ? "up" : "down",
                    "Moved from ¥" + ctx.firstPrice + " to ¥" + ctx.currentPrice + " ("
                            + signed(ctx.priceChangePct) + "%) across " + ctx.changeCount + " changes in 30 days."));
        }
        if (ctx.benchmarkChangePct != null && ctx.benchmarkChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0
                && (ctx.priceChangePct.signum() == 0 || ctx.benchmarkChangePct.signum() == ctx.priceChangePct.signum())) {
            drivers.add(driver("Industry benchmark " + (ctx.benchmarkChangePct.signum() > 0 ? "up" : "down"),
                    ctx.benchmarkChangePct.signum() > 0 ? "up" : "down",
                    "Benchmark moved from ¥" + ctx.firstBenchmark + " to ¥" + ctx.latestBenchmark + " ("
                            + signed(ctx.benchmarkChangePct) + "%) in the same direction."));
        }
        Map<String, Object> dominant = dominantFactor(ctx.factorList);
        if (dominant != null && ((int) dominant.get("score")) != 0) {
            int ds = (int) dominant.get("score");
            String key = String.valueOf(dominant.get("key"));
            drivers.add(driver("Five-factor · " + factorLabelEn(key), ds > 0 ? "up" : "down",
                    "Factor score " + ds + "/100, strength " + dominant.get("strength") + "/100, based on "
                            + factorNewsCount(ctx.factorList, key) + " related news items."));
        }
        if (drivers.isEmpty()) {
            drivers.add(driver("Stable pricing", "neutral",
                    "No material price change or benchmark move was recorded in the last 30 days."));
        }

        String risk = riskOf(ctx);
        String summary = "Over the last 30 days the price "
                + (ctx.priceChangePct.signum() == 0 ? "held steady"
                : ctx.priceChangePct.signum() > 0 ? "rose " + ctx.priceChangePct + "%"
                : "fell " + ctx.priceChangePct.abs() + "%")
                + " to ¥" + ctx.currentPrice + "; the industry benchmark "
                + (ctx.benchmarkChangePct == null ? "is unavailable"
                : (ctx.benchmarkChangePct.signum() >= 0 ? "rose " : "fell ") + ctx.benchmarkChangePct.abs() + "%")
                + ", leaving relative strength at " + signed(ctx.relativePct) + " pct. "
                + factorSummaryEn(ctx.factorList);

        StringBuilder suggestion = new StringBuilder();
        if (ctx.relativePct.compareTo(new BigDecimal("8")) >= 0) {
            suggestion.append("Relative strength is clearly above the industry: hold the current price and track competitor quotes.");
        } else if (ctx.relativePct.compareTo(new BigDecimal("-8")) <= 0) {
            suggestion.append("Relative strength is clearly below the industry: review costs, inventory and competitor pricing for signs of weakening demand.");
        } else {
            suggestion.append("The price is broadly in line with the industry: hold and review the benchmark weekly.");
        }
        if (risk.equals("HIGH")) suggestion.append(" Volatility is elevated; shorten the review cycle and turn on price alerts.");

        out.put("source", "RULE");
        out.put("summary", summary);
        out.put("drivers", drivers);
        out.put("risk", risk);
        out.put("suggestion", suggestion.toString());
        out.put("notice", "External model unavailable — the local rule engine answered instead.");
    }

    private static String riskOf(Ctx ctx) {
        BigDecimal absRel = ctx.relativePct.abs();
        if (ctx.volatilityPct.compareTo(new BigDecimal("8")) > 0 || absRel.compareTo(new BigDecimal("12")) >= 0) {
            return "HIGH";
        }
        if (ctx.volatilityPct.compareTo(new BigDecimal("4")) > 0 || absRel.compareTo(new BigDecimal("6")) >= 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String factorLabelEn(String key) {
        return switch (key == null ? "" : key) {
            case "sentiment" -> "Market sentiment";
            case "supply" -> "Upstream supply";
            case "material" -> "Raw materials";
            case "international" -> "Global factors";
            case "politics" -> "Policy & politics";
            default -> "Market factor";
        };
    }

    private static Object factorNewsCount(List<Map<String, Object>> factors, String key) {
        for (Map<String, Object> f : factors) {
            if (key.equals(f.get("key"))) return f.get("newsCount");
        }
        return 0;
    }

    private static String factorSummaryEn(List<Map<String, Object>> factors) {
        int idx = sentimentIndex(factors);
        String stance = idx >= 58 ? "bullish" : idx <= 42 ? "bearish" : "neutral";
        Map<String, Object> d = dominantFactor(factors);
        if (d == null) return "Five-factor composite index " + idx + "/100 (" + stance + ").";
        int score = (int) d.get("score");
        String dir = score >= 18 ? "upward pressure" : score <= -18 ? "downward pressure" : "neutral";
        return "Five-factor composite index " + idx + "/100 (" + stance + "); dominant factor: "
                + factorLabelEn(String.valueOf(d.get("key"))) + " (" + dir + ", strength "
                + d.get("strength") + ").";
    }

    // ==================== 因果问答 ====================

    private Map<String, Object> doAsk(Long prodId, String question, boolean en) {
        Product p = mustProduct(prodId);
        Map<String, Object> rule = safeAnalyze(prodId);
        Ctx ctx = context(p, rule, en);

        Map<String, Object> out = baseOut(p, ctx, en);
        out.put("question", question);

        JsonNode ai = callAi(askPrompt(question, ctx, en), en);
        if (ai != null) {
            out.put("source", "AI");
            out.put("answer", text(ai, "answer"));
            out.put("cause", text(ai, "cause"));
            out.put("process", text(ai, "process"));
            out.put("result", text(ai, "result"));
            out.put("outlook", text(ai, "outlook"));
            out.put("evidence", stringList(ai, "evidence"));
            out.put("model", ctx.modelName);
        } else if (en) {
            fillRuleAskEn(out, ctx, question);
        } else {
            fillRuleAsk(out, ctx, question);
        }
        return out;
    }

    private void fillRuleAsk(Map<String, Object> out, Ctx ctx, String question) {
        String direction = ctx.priceChangePct.signum() > 0 ? "上行" : ctx.priceChangePct.signum() < 0 ? "下行" : "平稳";
        String answer = "结论：该商品近30日价格整体" + direction
                + "（" + signed(ctx.priceChangePct) + "%），行业基准"
                + (ctx.benchmarkChangePct == null ? "数据不足" : signed(ctx.benchmarkChangePct) + "%")
                + "，相对强弱 " + signed(ctx.relativePct) + " 个百分点；当前 ¥" + ctx.currentPrice
                + "；主要驱动来自" + mainDriver(ctx) + "。";

        StringBuilder cause = new StringBuilder();
        cause.append("起因：");
        if (ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0) {
            cause.append("本商品近30日").append(ctx.priceChangePct.signum() > 0 ? "上调" : "下调")
                    .append(" ").append(ctx.priceChangePct.abs()).append("%，");
        }
        if (ctx.benchmarkChangePct != null && ctx.benchmarkChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0
                && (ctx.priceChangePct.signum() == 0 || ctx.benchmarkChangePct.signum() == ctx.priceChangePct.signum())) {
            cause.append("同期行业基准").append(ctx.benchmarkChangePct.signum() > 0 ? "上行" : "下行")
                    .append(" ").append(ctx.benchmarkChangePct.abs()).append("%，形成同向推动；");
        } else {
            cause.append("而行业基准")
                    .append(ctx.benchmarkChangePct == null ? "缺乏数据" : signed(ctx.benchmarkChangePct) + "%")
                    .append("，商品走势主要由自身调价与供需决定；");
        }
        if (!ctx.news.isEmpty()) {
            MarketNews n = ctx.news.get(0);
            cause.append("资讯层面，").append(n.getTitle()).append("（").append(categoryName(n.getCategory())).append("）；");
        }
        cause.append("本商品期间共调价 ").append(ctx.changeCount).append(" 次，最近一次为 ")
                .append(ctx.lastChangeText).append("。");

        StringBuilder process = new StringBuilder("经过：");
        if (ctx.recentChanges.isEmpty()) {
            process.append("近30日无调价记录，价格保持稳定。");
        } else {
            process.append("关键调价节点：").append(String.join("；", ctx.recentChanges)).append("。");
        }

        String risk = ctx.volatilityPct.compareTo(new BigDecimal("8")) > 0 || ctx.relativePct.abs().compareTo(new BigDecimal("12")) >= 0 ? "HIGH"
                : ctx.volatilityPct.compareTo(new BigDecimal("4")) > 0 || ctx.relativePct.abs().compareTo(new BigDecimal("6")) >= 0 ? "MEDIUM" : "LOW";
        String result = "结果：当前价 ¥" + ctx.currentPrice
                + "，近30日相对行业强弱 " + signed(ctx.relativePct) + " 个百分点，波动风险 " + risk + "。";

        String outlook;
        if (ctx.relativePct.compareTo(new BigDecimal("8")) >= 0) {
            outlook = "后续：走势强于行业，建议维持价格并跟踪竞品报价，防止份额被低价侵蚀。";
        } else if (ctx.relativePct.compareTo(new BigDecimal("-8")) <= 0) {
            outlook = "后续：走势弱于行业，建议核查成本、库存与竞品价格，评估是否存在需求走弱。";
        } else {
            outlook = "后续：与行业基本同步，建议保持现价并按周复查行业基准。";
        }
        if (risk.equals("HIGH")) outlook += " 波动偏高，建议开启 DROP_PCT/RISE_PCT 预警。";
        if (!ctx.news.isEmpty()) outlook += " 重点跟踪：" + ctx.news.get(0).getTitle() + "。";

        List<String> evidence = new ArrayList<>();
        evidence.add("近30日价格 " + ctx.firstPrice + " → " + ctx.currentPrice + "（" + signed(ctx.priceChangePct) + "%）");
        if (ctx.benchmarkChangePct != null) {
            evidence.add("行业基准近30日 " + signed(ctx.benchmarkChangePct) + "%，相对强弱 " + signed(ctx.relativePct) + " pct");
        }
        evidence.add("行业基准波动率 " + ctx.volatilityPct + "%");
        for (MarketNews n : ctx.news.subList(0, Math.min(2, ctx.news.size()))) {
            evidence.add(n.getPublishedAt().format(TIME_FMT) + " " + n.getSource() + "：" + n.getTitle());
        }

        out.put("source", "RULE");
        out.put("answer", answer);
        out.put("cause", cause.toString());
        out.put("process", process.toString());
        out.put("result", result);
        out.put("outlook", outlook);
        out.put("evidence", evidence);
        out.put("notice", "外部模型未配置或调用失败，已使用本地规则引擎分析");
    }

    /** 英文规则引擎问答：只引用数值与因子，不输出中文资讯标题。 */
    private void fillRuleAskEn(Map<String, Object> out, Ctx ctx, String question) {
        String move = ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) < 0
                ? "held broadly flat"
                : (ctx.priceChangePct.signum() > 0 ? "rose " + ctx.priceChangePct + "%"
                : "fell " + ctx.priceChangePct.abs() + "%");
        String benchMove = ctx.benchmarkChangePct == null ? "had no benchmark data"
                : ctx.benchmarkChangePct.abs().compareTo(new BigDecimal("0.5")) < 0 ? "was broadly flat"
                : (ctx.benchmarkChangePct.signum() > 0 ? "rose " : "fell ") + ctx.benchmarkChangePct.abs() + "%";
        String answer = "Conclusion: over the last 30 days the price " + move
                + " while the industry benchmark " + benchMove + ", leaving relative strength at "
                + signed(ctx.relativePct) + " pct; current price ¥" + ctx.currentPrice
                + ". Main driver: " + mainDriverEn(ctx) + ".";

        StringBuilder cause = new StringBuilder("Cause: ");
        if (ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0) {
            cause.append("the merchant adjusted the price ").append(ctx.priceChangePct.signum() > 0 ? "up" : "down")
                    .append(" ").append(ctx.priceChangePct.abs()).append("% through ").append(ctx.changeCount)
                    .append(" changes; ");
        } else {
            cause.append("no merchant price change was recorded in the window; ");
        }
        if (ctx.benchmarkChangePct != null) {
            cause.append("the industry benchmark ").append(benchMove).append(" over the same period; ");
        }
        Map<String, Object> dominant = dominantFactor(ctx.factorList);
        if (dominant != null) {
            cause.append("the five-factor board points to ")
                    .append(factorLabelEn(String.valueOf(dominant.get("key"))))
                    .append(" (score ").append(dominant.get("score")).append("/100, strength ")
                    .append(dominant.get("strength")).append(").");
        } else {
            cause.append("the board shows no decisive factor signal.");
        }

        List<String> steps = new ArrayList<>();
        for (int i = Math.max(0, ctx.histories.size() - 6); i < ctx.histories.size(); i++) {
            PriceHistory h = ctx.histories.get(i);
            steps.add(h.getCreateTime().format(TIME_FMT) + " ¥" + h.getOldPrice() + " -> ¥" + h.getNewPrice()
                    + " (" + signed(h.getChangePct()) + "%)");
        }
        String process = "Process: " + (steps.isEmpty()
                ? "no price changes were recorded in the last 30 days."
                : String.join("; ", steps) + ".");

        String risk = riskOf(ctx);
        String result = "Result: the current price is ¥" + ctx.currentPrice + ", relative strength is "
                + signed(ctx.relativePct) + " pct, and volatility risk is " + risk + ".";

        String outlook;
        if (ctx.relativePct.compareTo(new BigDecimal("8")) >= 0) {
            outlook = "Outlook: relative strength is well above the industry; hold the price and track competitor quotes.";
        } else if (ctx.relativePct.compareTo(new BigDecimal("-8")) <= 0) {
            outlook = "Outlook: relative strength is well below the industry; review costs, inventory and competitor pricing.";
        } else {
            outlook = "Outlook: the price is broadly in sync with the industry; hold and review the benchmark weekly.";
        }
        if (risk.equals("HIGH")) outlook += " Volatility is elevated, so consider DROP_PCT / RISE_PCT alerts.";

        List<String> evidence = new ArrayList<>();
        evidence.add("30-day price " + ctx.firstPrice + " -> " + ctx.currentPrice
                + " (" + signed(ctx.priceChangePct) + "%)");
        if (ctx.benchmarkChangePct != null) {
            evidence.add("Industry benchmark " + signed(ctx.benchmarkChangePct) + "%, relative strength "
                    + signed(ctx.relativePct) + " pct");
        }
        evidence.add("Benchmark volatility " + ctx.volatilityPct + "%");
        evidence.add("Five-factor index " + sentimentIndex(ctx.factorList) + "/100; "
                + factorSummaryEn(ctx.factorList));

        out.put("source", "RULE");
        out.put("answer", answer);
        out.put("cause", cause.toString());
        out.put("process", process);
        out.put("result", result);
        out.put("outlook", outlook);
        out.put("evidence", evidence);
        out.put("notice", "External model unavailable — the local rule engine answered instead.");
    }

    private static String mainDriverEn(Ctx ctx) {
        BigDecimal b = ctx.benchmarkChangePct == null ? BigDecimal.ZERO : ctx.benchmarkChangePct;
        boolean productSignificant = ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0;
        boolean benchSignificant = b.abs().compareTo(new BigDecimal("0.5")) >= 0;
        if (productSignificant && (!benchSignificant || ctx.priceChangePct.signum() != b.signum()
                || ctx.priceChangePct.abs().compareTo(b.abs()) >= 0)) {
            return "the merchant's own price adjustments (" + signed(ctx.priceChangePct) + "%)";
        }
        Map<String, Object> dom = dominantFactor(ctx.factorList);
        if (dom != null && Math.abs((int) dom.get("score")) >= 18) {
            return "the " + factorLabelEn(String.valueOf(dom.get("key"))) + " factor (score "
                    + dom.get("score") + "/100, strength " + dom.get("strength") + ")";
        }
        if (benchSignificant) {
            return "the industry benchmark (" + signed(b) + "%)";
        }
        return "pricing strategy and the supply-demand balance";
    }

    // ==================== 上下文 ====================

    private record Ctx(Product product, Map<String, Object> rule, List<PriceHistory> histories,
                       List<MarketNews> news, List<Map<String, Object>> factorList,
                       BigDecimal currentPrice, BigDecimal firstPrice,
                       BigDecimal priceChangePct, int changeCount, BigDecimal latestBenchmark,
                       BigDecimal firstBenchmark, BigDecimal benchmarkChangePct,
                       BigDecimal deviationPct, BigDecimal relativePct, BigDecimal volatilityPct,
                       List<String> recentChanges, String lastChangeText, String modelName,
                       String industryName, String categoryName, ProductFactorProfile profile,
                       PriceBehavior behavior) { }

    private Ctx context(Product p, Map<String, Object> rule, boolean en) {
        Map<String, Object> safeRule = rule == null ? new LinkedHashMap<>() : rule;
        BigDecimal current = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
        List<PriceHistory> hs = safeHistories(p.getProdId());
        BigDecimal first = current;
        if (!hs.isEmpty()) {
            PriceHistory h0 = hs.get(0);
            first = h0.getOldPrice() != null ? h0.getOldPrice()
                    : (h0.getNewPrice() != null ? h0.getNewPrice() : current);
        }
        BigDecimal changePct = pctChange(current, first);

        List<String> timeline = new ArrayList<>();
        for (int i = Math.max(0, hs.size() - 6); i < hs.size(); i++) {
            PriceHistory h = hs.get(i);
            timeline.add(en
                    ? h.getCreateTime().format(TIME_FMT) + " ¥" + h.getOldPrice() + " -> ¥" + h.getNewPrice()
                      + " (" + signed(h.getChangePct()) + "%)"
                    : h.getCreateTime().format(TIME_FMT) + " ¥" + h.getOldPrice() + "→¥" + h.getNewPrice()
                      + "（" + signed(h.getChangePct()) + "%）");
        }
        String lastText = hs.isEmpty() ? (en ? "no price changes" : "无调价记录")
                : hs.get(hs.size() - 1).getCreateTime().format(TIME_FMT) + " ¥"
                + hs.get(hs.size() - 1).getOldPrice() + (en ? " -> ¥" : "→¥")
                + hs.get(hs.size() - 1).getNewPrice();

        BigDecimal latest = dec(safeRule.get("benchmarkPrice"));
        BigDecimal firstBench = null;
        Object benchObj = safeRule.get("benchmarkSeries");
        if (benchObj instanceof List<?> benchSeries && !benchSeries.isEmpty()
                && benchSeries.get(0) instanceof com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark mb) {
            firstBench = mb.getBenchmark();
        }
        BigDecimal benchChange = dec(safeRule.get("benchmarkChangePct"));
        BigDecimal deviation = dec(safeRule.get("deviationPct"));
        BigDecimal volatility = dec(safeRule.get("volatilityPct"));
        if (volatility == null) volatility = BigDecimal.ZERO;
        BigDecimal relative = changePct.subtract(benchChange == null ? BigDecimal.ZERO : benchChange)
                .setScale(2, RoundingMode.HALF_UP);

        // 按商品：先算画像/价格行为/五维因子，再按商品相关度取上下文资讯（>=2 优先，最新补足）。
        String industryName = industryNameOf(p);
        String categoryName = categoryNameOf(p);
        ProductFactorProfile profile = ProductFactorProfile.of(p, industryName, categoryName);
        PriceBehavior behavior = priceBehavior(p, safeRule, hs);
        List<MarketNews> allNews = safeNews(40);
        List<Map<String, Object>> factorList = computeFactors(allNews, profile, behavior, en);
        List<MarketNews> news = relevantNews(allNews, profile, 6);
        // 展示模型名与实际调用顺序一致：优先本地 AI 网关（mimosa）渠道，其次任一启用渠道。
        String model = orderedChannels().stream().map(OutboundModelConfigService.ChannelConfig::modelName)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Ctx(p, safeRule, hs, news, factorList, current, first, changePct, hs.size(), latest, firstBench,
                benchChange, deviation, relative, volatility, timeline, lastText, model,
                industryName, categoryName, profile, behavior);
    }

    private Map<String, Object> baseOut(Product p, Ctx ctx, boolean en) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodId", p.getProdId());
        out.put("title", p.getTitle());
        out.put("industryName", ctx.industryName);
        out.put("currentPrice", ctx.currentPrice);
        out.put("priceChange30dPct", ctx.priceChangePct);
        out.put("benchmarkPrice", ctx.latestBenchmark);
        out.put("deviationPct", ctx.deviationPct);
        out.put("relativePct", ctx.relativePct);
        out.put("volatilityPct", ctx.volatilityPct);
        out.put("level", ctx.rule.get("level"));
        out.put("trend", ctx.rule);
        out.put("news", ctx.news.subList(0, Math.min(4, ctx.news.size())));
        out.put("factors", ctx.factorList);
        out.put("factorSummary", en ? factorSummaryEn(ctx.factorList) : factorSummary(ctx.factorList));
        out.put("sentimentIndex", sentimentIndex(ctx.factorList));
        out.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return out;
    }

    // ==================== AI 调用 ====================

    private String analysisPrompt(Ctx ctx, boolean en) {
        String schema = "{\"summary\":\"one-sentence conclusion\",\"drivers\":\"driver1；driver2；driver3 (plain text, separated by ；)\","
                + "\"risk\":\"HIGH|MEDIUM|LOW\",\"suggestion\":\"actionable advice\"}";
        String requirement = en
                ? "Requirement: answer in English; lead with the conclusion; combine all five dimensions "
                + "(market sentiment, upstream supply, raw materials, global factors, policy & politics, see \"five_factors\"); "
                + "cite concrete prices, percentages and news; put 2-4 drivers in the \"drivers\" string separated by ；"
                + " (do NOT use nested arrays or objects); never invent data. Translate any Chinese news titles you cite.\n"
                + "Drivers rules: each driver must be a SPECIFIC business fact about THIS product or its industry "
                + "(own price moves with numbers, a concrete news title with its direction, or the strongest factor WITH its evidence) — "
                + "NEVER list the five dimension names themselves as drivers, and never repeat the same factor twice.\nData:"
                : "要求：结论先行；必须结合市场情绪、上游供应链、基础原材料、国际化因素、政治与政策五个维度"
                + "（见数据中的“五维因子”），引用具体价格、百分比与资讯；drivers 用中文分号分隔的 2-4 条要点纯文本，不要用数组或对象；不得编造数据。\n"
                + "drivers 规则：每条必须是针对【本商品】的具体业务事实（自身调价幅度带数字、某条具体资讯标题及其方向、"
                + "或最强因子及其依据线索），禁止把“市场情绪/上游供应链”这类维度名本身当作一条 driver，同一因子不得重复出现。\nData:";
        return (en ? "You are a B2B commodity price analyst. Return strict JSON (no Markdown fences) with fields: "
                : "你是 B2B 商品价格分析师。根据给定数据输出严格 JSON（不要 Markdown 代码块），字段：")
                + schema + "。 " + requirement + contextJson(ctx, en);
    }

    private String askPrompt(String question, Ctx ctx, boolean en) {
        String schema = "{\"answer\":\"one-sentence conclusion\",\"cause\":\"cause\",\"process\":\"timeline\","
                + "\"result\":\"result and impact\",\"outlook\":\"what to watch next\",\"evidence\":[\"evidence 1\",\"evidence 2\"]}";
        String requirement = en
                ? "Requirement: clear structure; cite data and news; explain the five factors "
                + "(market sentiment, upstream supply, raw materials, global factors, policy & politics); "
                + "never invent data; answer in English and translate any Chinese news titles you cite.\nQuestion: "
                : "要求：结构清晰、引用数据与资讯；结合“五维因子”说明市场情绪、上游供应链、基础原材料、"
                + "国际化因素、政治与政策的影响；不得编造。\n问题：";
        return (en ? "You are a B2B commodity price analyst. Answer the user's question with strict JSON (no Markdown fences), fields: "
                : "你是 B2B 商品价格分析师。请回答用户关于价格变化的问题，输出严格 JSON（不要 Markdown 代码块），字段：")
                + schema + "。 " + requirement + question + "\nData: " + contextJson(ctx, en);
    }

    private String contextJson(Ctx ctx, boolean en) {
        try {
            ObjectNode root = JSON.createObjectNode();
            if (en) {
                // 英文态：上下文只用英文键、不含任何中文（商品名/资讯标题/因子名一律用英文或省略），
                // 否则中文小模型会照抄中文，破坏英文界面零中文。因子 basis 由 en=true 计算，天然全英文。
                root.put("product", "the selected product");
                root.put("current_price", ctx.currentPrice);
                root.put("first_price_30d", ctx.firstPrice);
                root.put("change_30d_pct", ctx.priceChangePct);
                root.put("price_changes", ctx.changeCount);
                root.put("benchmark_change_pct", ctx.benchmarkChangePct);
                root.put("relative_strength_pct", ctx.relativePct);
                root.put("benchmark_volatility_pct", ctx.volatilityPct);
                ObjectNode sa = root.putObject("systematic_analysis");
                sa.put("price_change_30d_pct", ctx.behavior.momentum30dPct());
                sa.put("relative_pct", ctx.behavior.relativePct());
                sa.put("volatility_pct", ctx.behavior.volatilityPct());
                sa.put("price_change_count", ctx.behavior.changeCount());
                sa.put("range_position_pct", ctx.behavior.rangePositionPct());
                sa.put("inventory_coverage_days", ctx.behavior.stockCoverageDays());
                ArrayNode ch = root.putArray("recent_price_moves");
                ctx.recentChanges.forEach(ch::add);
                ArrayNode ns = root.putArray("news_signals");
                for (MarketNews n : ctx.news.subList(0, Math.min(5, ctx.news.size()))) {
                    ns.addObject().put("category", categoryNameEn(n.getCategory()))
                            .put("hours_ago", hoursAgo(n.getPublishedAt()))
                            .put("product_relevance", ctx.profile.relevanceOf(n));
                }
                root.put("five_factor_index", sentimentIndex(ctx.factorList));
                root.put("five_factor_summary", factorSummaryEn(ctx.factorList));
                ArrayNode fs = root.putArray("five_factors");
                for (Map<String, Object> f : ctx.factorList) {
                    ObjectNode o = fs.addObject();
                    o.put("dimension", factorLabelEn(String.valueOf(f.get("key"))));
                    o.put("direction", String.valueOf(f.get("impact")));
                    o.put("score", (int) f.get("score"));
                    o.put("strength", (int) f.get("strength"));
                    o.put("news_items", (int) f.get("newsCount"));
                    o.put("product_news_items", (int) f.get("productNewsCount"));
                    o.put("news_score", (int) f.get("newsScore"));
                    o.put("behaviour_score", (int) f.get("behaviorScore"));
                    o.put("industry_weight", ((Number) f.get("weight")).doubleValue());
                    o.put("basis", String.valueOf(f.get("basis")));
                }
                return JSON.writeValueAsString(root);
            }
            root.put("商品", ctx.product.getTitle());
            root.put("当前价", ctx.currentPrice);
            root.put("行业", ctx.industryName == null ? "未知" : ctx.industryName);
            root.put("分类", ctx.categoryName == null ? "未知" : ctx.categoryName);
            root.put("近30日首价", ctx.firstPrice);
            root.put("近30日变动%", ctx.priceChangePct);
            root.put("调价次数", ctx.changeCount);
            root.put("行业基准变动%", ctx.benchmarkChangePct);
            // 只喂"相对强弱"（商品涨幅−基准涨幅，百分点）这类跨品类可比的口径；
            // 不再传"现价 vs 行业均价"的绝对偏离与规则建议——不同商品量级差异大，绝对比价会误导模型。
            root.put("相对强弱(百分点)", ctx.relativePct);
            root.put("基准波动率%", ctx.volatilityPct);
            ObjectNode sa = root.putObject("系统性分析");
            sa.put("近30日变动%", ctx.behavior.momentum30dPct());
            sa.put("相对行业百分点", ctx.behavior.relativePct());
            sa.put("基准波动率%", ctx.behavior.volatilityPct());
            sa.put("调价次数", ctx.behavior.changeCount());
            sa.put("区间位置%", ctx.behavior.rangePositionPct());
            sa.put("库存覆盖天数", ctx.behavior.stockCoverageDays());
            ArrayNode changes = root.putArray("关键调价节点");
            ctx.recentChanges.forEach(changes::add);
            ArrayNode news = root.putArray("近期资讯");
            for (MarketNews n : ctx.news.subList(0, Math.min(5, ctx.news.size()))) {
                ObjectNode o = news.addObject();
                o.put("标题", n.getTitle());
                o.put("类别", categoryName(n.getCategory()));
                o.put("来源", n.getSource());
                o.put("时间", n.getPublishedAt().format(TIME_FMT));
                o.put("摘要", n.getSummary());
                o.put("本商品相关度", ctx.profile.relevanceOf(n));
            }
            root.put("五维因子综合指数", sentimentIndex(ctx.factorList));
            root.put("五维因子结论", factorSummary(ctx.factorList));
            ArrayNode factors = root.putArray("五维因子");
            for (Map<String, Object> f : ctx.factorList) {
                ObjectNode o = factors.addObject();
                o.put("维度", String.valueOf(f.get("label")));
                o.put("方向", String.valueOf(f.get("impact")));
                o.put("评分(-100偏空~100偏多)", (int) f.get("score"));
                o.put("强度(0-100)", (int) f.get("strength"));
                o.put("资讯数", (int) f.get("newsCount"));
                o.put("本商品相关资讯数", (int) f.get("productNewsCount"));
                o.put("资讯加权分", (int) f.get("newsScore"));
                o.put("行为分", (int) f.get("behaviorScore"));
                o.put("行业权重", ((Number) f.get("weight")).doubleValue());
                o.put("依据", joinEvidence(f));
                o.put("算法依据", String.valueOf(f.get("basis")));
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode callAi(String prompt, boolean en) {
        if (System.currentTimeMillis() < aiDisabledUntil) return null;
        int attempts = 0;
        String lastRaw = null;
        for (OutboundModelConfigService.ChannelConfig ch : orderedChannels()) {
            if (attempts >= 3) break;
            attempts++;
            try {
                String url = OutboundModelConfigService.chatCompletionsEndpoint(ch.baseUrl());
                ObjectNode body = JSON.createObjectNode();
                String model = ch.modelName();
                if (model == null || model.isBlank()) model = OutboundModelConfigService.defaultModelFor(ch.channel());
                body.put("model", model);
                body.put("temperature", 0.2);
                body.put("max_tokens", 800);
                // 兼容 OpenAI JSON 模式：支持的网关会据此强制合法 JSON，不支持的会忽略该扩展字段。
                body.putObject("response_format").put("type", "json_object");
                // 本地 AI 网关扩展字段：价格分析要纯模型推理，关闭电商知识库 RAG，避免被客服问答上下文带偏。
                body.put("use_kb", false);
                var messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("content", en
                        ? "You are a rigorous B2B price analyst. Output ONLY valid JSON, and write every value in English."
                        : "你是严谨的 B2B 价格分析师，只输出合法 JSON。");
                messages.addObject().put("role", "user").put("content", prompt);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(90))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + ch.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.warn("[price-ai] provider {} HTTP {}", ch.channel(), resp.statusCode());
                    continue;
                }
                JsonNode root = JSON.readTree(resp.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                JsonNode parsed = extractJson(content);
                if (parsed != null) {
                    aiDisabledUntil = 0;
                    return parsed;
                }
                // 模型确实返回了内容但 JSON 不完整（小模型常见）：留作兜底，仍算真实 AI 调用。
                if (content != null && !content.isBlank()) lastRaw = stripFence(content);
            } catch (Exception e) {
                log.warn("[price-ai] provider {} failed: {}", ch.channel(), e.getMessage());
            }
        }
        if (lastRaw != null) {
            aiDisabledUntil = 0;
            ObjectNode node = JSON.createObjectNode();
            String clipped = lastRaw.length() > 600 ? lastRaw.substring(0, 600) + "…" : lastRaw;
            node.put("summary", clipped);
            log.info("[price-ai] 模型返回非严格 JSON，已按原文兜底为 AI 结论");
            return node;
        }
        // 全部渠道无有效返回：熔断 2 分钟，期间直接使用规则引擎（本地 Ollama 串行推理慢，
        // 一次超时不该让后续 5 分钟全部降级，故熔断窗口短于历史 5 分钟）。
        aiDisabledUntil = System.currentTimeMillis() + 2 * 60 * 1000L;
        return null;
    }

    /** 本地 AI 网关（mimosa）优先，其次其余启用渠道，避免先撞不可用的外部渠道浪费尝试次数。 */
    private List<OutboundModelConfigService.ChannelConfig> orderedChannels() {
        List<OutboundModelConfigService.ChannelConfig> list =
                new ArrayList<>(configService.activeChannels());
        list.sort((a, b) -> Integer.compare(
                "mimosa".equalsIgnoreCase(a.channel()) ? 0 : 1,
                "mimosa".equalsIgnoreCase(b.channel()) ? 0 : 1));
        return list;
    }

    /** 去掉 ```json / ``` 代码围栏与首尾空白。 */
    private static String stripFence(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private JsonNode extractJson(String content) {
        if (content == null) return null;
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = JSON.readTree(s.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) arr.forEach(x -> { if (!x.asText("").isBlank()) out.add(x.asText("")); });
        return out;
    }

    private static List<Map<String, Object>> objects(JsonNode node, String field) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (!arr.isArray()) return out;
        for (JsonNode item : arr) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", text(item, "label"));
            m.put("impact", text(item, "impact"));
            m.put("evidence", text(item, "evidence"));
            if (!String.valueOf(m.get("label")).isBlank()) out.add(m);
        }
        return out;
    }

    /**
     * 兼容模型返回的两种 drivers 形态：数组对象（[{label,impact,evidence}]）或
     * 分号/换行分隔的纯文本要点（小模型更擅长输出后者）。统一转成前端消费的 [{label,impact,evidence}]。
     */
    private static List<Map<String, Object>> driversFrom(JsonNode node, boolean en) {
        JsonNode d = node.path("drivers");
        if (d.isArray()) return objects(node, "drivers");
        List<Map<String, Object>> out = new ArrayList<>();
        if (d.isTextual()) {
            for (String part : d.asText("").split("[；;。\n]")) {
                String t = part.replaceAll("^\\s*[-·•*\\d.、)）]+\\s*", "").trim();
                if (t.isEmpty()) continue;
                String impact = t.matches(".*(涨|上行|偏多|走强|收紧|偏紧|上升|利好|紧缺|提速|回暖).*") ? "up"
                        : t.matches(".*(跌|下行|偏空|走弱|放缓|疲软|回落|宽松|利空|下降|回调).*") ? "down" : "neutral";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", t);
                m.put("impact", impact);
                m.put("evidence", "");
                out.add(m);
            }
        }
        return out;
    }

    private static Map<String, Object> driver(String label, String impact, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("impact", impact);
        m.put("evidence", evidence);
        return m;
    }

    private static String signed(BigDecimal v) {
        if (v == null) return "0.00";
        return (v.signum() > 0 ? "+" : "") + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String mainDriver(Ctx ctx) {
        BigDecimal b = ctx.benchmarkChangePct == null ? BigDecimal.ZERO : ctx.benchmarkChangePct;
        boolean productSignificant = ctx.priceChangePct.abs().compareTo(new BigDecimal("0.5")) >= 0;
        boolean benchSignificant = b.abs().compareTo(new BigDecimal("0.5")) >= 0;
        if (productSignificant && (!benchSignificant || ctx.priceChangePct.signum() != b.signum()
                || ctx.priceChangePct.abs().compareTo(b.abs()) >= 0)) {
            return "本商品自身调价（近30日" + (ctx.priceChangePct.signum() > 0 ? "上行" : "下行") + " "
                    + ctx.priceChangePct.abs() + "%）";
        }
        Map<String, Object> dom = dominantFactor(ctx.factorList);
        if (dom != null && Math.abs((int) dom.get("score")) >= 18) {
            int ds = (int) dom.get("score");
            return "五维因子中的" + dom.get("label") + "（" + (ds > 0 ? "偏多" : "偏空")
                    + "，强度 " + dom.get("strength") + "/100）";
        }
        if (benchSignificant) {
            return "行业基准" + (b.signum() > 0 ? "上行" : "下行") + " " + b.abs() + "%";
        }
        if (!ctx.news.isEmpty()) return categoryName(ctx.news.get(0).getCategory()) + "资讯（" + ctx.news.get(0).getTitle() + "）";
        return "自身调价策略与供需变化";
    }

    private static String categoryName(String c) {
        return switch (c == null ? "" : c) {
            case "policy" -> "政策";
            case "supply" -> "供给";
            case "demand" -> "需求";
            case "price" -> "价格";
            case "tech" -> "技术";
            case "international" -> "国际";
            default -> "市场";
        };
    }

    private static String categoryNameEn(String c) {
        return switch (c == null ? "" : c) {
            case "policy" -> "policy";
            case "supply" -> "supply";
            case "demand" -> "demand";
            case "price" -> "price";
            case "tech" -> "technology";
            case "international" -> "global";
            default -> "market";
        };
    }

    private static long hoursAgo(LocalDateTime t) {
        return t == null ? 0 : Math.max(0, java.time.Duration.between(t, LocalDateTime.now()).toHours());
    }

    private Product mustProduct(Long prodId) {
        Product p = prodId == null ? null : productMapper.selectById(prodId);
        if (p == null) throw new BusinessException("商品不存在");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCached(String key) {
        Cached c = cache.get(key);
        if (c == null) return null;
        if (System.currentTimeMillis() - c.at > CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return (Map<String, Object>) c.value;
    }

    private void putCached(String key, Map<String, Object> value) {
        if (cache.size() > 500) cache.clear();
        cache.put(key, new Cached(value, System.currentTimeMillis()));
    }
}

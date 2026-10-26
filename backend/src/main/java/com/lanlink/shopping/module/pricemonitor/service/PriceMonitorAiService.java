package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Product;
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
    private final MarketNewsService newsService;
    private final PriceMonitorService monitorService;
    private final OutboundModelConfigService configService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    /** 外部模型熔断：失败后 5 分钟内直接走规则引擎，避免每次请求都等待超时。 */
    private volatile long aiDisabledUntil = 0;

    private record Cached(Object value, long at) { }

    public PriceMonitorAiService(ProductMapper productMapper, PriceHistoryMapper historyMapper,
                                 IndustryMapper industryMapper, MarketNewsService newsService,
                                 PriceMonitorService monitorService, OutboundModelConfigService configService) {
        this.productMapper = productMapper;
        this.historyMapper = historyMapper;
        this.industryMapper = industryMapper;
        this.newsService = newsService;
        this.monitorService = monitorService;
        this.configService = configService;
    }

    // ==================== 五维价格因子 ====================

    /** 因子定义：市场情绪 / 上游供应链 / 基础原材料 / 国际化因素 / 政治与政策。 */
    private static final Map<String, String> FACTOR_LABELS;
    private static final Map<String, List<String>> FACTOR_KEYWORDS;
    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sentiment", "市场情绪");
        labels.put("supply", "上游供应链");
        labels.put("material", "基础原材料");
        labels.put("international", "国际化因素");
        labels.put("politics", "政治与政策");
        FACTOR_LABELS = Collections.unmodifiableMap(labels);

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

    private record ScoredNews(MarketNews news, int dir, int importance, int relevance) { }

    /** 五维因子看板：从近期资讯中量化各维度对价格的方向性压力（-100 偏空 ~ +100 偏多）。 */
    public Map<String, Object> factors(Long prodId) {
        Product p = mustProduct(prodId);
        List<MarketNews> news = newsService.latest(40);
        List<Map<String, Object>> list = computeFactors(news);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodId", p.getProdId());
        out.put("title", p.getTitle());
        out.put("industryName", p.getIndId() == null ? null :
                Optional.ofNullable(industryMapper.selectById(p.getIndId())).map(i -> i.getName()).orElse(null));
        out.put("factors", list);
        out.put("sentimentIndex", sentimentIndex(list));
        out.put("dominant", dominantFactor(list));
        out.put("summary", factorSummary(list));
        out.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        out.put("disclaimer", "因子由近期市场资讯与平台价格行为量化生成，仅作经营决策参考。");
        return out;
    }

    private List<Map<String, Object>> computeFactors(List<MarketNews> news) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : FACTOR_LABELS.entrySet()) {
            String key = e.getKey();
            List<ScoredNews> matched = new ArrayList<>();
            for (MarketNews n : news) {
                String text = textOf(n);
                if (!matchesFactor(key, n.getCategory(), text)) continue;
                int imp = n.getImportance() == null ? 1 : Math.max(1, n.getImportance());
                matched.add(new ScoredNews(n, direction(key, text), imp, relevanceFor(key, n, text)));
            }
            matched.sort((a, b) -> {
                if (a.relevance != b.relevance) return Integer.compare(b.relevance, a.relevance);
                int ca = Math.abs(a.dir * a.importance);
                int cb = Math.abs(b.dir * b.importance);
                if (ca != cb) return Integer.compare(cb, ca);
                return b.news.getPublishedAt().compareTo(a.news.getPublishedAt());
            });

            int weighted = 0;
            int impSum = 0;
            for (ScoredNews s : matched) {
                weighted += s.dir * s.importance;
                impSum += s.importance;
            }
            int count = matched.size();
            double avg = count == 0 ? 0 : (double) weighted / count;
            int score = count == 0 ? 0 : (int) Math.round(avg * 30);
            score = Math.max(-100, Math.min(100, score));
            double agreement = impSum == 0 ? 0 : Math.min(1, (double) Math.abs(weighted) / impSum);
            double coverage = Math.min(1, count / 10.0);
            boolean fresh = matched.stream().anyMatch(s -> s.news.getPublishedAt() != null
                    && s.news.getPublishedAt().isAfter(LocalDateTime.now().minusDays(3)));
            int strength = count == 0 ? 12
                    : (int) Math.min(96, Math.round(20 + coverage * 40 + agreement * 35 + (fresh ? 6 : 0)));
            String impact = score >= 18 ? "up" : score <= -18 ? "down" : "neutral";

            List<String> evidence = new ArrayList<>();
            List<Map<String, Object>> newsRefs = new ArrayList<>();
            for (int i = 0; i < Math.min(2, matched.size()); i++) {
                MarketNews n = matched.get(i).news;
                int dir = matched.get(i).dir;
                String tag = dir > 0 ? "偏多" : dir < 0 ? "偏空" : "中性";
                evidence.add(n.getTitle() + "（" + n.getSource() + "，" + tag + "）");
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("title", n.getTitle());
                ref.put("source", n.getSource());
                ref.put("impact", dir > 0 ? "up" : dir < 0 ? "down" : "neutral");
                ref.put("publishedAt", n.getPublishedAt() == null ? null : n.getPublishedAt().format(TIME_FMT));
                newsRefs.add(ref);
            }
            if (evidence.isEmpty()) evidence.add("近期资讯中未发现该维度的直接信号，暂按中性处理。");

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("key", key);
            f.put("label", e.getValue());
            f.put("impact", impact);
            f.put("score", score);
            f.put("strength", strength);
            f.put("newsCount", count);
            f.put("headline", matched.isEmpty() ? null : matched.get(0).news.getTitle());
            f.put("evidence", evidence);
            f.put("newsRefs", newsRefs);
            f.put("updatedAt", matched.stream().map(ScoredNews::news).map(MarketNews::getPublishedAt)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo)
                    .map(t -> t.format(TIME_FMT)).orElse(null));
            out.add(f);
        }
        return out;
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

    // ==================== 对外 ====================

    public Map<String, Object> analysis(Long prodId) {
        return analysis(prodId, "zh");
    }

    public Map<String, Object> analysis(Long prodId, String lang) {
        boolean en = isEnglish(lang);
        String key = "A:" + prodId + ":" + (en ? "en" : "zh");
        Map<String, Object> cached = getCached(key);
        if (cached != null) return cached;
        Map<String, Object> out = doAnalysis(prodId, en);
        putCached(key, out);
        return out;
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
        Map<String, Object> cached = getCached(key);
        if (cached != null) return cached;
        Map<String, Object> out = doAsk(prodId, q, en);
        putCached(key, out);
        return out;
    }

    private static boolean isEnglish(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
    }

    // ==================== 分析 ====================

    private Map<String, Object> doAnalysis(Long prodId, boolean en) {
        Product p = mustProduct(prodId);
        Map<String, Object> rule = monitorService.analyze(prodId);
        Ctx ctx = context(p, rule);

        Map<String, Object> out = baseOut(p, ctx);
        JsonNode ai = callAi(analysisPrompt(ctx, en));
        if (ai != null) {
            out.put("source", "AI");
            out.put("summary", text(ai, "summary"));
            out.put("drivers", objects(ai, "drivers"));
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
        Map<String, Object> rule = monitorService.analyze(prodId);
        Ctx ctx = context(p, rule);

        Map<String, Object> out = baseOut(p, ctx);
        out.put("question", question);

        JsonNode ai = callAi(askPrompt(question, ctx, en));
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
                       List<String> recentChanges, String lastChangeText, String modelName) { }

    @SuppressWarnings("unchecked")
    private Ctx context(Product p, Map<String, Object> rule) {
        BigDecimal current = p.getPrice() == null ? BigDecimal.ZERO : p.getPrice();
        List<PriceHistory> hs = historyMapper.selectList(Wrappers.<PriceHistory>lambdaQuery()
                .eq(PriceHistory::getProdId, p.getProdId())
                .ge(PriceHistory::getCreateTime, LocalDateTime.now().minusDays(30))
                .orderByAsc(PriceHistory::getCreateTime));
        BigDecimal first = current;
        if (!hs.isEmpty()) {
            PriceHistory h0 = hs.get(0);
            first = h0.getOldPrice() != null ? h0.getOldPrice()
                    : (h0.getNewPrice() != null ? h0.getNewPrice() : current);
        }
        BigDecimal changePct = first.signum() <= 0 ? BigDecimal.ZERO
                : current.subtract(first).multiply(BigDecimal.valueOf(100))
                .divide(first, 2, RoundingMode.HALF_UP);

        List<String> timeline = new ArrayList<>();
        for (int i = Math.max(0, hs.size() - 6); i < hs.size(); i++) {
            PriceHistory h = hs.get(i);
            timeline.add(h.getCreateTime().format(TIME_FMT) + " ¥" + h.getOldPrice() + "→¥" + h.getNewPrice()
                    + "（" + signed(h.getChangePct()) + "%）");
        }
        String lastText = hs.isEmpty() ? "无调价记录"
                : hs.get(hs.size() - 1).getCreateTime().format(TIME_FMT) + " ¥"
                + hs.get(hs.size() - 1).getOldPrice() + "→¥" + hs.get(hs.size() - 1).getNewPrice();

        BigDecimal latest = rule.get("benchmarkPrice") == null ? null : new BigDecimal(String.valueOf(rule.get("benchmarkPrice")));
        BigDecimal firstBench = null;
        Object benchObj = rule.get("benchmarkSeries");
        if (benchObj instanceof List<?> benchSeries && !benchSeries.isEmpty()
                && benchSeries.get(0) instanceof com.lanlink.shopping.module.pricemonitor.entity.MarketBenchmark mb) {
            firstBench = mb.getBenchmark();
        }
        BigDecimal benchChange = rule.get("benchmarkChangePct") == null ? null : new BigDecimal(String.valueOf(rule.get("benchmarkChangePct")));
        BigDecimal deviation = rule.get("deviationPct") == null ? null : new BigDecimal(String.valueOf(rule.get("deviationPct")));
        BigDecimal volatility = rule.get("volatilityPct") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(rule.get("volatilityPct")));
        BigDecimal relative = changePct.subtract(benchChange == null ? BigDecimal.ZERO : benchChange).setScale(2, RoundingMode.HALF_UP);

        List<MarketNews> news = newsService.latest(6);
        List<Map<String, Object>> factorList = computeFactors(newsService.latest(40));
        String model = configService.activeChannels().stream().map(OutboundModelConfigService.ChannelConfig::modelName)
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Ctx(p, rule, hs, news, factorList, current, first, changePct, hs.size(), latest, firstBench,
                benchChange, deviation, relative, volatility, timeline, lastText, model);
    }

    private Map<String, Object> baseOut(Product p, Ctx ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodId", p.getProdId());
        out.put("title", p.getTitle());
        out.put("industryName", p.getIndId() == null ? null :
                Optional.ofNullable(industryMapper.selectById(p.getIndId())).map(i -> i.getName()).orElse(null));
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
        out.put("factorSummary", factorSummary(ctx.factorList));
        out.put("sentimentIndex", sentimentIndex(ctx.factorList));
        out.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return out;
    }

    // ==================== AI 调用 ====================

    private String analysisPrompt(Ctx ctx, boolean en) {
        String schema = "{\"summary\":\"one-sentence conclusion\",\"drivers\":[{\"label\":\"driver\",\"impact\":\"up|down|neutral\",\"evidence\":\"data or news basis\"}],"
                + "\"risk\":\"HIGH|MEDIUM|LOW\",\"suggestion\":\"actionable advice\"}";
        String requirement = en
                ? "Requirement: answer in English; lead with the conclusion; combine all five dimensions "
                + "(market sentiment, upstream supply, raw materials, global factors, policy & politics, see \"五维因子\"); "
                + "cite concrete prices, percentages and news; 2-4 drivers; never invent data. Translate any Chinese news titles you cite.\nData:"
                : "要求：结论先行；必须结合市场情绪、上游供应链、基础原材料、国际化因素、政治与政策五个维度"
                + "（数据中的“五维因子”），引用具体价格、百分比与资讯；drivers 2-4 条；不得编造数据。\n数据：";
        return (en ? "You are a B2B commodity price analyst. Return strict JSON (no Markdown fences) with fields: "
                : "你是 B2B 商品价格分析师。根据给定数据输出严格 JSON（不要 Markdown 代码块），字段：")
                + schema + "。 " + requirement + contextJson(ctx);
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
                + schema + "。 " + requirement + question + "\nData: " + contextJson(ctx);
    }

    private String contextJson(Ctx ctx) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("商品", ctx.product.getTitle());
            root.put("当前价", ctx.currentPrice);
            root.put("行业", ctx.product.getIndId() == null ? "未知" :
                    Optional.ofNullable(industryMapper.selectById(ctx.product.getIndId())).map(i -> i.getName()).orElse("未知"));
            root.put("近30日首价", ctx.firstPrice);
            root.put("近30日变动%", ctx.priceChangePct);
            root.put("调价次数", ctx.changeCount);
            root.put("行业基准", ctx.latestBenchmark);
            root.put("基准变动%", ctx.benchmarkChangePct);
            root.put("偏离基准%", ctx.deviationPct);
            root.put("基准波动率%", ctx.volatilityPct);
            root.put("规则建议等级", String.valueOf(ctx.rule.get("level")));
            root.put("规则建议", String.valueOf(ctx.rule.get("advice")));
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
                o.put("依据", joinEvidence(f));
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private JsonNode callAi(String prompt) {
        if (System.currentTimeMillis() < aiDisabledUntil) return null;
        int attempts = 0;
        for (OutboundModelConfigService.ChannelConfig ch : configService.activeChannels()) {
            if (attempts >= 2) break;
            attempts++;
            try {
                String url = OutboundModelConfigService.chatCompletionsEndpoint(ch.baseUrl());
                ObjectNode body = JSON.createObjectNode();
                String model = ch.modelName();
                if (model == null || model.isBlank()) model = OutboundModelConfigService.defaultModelFor(ch.channel());
                body.put("model", model);
                body.put("temperature", 0.2);
                body.put("max_tokens", 800);
                var messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("content", "你是严谨的 B2B 价格分析师，只输出 JSON。");
                messages.addObject().put("role", "user").put("content", prompt);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
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
            } catch (Exception e) {
                log.warn("[price-ai] provider {} failed: {}", ch.channel(), e.getMessage());
            }
        }
        // 全部失败：熔断 5 分钟，期间直接使用规则引擎
        aiDisabledUntil = System.currentTimeMillis() + 5 * 60 * 1000L;
        return null;
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
            default -> "市场";
        };
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

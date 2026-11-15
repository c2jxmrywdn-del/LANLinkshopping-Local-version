package com.lanlink.shopping.module.pricemonitor.service;

import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品因子画像（纯计算、无状态、无外部依赖，可单测）。
 *
 * <p>把商品标题/品牌/规格/分类/行业翻译成「五维价格因子」按商品计算所需的三样东西：
 * <ul>
 *   <li>{@link #productKeywords()}：商品自身关键词（命中即视为「与本商品直接相关」）；</li>
 *   <li>{@link #industryKeywords()}：行业关键词（命中即视为「同行业相关」）；</li>
 *   <li>{@link #factorWeights()}：本行业对五个维度的价格暴露度（0.8~1.6，静态表）。</li>
 * </ul>
 * 并提供 {@link #relevanceOf(MarketNews)} 资讯相关性打级，用于把「全局市场资讯」降噪成
 * 「本商品相关资讯」——这是不同商品因子输出不再雷同的关键。
 */
public final class ProductFactorProfile {

    /** 五维顺序固定：市场情绪 / 上游供应链 / 基础原材料 / 国际化因素 / 政治与政策。 */
    public static final List<String> FACTOR_KEYS =
            List.of("sentiment", "supply", "material", "international", "politics");

    /** 标题/规格切词：空白与常见分隔符（保留 * × . - Φ 等规格符号，避免把 40*40 拆散）。 */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s/、,，;；|·]+");
    /** 中文连续片段（"螺纹钢（HRB400）" → 螺纹钢 / HRB400）。 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern TRIM_PUNCT = Pattern.compile("^[\\p{Punct}]+|[\\p{Punct}]+$");
    private static final Pattern INDUSTRY_SUFFIX = Pattern.compile("(行业|产业|业)$");

    /** 行业分组编码（按 indId 映射；未收录时再按行业名回退）。 */
    private static final Map<Long, String> GROUP_BY_IND_ID;

    /** 行业分组 → 行业词典。覆盖 1 建筑 / 2 纺织 / 3 石化 / 4 电子，另含 5 钢铁冶金 /
     *  6 机械装备 / 8 电力能源 / 11 包装印刷 / 13 新能源 基本词。 */
    private static final Map<String, List<String>> GROUP_KEYWORDS;

    /** 行业分组 → 五维暴露度权重（sentiment≈1.0；supply 建筑/石化/电子 1.3；
     *  material 建筑/钢铁/石化 1.4；international 电子/纺织/新能源/包装 1.3~1.5；
     *  politics 建筑/石化/钢铁/能源 1.3~1.5；未收录 1.0）。 */
    private static final Map<String, Map<String, Double>> GROUP_WEIGHTS;

    /**
     * 过宽的行业大类词：出现在大量社会/股市/科技新闻里，但不代表该商品受影响。
     *
     * <p>「建筑」→ 农民工就业报道、「能源」→ 芯片算力稿、「电子」→ 手机评测，
     * 这些命中只能算弱相关，不能作为「本商品直接证据」。
     */
    static final Set<String> BROAD_INDUSTRY_TERMS = Set.of(
            "建筑", "建筑行业", "建材", "能源", "电力能源", "电力", "电子", "电子行业",
            "机械", "机械装备", "化工", "石化", "纺织", "包装", "印刷", "新能源",
            "钢铁", "钢铁冶金", "汽车", "汽车及零部件", "物流", "物流仓储",
            "食品", "食品饮料", "医药", "医药医疗", "环保", "环保水务", "农业",
            "家居", "家居家装", "工业", "制造业", "行业", "产业");

    /**
     * 允许作为「具体品类」参与强相关的两字词白名单。
     *
     * <p>两字词做子串匹配极易被更长词组劫持：「换电网络」含「电网」、
     * 「充电桩」含「充电」，会把换电站、充电桩新闻误判成变压器商品的直接证据。
     * 因此两字词只有在白名单内（真正的品类名）才参与判定，其余一律只当弱信号。
     */
    private static final Set<String> STRONG_TWO_CHAR = Set.of(
            "水泥", "钢材", "螺纹", "生铁", "焦炭", "纯碱", "塑料", "树脂", "涤纶", "坯布",
            "纱线", "面料", "织造", "玻璃", "纸浆", "瓦楞", "芯片", "面板", "晶圆", "封装",
            "轴承", "齿轮", "液压", "机床", "电缆", "变压", "光伏", "电池", "储能", "逆变",
            "钢", "铜", "铝", "锂", "锡", "镍", "锌", "煤", "油", "硅", "铁", "纸", "纱", "布");

    static {
        Map<Long, String> byId = new LinkedHashMap<>();
        byId.put(1L, "building");
        byId.put(2L, "textile");
        byId.put(3L, "chemical");
        byId.put(4L, "electronics");
        byId.put(5L, "steel");
        byId.put(6L, "machinery");
        byId.put(8L, "energy");
        byId.put(11L, "packaging");
        byId.put(13L, "newenergy");
        GROUP_BY_IND_ID = Collections.unmodifiableMap(byId);

        Map<String, List<String>> kw = new LinkedHashMap<>();
        kw.put("building", List.of("建材", "钢材", "水泥", "螺纹钢", "基建", "房地产", "工地",
                "错峰生产", "环保督察", "管材", "混凝土"));
        kw.put("textile", List.of("纺织", "坯布", "纱线", "涤纶", "棉", "面料", "织造", "补库"));
        kw.put("chemical", List.of("化工", "纯碱", "树脂", "塑料", "原油", "油价", "液压油",
                "润滑油", "基础化工"));
        kw.put("electronics", List.of("电子", "元器件", "芯片", "面板", "MLCC", "电阻", "连接器",
                "交期", "消费电子", "工控"));
        kw.put("steel", List.of("钢铁", "钢材", "钢坯", "铁矿石", "冶炼", "热轧", "冷轧", "螺纹钢",
                "高炉", "焦炭"));
        kw.put("machinery", List.of("机械", "装备", "机床", "工程机械", "零部件", "液压件", "轴承",
                "齿轮", "泵阀", "排产"));
        kw.put("energy", List.of("电力", "能源", "用电", "电价", "发电", "电网", "高耗能"));
        kw.put("packaging", List.of("包装", "纸浆", "瓦楞纸", "箱板纸", "印刷", "包装材料"));
        kw.put("newenergy", List.of("新能源", "光伏", "组件", "电池", "碳酸锂", "逆变器", "储能",
                "分布式"));
        GROUP_KEYWORDS = Collections.unmodifiableMap(kw);

        Map<String, Map<String, Double>> weights = new LinkedHashMap<>();
        weights.put("building", weights(1.00, 1.30, 1.40, 1.10, 1.40));
        weights.put("textile", weights(1.05, 1.15, 1.25, 1.40, 1.05));
        weights.put("chemical", weights(1.00, 1.30, 1.40, 1.20, 1.35));
        weights.put("electronics", weights(1.10, 1.30, 1.20, 1.50, 1.15));
        weights.put("steel", weights(1.05, 1.25, 1.40, 1.25, 1.40));
        weights.put("machinery", weights(1.05, 1.20, 1.30, 1.25, 1.10));
        weights.put("energy", weights(1.00, 1.25, 1.35, 1.25, 1.50));
        weights.put("packaging", weights(1.05, 1.20, 1.35, 1.30, 1.10));
        weights.put("newenergy", weights(1.05, 1.25, 1.35, 1.45, 1.40));
        GROUP_WEIGHTS = Collections.unmodifiableMap(weights);
    }

    private static Map<String, Double> weights(double sentiment, double supply, double material,
                                               double international, double politics) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("sentiment", sentiment);
        m.put("supply", supply);
        m.put("material", material);
        m.put("international", international);
        m.put("politics", politics);
        return Collections.unmodifiableMap(m);
    }

    private final List<String> productKeywords;
    private final List<String> industryKeywords;
    private final Map<String, Double> factorWeights;
    private final String categoryName;
    private final String industryGroup;
    private final List<String> productKeywordsLower;
    private final List<String> industryKeywordsLower;

    private ProductFactorProfile(List<String> productKeywords, List<String> industryKeywords,
                                 Map<String, Double> factorWeights, String categoryName,
                                 String industryGroup) {
        this.productKeywords = List.copyOf(productKeywords);
        this.industryKeywords = List.copyOf(industryKeywords);
        this.factorWeights = Collections.unmodifiableMap(new LinkedHashMap<>(factorWeights));
        this.categoryName = categoryName == null ? "" : categoryName.trim();
        this.industryGroup = industryGroup;
        this.productKeywordsLower = lower(this.productKeywords);
        this.industryKeywordsLower = lower(this.industryKeywords);
    }

    /**
     * 由商品 + 行业名 + 分类名构建画像。三处入参都可能为 null（历史脏数据/未登录场景），
     * 全部按空处理，保证纯计算不抛异常。
     */
    public static ProductFactorProfile of(Product p, String industryName, String categoryName) {
        String group = resolveGroup(p == null ? null : p.getIndId(), industryName);
        List<String> productKeywords = productKeywords(p, categoryName, industryName);
        List<String> industryKeywords = industryKeywords(industryName, group);
        Map<String, Double> weights = GROUP_WEIGHTS.getOrDefault(group, weights(1, 1, 1, 1, 1));
        return new ProductFactorProfile(productKeywords, industryKeywords, weights, categoryName, group);
    }

    /** 商品自身关键词：标题/品牌/规格切词 + 中文连续片段 + 分类名 + 行业名核心词。 */
    private static List<String> productKeywords(Product p, String categoryName, String industryName) {
        Set<String> out = new LinkedHashSet<>();
        if (p != null) {
            addTokens(out, p.getTitle());
            addTokens(out, p.getBrand());
            addTokens(out, p.getSpec());
        }
        addTokens(out, categoryName);
        // 注意：行业名核心词（"建筑行业" → "建筑"）只进 industryKeywords，不进商品关键词。
        // 历史缺陷：把「建筑」当成商品自身关键词后，任何提到"建筑"的社会新闻
        // （如农民工就业报道）都会拿到 3.0 强相关，被当成该商品的直接证据。
        return new ArrayList<>(out);
    }

    /** 行业关键词：行业名核心词 + 静态行业词典（按 indId / 行业名分组）。 */
    private static List<String> industryKeywords(String industryName, String group) {
        Set<String> out = new LinkedHashSet<>();
        String core = industryCore(industryName);
        if (!core.isEmpty()) out.add(core);
        String name = industryName == null ? "" : industryName.trim();
        if (!name.isEmpty()) out.add(name);
        out.addAll(GROUP_KEYWORDS.getOrDefault(group, List.of()));
        return new ArrayList<>(out);
    }

    private static String industryCore(String industryName) {
        if (industryName == null) return "";
        String core = INDUSTRY_SUFFIX.matcher(industryName.trim()).replaceAll("").trim();
        // 「建筑」这类行业大类名过宽：命中即判同行业会把大量社会新闻算成相关。
        // 保留在行业词典里（仍可作为弱信号），但需要 >= 3 字才算独立核心词。
        return core.length() >= 3 ? core : "";
    }

    /** 切词并把「完整词 + 中文连续片段」加入集合；过短或纯符号片段丢弃。 */
    private static void addTokens(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String part : TOKEN_SPLIT.split(raw)) {
            String token = part == null ? "" : part.trim();
            if (token.isEmpty()) continue;
            Matcher m = CJK_RUN.matcher(token);
            while (m.find()) {
                String run = m.group();
                if (run.length() >= 2) out.add(run);
            }
            String cleaned = TRIM_PUNCT.matcher(token).replaceAll("").trim();
            // 纯数字片段（"10"、"5000"）不是商品语义，命中率极高但零区分度：
            // 会把「9 核 16 线程」之类的芯片新闻误判成变压器商品的强相关资讯。
            if (cleaned.length() >= 2 && !cleaned.matches("[0-9.]+")) out.add(cleaned);
        }
    }

    /** 按 indId 优先、行业名回退确定行业分组；未收录返回 generic（权重全 1.0）。 */
    private static String resolveGroup(Long indId, String industryName) {
        if (indId != null) {
            String g = GROUP_BY_IND_ID.get(indId);
            if (g != null) return g;
        }
        String name = industryName == null ? "" : industryName;
        // 先匹配更具体的新能源，避免被「能源」截胡
        if (name.contains("新能源")) return "newenergy";
        if (name.contains("建筑") || name.contains("建材")) return "building";
        if (name.contains("纺织")) return "textile";
        if (name.contains("石化") || name.contains("化工")) return "chemical";
        if (name.contains("电子")) return "electronics";
        if (name.contains("钢铁") || name.contains("冶金")) return "steel";
        if (name.contains("机械")) return "machinery";
        if (name.contains("电力") || name.contains("能源")) return "energy";
        if (name.contains("包装") || name.contains("印刷")) return "packaging";
        return "generic";
    }

    private static List<String> lower(List<String> source) {
        List<String> out = new ArrayList<>(source.size());
        for (String s : source) {
            if (s != null && !s.isBlank()) out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ==================== 对外 ====================

    /** 商品自身关键词（原始大小写，用于展示）。 */
    public List<String> productKeywords() {
        return productKeywords;
    }

    /** 行业关键词（原始大小写，用于展示）。 */
    public List<String> industryKeywords() {
        return industryKeywords;
    }

    /** 五维暴露度，键顺序固定 sentiment/supply/material/international/politics。 */
    public Map<String, Double> factorWeights() {
        return factorWeights;
    }

    public String categoryName() {
        return categoryName;
    }

    /** 行业分组编码（building/textile/chemical/electronics/steel/machinery/energy/packaging/newenergy/generic）。 */
    public String industryGroup() {
        return industryGroup;
    }

    /** 因子权重，未收录维度按 1.0；对越界值做 0.8~1.6 兜底裁剪。 */
    public double weightOf(String factorKey) {
        if (factorKey == null) return 1.0;
        double w = factorWeights.getOrDefault(factorKey, 1.0);
        return Math.max(0.8, Math.min(1.6, w));
    }

    /**
     * 资讯与商品的相关性打级（越大越相关）：
     * <ul>
     *   <li>3.0：标题/摘要/标签命中商品自身关键词（商品相关，直接可用）；</li>
     *   <li>2.0：命中本行业关键词（同行业相关）；</li>
     *   <li>1.0：泛市场资讯（宏观/平台/汇率/物流等）；</li>
     *   <li>0.4：强命中其他行业词典且与本行业无关（降噪，避免"织造新闻拉高钢材因子"）。</li>
     * </ul>
     */
    public double relevanceOf(MarketNews n) {
        if (n == null) return 1.0;
        String text = textOf(n);
        if (text.isEmpty()) return 1.0;
        String lower = text.toLowerCase(Locale.ROOT);
        // 商品自身关键词：必须命中「具体品类/型号」，不能只命中行业大类词
        if (hitsAny(lower, specificProductKeywords())) return 3.0;
        // 同行业：排除「建筑/能源/电子」等过宽词；且必须是标题命中，或正文命中 ≥2 个不同品类词。
        // 只靠正文顺带提一句「钢材」的欧盟汽车排放稿，不算钢材/水泥商品的行业证据。
        String titleLower = (n.getTitle() == null ? "" : n.getTitle()).toLowerCase(Locale.ROOT);
        if (hitsAny(titleLower, specificIndustryKeywords())) return 2.0;
        if (countHits(lower, specificIndustryKeywords()) >= 2) return 2.0;
        if (hitsOtherIndustry(lower)) return 0.4;
        return 1.0;
    }

    /** 商品关键词去掉行业大类词后的具体词（品类/型号/规格/品牌）。 */
    private List<String> specificProductKeywords() {
        List<String> out = new ArrayList<>();
        for (String k : productKeywordsLower) {
            if (isStrongTerm(k)) out.add(k);
        }
        return out;
    }

    /** 行业关键词去掉大类词后的具体词（水泥/螺纹钢/变压器/涤纶…）。 */
    private List<String> specificIndustryKeywords() {
        List<String> out = new ArrayList<>();
        for (String k : industryKeywordsLower) {
            if (isStrongTerm(k)) out.add(k);
        }
        return out;
    }

    /**
     * 是否可作为「具体品类」参与强相关判定。
     *
     * <ul>
     *   <li>行业大类词（建筑/能源/电力/电子…）→ 否，只能算弱信号；</li>
     *   <li>≥3 字（硅酸盐水泥/油浸式变压器/螺纹钢）→ 是，语义足够具体；</li>
     *   <li>2 字 → 仅白名单内的真实品类名算（避免「换电网络」命中「电网」）；</li>
     *   <li>1 字 → 仅大宗品类单字算（钢/铜/铝/锂…）。</li>
     * </ul>
     */
    private static boolean isStrongTerm(String term) {
        if (term == null || term.isEmpty()) return false;
        if (BROAD_INDUSTRY_TERMS.contains(term)) return false;
        if (term.length() >= 3) return true;
        return STRONG_TWO_CHAR.contains(term);
    }

    /** 标题 + 摘要 + 标签（不含 category 归类字段：归类是维度口径，不是内容）。 */
    private static String textOf(MarketNews n) {
        return (n.getTitle() == null ? "" : n.getTitle()) + " "
                + (n.getSummary() == null ? "" : n.getSummary()) + " "
                + (n.getTags() == null ? "" : n.getTags());
    }

    private static boolean hitsAny(String lowerText, List<String> keywords) {
        for (String k : keywords) {
            if (k.length() >= 2 && lowerText.contains(k)) return true;
        }
        return false;
    }

    /** 命中的不同关键词个数（用于「正文顺带提及」与「真正的行业信号」区分）。 */
    private static int countHits(String lowerText, List<String> keywords) {
        int hits = 0;
        for (String k : keywords) {
            if (k.length() >= 2 && lowerText.contains(k)) hits++;
        }
        return hits;
    }

    /** 其他行业词典命中 >= 2 个关键词视为「强命中」（标签里通常同时出现品种与下游，2 个起步才可靠）。 */
    private boolean hitsOtherIndustry(String lowerText) {
        for (Map.Entry<String, List<String>> e : GROUP_KEYWORDS.entrySet()) {
            if (e.getKey().equals(industryGroup)) continue;
            int hits = 0;
            for (String k : e.getValue()) {
                if (k.length() >= 2 && lowerText.contains(k)) {
                    hits++;
                    if (hits >= 2) return true;
                }
            }
        }
        return false;
    }
}

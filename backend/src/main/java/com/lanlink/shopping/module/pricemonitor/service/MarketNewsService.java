package com.lanlink.shopping.module.pricemonitor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.mapper.MarketNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 市场资讯读取（价格情报台信息流 + AI 分析上下文）+ 外部抓取写入。 */
@Service
public class MarketNewsService {

    private static final Logger log = LoggerFactory.getLogger(MarketNewsService.class);

    private final MarketNewsMapper mapper;
    private final NewsLinkService linkService;
    /** 在售商品词表（可选依赖，单测环境不注入）：读取侧与写入侧共用同一相关性口径。 */
    private ProductCatalogTerms catalogTerms;

    public MarketNewsService(MarketNewsMapper mapper, NewsLinkService linkService) {
        this.mapper = mapper;
        this.linkService = linkService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCatalogTerms(ProductCatalogTerms catalogTerms) {
        this.catalogTerms = catalogTerms;
    }

    /** 读取侧时效窗（天）：首页只展示近 N 天资讯，避免多年前的旧闻占据版面。 */
    private static final int READ_MAX_AGE_DAYS = 7;
    /** 近 7 天无数据时的放宽窗（全新库/抓取刚恢复），仍不返回无上限的历史数据。 */
    private static final int READ_FALLBACK_AGE_DAYS = 30;

    public List<MarketNews> list(String category, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return mapper.selectList(Wrappers.<MarketNews>lambdaQuery()
                .eq(category != null && !category.isBlank(), MarketNews::getCategory, category)
                .orderByDesc(MarketNews::getPublishedAt)
                .last("LIMIT " + size));
    }

    public List<MarketNews> latest(int limit) {
        return list(null, limit);
    }

    /**
     * 按商品关键词定向取资讯（价格情报台五维因子使用）。
     *
     * <p>历史缺陷：因子看板只取 {@link #latest(int)} 的全局最新 N 条，热门宏观稿
     * （油价、美元指数、股市收盘）会把版面占满，水泥/钢材等具体品类一条都进不了候选集，
     * 前端因此长期显示「近期资讯中没有该商品的直接信号」。
     *
     * <p>这里改为把商品词直接下推 SQL（title/summary 的 OR LIKE），与全局最新资讯合并去重，
     * 保证每个商品都能拿到「与自己有关」的资讯。
     *
     * @param keywords 商品/行业关键词（调用方负责排序与截断，建议 ≤ 12 个）
     * @param limit    最多返回条数
     */
    public List<MarketNews> relatedTo(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return List.of();
        int size = Math.min(Math.max(limit, 1), 50);
        List<String> terms = new ArrayList<>();
        Set<String> seenTerm = new HashSet<>();
        for (String raw : keywords) {
            if (raw == null) continue;
            String term = raw.trim();
            // 过短关键词（1 字）下推 SQL 会命中过多无关稿，交由内存相关性判定兜底
            if (term.length() < 2 || !seenTerm.add(term)) continue;
            terms.add(term);
            if (terms.size() >= 12) break;
        }
        if (terms.isEmpty()) return List.of();
        try {
            LambdaQueryWrapper<MarketNews> query = Wrappers.<MarketNews>lambdaQuery()
                    .ge(MarketNews::getPublishedAt, LocalDateTime.now().minusDays(READ_FALLBACK_AGE_DAYS));
            query.and(w -> {
                for (int i = 0; i < terms.size(); i++) {
                    String term = terms.get(i);
                    if (i > 0) w.or();
                    w.like(MarketNews::getTitle, term).or().like(MarketNews::getSummary, term);
                }
            });
            query.orderByDesc(MarketNews::getPublishedAt).last("LIMIT " + size);
            List<MarketNews> rows = mapper.selectList(query);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("[market-news] relatedTo failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 外部资讯自动抓取写入（ExternalInfoFetchService 使用） ====================

    /**
     * 标题查重（对应唯一键 {@code uk_news_title}）。外部 RSS 抓取入库前的幂等判断：
     * 同一 title 已存在则跳过，避免重复灌入同一篇资讯。
     */
    public boolean existsByTitle(String title) {
        if (title == null || title.isBlank()) return false;
        try {
            Long count = mapper.selectCount(Wrappers.<MarketNews>lambdaQuery()
                    .eq(MarketNews::getTitle, title.trim()));
            return count != null && count > 0;
        } catch (Exception e) {
            // 查询失败时按"不存在"继续，最终由唯一键兜底（saveExternal 捕获冲突返回 false）
            log.warn("[market-news] existsByTitle failed, fallback insert: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 最近已上架标题，供外部抓取做归一化去重（转载、加标点、前后缀不同但正文相同）。
     * 查询失败返回空列表，不阻断上架。
     */
    public List<String> recentTitles(int limit) {
        int size = Math.min(Math.max(limit, 1), 300);
        try {
            List<MarketNews> rows = mapper.selectList(Wrappers.<MarketNews>lambdaQuery()
                    .select(MarketNews::getTitle)
                    .orderByDesc(MarketNews::getPublishedAt)
                    .last("LIMIT " + size));
            List<String> titles = new ArrayList<>();
            if (rows != null) {
                for (MarketNews row : rows) {
                    if (row != null && row.getTitle() != null && !row.getTitle().isBlank()) {
                        titles.add(row.getTitle());
                    }
                }
            }
            return titles;
        } catch (Exception e) {
            log.warn("[market-news] recentTitles failed, skip near-duplicate scan: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 写入一条外部抓取资讯（外部 RSS → t_market_news）。
     *
     * <p>与既有信息流读取方法（{@link #list}/{@link #latest}/{@link #feed}）完全解耦：
     * 唯一键冲突（并发/重跑/多实例）不抛异常，返回 false 交由调用方计入"跳过"。
     */
    public boolean saveExternal(MarketNews news) {
        if (news == null || news.getTitle() == null || news.getTitle().isBlank()) return false;
        try {
            return mapper.insert(news) > 0;
        } catch (DataIntegrityViolationException e) {
            // uk_news_title 唯一键冲突：已抓过（并发/多实例），静默跳过
            log.debug("[market-news] duplicate external title skipped: {}", news.getTitle());
            return false;
        }
    }

    /**
     * 每日资讯信息流：在 {@link #list(String, int)} 的过滤与条数规则之上，
     * 为每条资讯解析「信息源头网页」链接（url/sourceUrl/searchUrl/linkType）。
     *
     * <p>与 {@link #list} / {@link #latest} 的区别：这里返回前端可直接消费的 Map，
     * 链接字段不写入实体，避免污染 AI 知识库 ingest 的既有契约。
     */
    public List<Map<String, Object>> feed(String category, int limit) {
        List<MarketNews> rows = list(category, limit);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (MarketNews news : rows) {
            items.add(toFeedItem(news));
        }
        return items;
    }

    /**
     * 首页「每日资讯」专用读取：与任何商品无关，永不返回空。
     *
     * <p>历史缺陷：首页资讯走 {@code /news/live?prodId=}，按"选中商品标题"实时检索必应 RSS。
     * 商品改名/下架/标题无可检索词、或检索失败时，板块直接空白——资讯可用性被商品数据绑架。
     *
     * <p>三级保障（任一级有数据即返回，逐级降级）：
     * <ol>
     *   <li><b>库内资讯</b>：{@code t_market_news}（由独立 RSS 定时任务写入，与商品无关）；</li>
     *   <li><b>库内放宽</b>：忽略分类过滤再取一次（分类空态时不至于整块空白）；</li>
     *   <li><b>静态兜底</b>：平台自产的行业观察条目，保证前端一定有可渲染内容。</li>
     * </ol>
     * 三者都不依赖商品表，因此任何商品操作都不会影响本板块。
     */
    public List<Map<String, Object>> dailyFeed(String category, int limit) {
        int size = Math.min(Math.max(limit, 1), 20);

        // 首页面向工业品采购：优先展示价格/供给/政策/需求等业务资讯，
        // 避免科技消费类(tech)、国际综合(international)把业务资讯挤出首页。
        List<Map<String, Object>> items = businessFirstFeed(category, size);
        if (!items.isEmpty()) {
            return items;
        }
        // 分类空态：放宽到全量，避免"这个分类今天没抓到"就整块空白
        if (category != null && !category.isBlank()) {
            items = businessFirstFeed(null, size);
            if (!items.isEmpty()) {
                return items;
            }
        }
        return fallbackItems(size);
    }

    /**
     * 业务相关分类（工业采购视角），按优先级排列；其余分类仅在业务资讯不足时补位。
     *
     * <p>tech 被排除在外：该分类实测主要是消费电子/芯片新品/游戏主机，
     * 与工业采购资讯无关（需要时会作为补位来源，且仍须通过工业实体词判定）。
     */
    private static final List<String> BUSINESS_CATEGORIES =
            List.of("supply", "price", "policy", "market");

    /**
     * 业务资讯优先的读取：先按业务分类取，不足 {@code size} 时再用其它分类补齐。
     *
     * <p>只是排序/补位策略，不改变 {@link #feed} 的既有契约（价格情报台仍用全量口径）。
     */
    private List<Map<String, Object>> businessFirstFeed(String category, int size) {
        List<Map<String, Object>> items = businessFirstFeed(category, size, READ_MAX_AGE_DAYS);
        if (!items.isEmpty()) return items;
        // 近 7 天没抓到任何业务资讯（新库/抓取中断）：放宽到 30 天，仍不返回无上限历史数据
        return businessFirstFeed(category, size, READ_FALLBACK_AGE_DAYS);
    }

    private List<Map<String, Object>> businessFirstFeed(String category, int size, int maxAgeDays) {
        LocalDateTime since = maxAgeDays > 0 ? LocalDateTime.now().minusDays(maxAgeDays) : null;
        if (category != null && !category.isBlank()) {
            // 分类分支同样要过业务相关性过滤：否则用户点「价格」标签会看到
            // 洗地机促销、车企价格战、股票快讯等与工业采购无关的内容。
            List<Map<String, Object>> out = new ArrayList<>();
            List<MarketNews> rows = mapper.selectList(Wrappers.<MarketNews>lambdaQuery()
                    .eq(MarketNews::getCategory, category)
                    .ge(since != null, MarketNews::getPublishedAt, since)
                    .orderByDesc(MarketNews::getPublishedAt)
                    .last("LIMIT 50"));
            for (MarketNews row : rows) {
                if (out.size() >= size) break;
                Map<String, Object> item = toFeedItem(row);
                if (isBusinessItem(item)) {
                    out.add(item);
                }
            }
            // 该分类业务资讯不足时用平台观察条目补齐，保证标签页不空白
            if (out.size() < size) {
                for (Map<String, Object> item : fallbackItems(size)) {
                    if (out.size() >= size) break;
                    out.add(item);
                }
            }
            return out;
        }
        // 候选池：一次按时间倒序取业务分类，而不是"每个分类 LIMIT 30 再按分类顺序拼接"。
        // 历史缺陷：分类拼接会让 supply 分类的 17 条老稿排在 price/policy 的新稿之前，
        // 再被相关性过滤筛掉后，首页只剩 4 条真实资讯，其余全靠平台观察兜底。
        List<MarketNews> rows = mapper.selectList(Wrappers.<MarketNews>lambdaQuery()
                .in(MarketNews::getCategory, BUSINESS_CATEGORIES)
                .ge(since != null, MarketNews::getPublishedAt, since)
                .orderByDesc(MarketNews::getPublishedAt)
                .last("LIMIT 200"));
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (MarketNews row : rows) {
            if (out.size() >= size) break;
            Map<String, Object> item = toFeedItem(row);
            // 读取侧复用写入侧同一套业务相关性判定：
            // 过滤上线前入库的消费/娱乐类历史数据不应再出现在首页。
            if (isBusinessItem(item) && seen.add(item.get("id"))) {
                out.add(item);
            }
        }
        // 仍不足（库内工业资讯偏少）：用平台自产观察条目补齐，保证首页不出现"只有 2 条"的稀疏观感
        if (out.size() < size) {
            for (Map<String, Object> item : fallbackItems(size)) {
                if (out.size() >= size) break;
                out.add(item);
            }
        }
        return out;
    }

    /**
     * 是否属于 B2B 业务资讯（与 RSS 写入侧同一判定口径）。
     *
     * <p>三道闸门：① 非娱乐/消费电子（写入侧同款硬过滤）；② 标题含工业实体词；
     * ③ 命中在售商品词表——只有能影响商城在售品类的资讯才上首页。
     * 第 ③ 道在无词表服务（单测/未装配）时跳过，避免把历史数据全部判死。
     */
    private boolean isBusinessItem(Map<String, Object> item) {
        String title = item.get("title") == null ? "" : String.valueOf(item.get("title"));
        String summary = item.get("summary") == null ? "" : String.valueOf(item.get("summary"));
        if (!com.lanlink.shopping.module.aikb.ExternalInfoFetchService
                .isBusinessRelevant(title, summary)) {
            return false;
        }
        if (!hasIndustrialSignal(title, summary)) return false;
        if (catalogTerms == null) return true;
        List<String> terms = catalogTerms.terms();
        // 词表为空（新库/查询失败）时不做商品闸门，保留工业资讯
        if (terms.isEmpty()) return true;
        return ProductCatalogTerms.affects(title, summary, terms);
    }

    /**
     * 标题是否命中"工业实体词"。
     *
     * <p>只有"价格/股价/业绩"这类泛财经词的标题（消费电子促销、股市快讯、车企价格战）
     * 不算工业资讯——它们正是首页被污染的主因。
     */
    private static boolean hasIndustrialSignal(String title) {
        return hasIndustrialSignal(title, null);
    }

    /**
     * 标题/摘要是否构成工业资讯信号。
     *
     * @param title   标题（必须有工业实体词）
     * @param summary 摘要（仅用于排除消费品牌/促销；可为 null）
     */
    private static boolean hasIndustrialSignal(String title, String summary) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        String body = summary == null ? "" : summary.toLowerCase(java.util.Locale.ROOT);
        // 乘用车/消费品牌类标题即使含"停产/供应链"也不属于工业采购资讯
        for (String word : CONSUMER_BRAND_SIGNALS) {
            if (t.contains(word) || body.contains(word)) return false;
        }
        // 促销/家电/消费数码类：标题含工业词但实际是零售促销（"热泵洗烘一体机 10498 → 6104 元"）
        for (String word : RETAIL_PROMO_SIGNALS) {
            if (t.contains(word)) return false;
        }
        for (String word : INDUSTRIAL_TITLE_SIGNALS) {
            if (t.contains(word)) return true;
        }
        return false;
    }

    /**
     * 零售促销信号：标题命中即判定为非工业采购资讯。
     *
     * <p>实测漏网：IT之家「小户型一步到位：卡萨帝 10kg 热泵洗烘一体机 10498 → 6104 元破冰新低」
     * 因为含「一体机/设备」类工业词混进首页，实际是家电促销。
     */
    private static final List<String> RETAIL_PROMO_SIGNALS = List.of(
            "破冰新低", "新低", "直降", "国补", "到手价", "秒杀", "券后", "限时", "预售",
            "折合", "元起", "首发价", "跌破", "→", "洗烘", "洗衣机", "空调", "冰箱", "电视",
            "油烟机", "净烟机", "扫地机", "吸尘器", "吹风机", "电饭煲", "微波炉", "热水器");

    /** 消费/乘用车品牌信号：命中即排除（避免"车企停产""手机供应链"混入工业资讯）。 */
    private static final List<String> CONSUMER_BRAND_SIGNALS = List.of(
            "宝马", "奔驰", "奥迪", "大众", "丰田", "本田", "日产", "特斯拉", "蔚来", "小鹏",
            "理想汽车", "比亚迪", "捷豹", "路虎", "揽胜", "保时捷", "沃尔沃", "凯迪拉克",
            "苹果", "华为", "小米", "三星", "oppo", "vivo", "荣耀", "一加", "魅族",
            "iphone", "ipad", "macbook", "switch", "playstation", "xbox",
            // 乘用车品牌（正文出现即排除：车企产销稿对工业品采购无直接指导意义）
            "欧拉", "问界", "极氪", "深蓝", "埃安", "零跑", "哪吒", "智己", "岚图", "领克",
            "奇瑞", "吉利", "长安", "哈弗", "坦克300", "五菱", "宝骏", "荣威", "名爵",
            "黄仁勋", "英伟达", "任天堂", "索尼", "腾讯", "网易", "字节",
            // AI/消费电子上游讨论（内存涨价、算力采购等）不面向工业采购决策
            "openai", "奥尔特曼", "大模型", "算力", "数据中心", "显卡", "内存条", "闪存");

    /**
     * 首页资讯的「工业实体词」白名单：必须出现具体品类/材料/环节。
     *
     * <p>刻意排除"价格/股价/业绩/市值"等泛财经词——它们几乎出现在任何商业报道里，
     * 无法区分工业资讯与消费/股市快讯。
     */
    private static final List<String> INDUSTRIAL_TITLE_SIGNALS = List.of(
            // 原材料 / 大宗商品
            "钢", "钢材", "螺纹", "水泥", "建材", "化工", "化纤", "纺织", "塑料", "造纸", "纸浆", "玻璃",
            "铜", "铝", "锂", "稀土", "有色", "锌", "镍", "锡", "矿石", "矿产", "石英", "硅",
            "原油", "煤炭", "天然气", "成品油", "燃料",
            // 工业中间品 / 装备
            "元器件", "集成电路", "晶圆", "封装", "电路板", "连接器",
            "机械", "机床", "轴承", "泵", "阀门", "管道", "电缆", "变压器", "电机", "减速机", "液压",
            "制造业", "工业", "工厂", "产线", "产能", "开工率", "限产", "检修", "设备",
            // 工业供应链环节
            "供应链", "库存", "交期", "运价", "港口", "海运", "干线", "仓储", "仓储物流",
            "原材料", "大宗", "期货", "现货", "报价", "招标", "集采",
            // 工业下游（工程/基建，不含乘用车）
            "建筑", "施工", "基建", "工程", "厂房", "市政", "工装");

    /**
     * 静态兜底条目：平台自产、无外部依赖。
     *
     * <p>仅在库内一条数据都没有时使用（例如全新库尚未跑过 RSS 抓取）。
     * 明确标注来源为平台观察，不伪造具体行情数字。
     */
    private List<Map<String, Object>> fallbackItems(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String[][] seeds = {
                {"price", "工业品价格观察：本周主要品类报价以稳为主", "平台持续跟踪建材、化工、电子元器件等主要品类的现货报价与成交节奏，具体价格以商品页实时数据为准。"},
                {"supply", "供应链提示：关注错峰生产与物流时效对交期的影响", "北方地区进入错峰生产周期，部分基础化工品供给偏紧；跨区域采购建议预留额外在途时间。"},
                {"policy", "采购合规：增值税专用发票与账期结算要点", "企业采购需关注开票资质与账期授信条件，具体规则以平台政策页与订单结算页为准。"},
                {"market", "平台行情：工业品询盘活跃度保持平稳", "建筑、机械与电子三大行业的采购询盘维持常态水平，建议结合库存与交期综合决策。"},
        };
        for (int i = 0; i < seeds.length && out.size() < limit; i++) {
            MarketNews n = new MarketNews();
            n.setId(-1000L - i);
            n.setTitle(seeds[i][1]);
            n.setSummary(seeds[i][2]);
            n.setCategory(seeds[i][0]);
            n.setSource("平台观察");
            n.setImportance(i == 0 ? 2 : 1);
            n.setPublishedAt(now.minusHours(i * 3L));
            n.setCreatedAt(now);
            Map<String, Object> item = toFeedItem(n);
            // 兜底条目没有原文链接：指向站内价格情报台，而不是退化成必应搜索页（像坏链）
            item.put("url", "/price-monitor");
            item.put("sourceUrl", null);
            item.put("searchUrl", null);
            item.put("linkType", "internal");
            out.add(item);
        }
        return out;
    }

    /** 单条资讯的接口视图：基础字段保持固定顺序，尾部并入来源链接字段。 */
    private Map<String, Object> toFeedItem(MarketNews news) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", news.getId());
        item.put("title", news.getTitle());
        item.put("summary", news.getSummary());
        item.put("category", news.getCategory());
        item.put("source", news.getSource());
        item.put("tags", news.getTags());
        item.put("importance", news.getImportance());
        item.put("publishedAt", news.getPublishedAt());
        item.put("createdAt", news.getCreatedAt());
        // url/sourceUrl/searchUrl/linkType：article 原文优先 → internal 站内 → source 官网 → search 检索
        item.putAll(linkService.linkOf(news));
        return item;
    }
}

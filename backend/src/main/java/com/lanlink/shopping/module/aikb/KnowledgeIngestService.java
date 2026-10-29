package com.lanlink.shopping.module.aikb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.entity.Category;
import com.lanlink.shopping.entity.Industry;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CategoryMapper;
import com.lanlink.shopping.mapper.IndustryMapper;
import com.lanlink.shopping.mapper.LogisticsProviderMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.membership.entity.MemberLevel;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.mapper.MemberLevelMapper;
import com.lanlink.shopping.module.membership.mapper.SubscriptionPlanMapper;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库自动灌装（多信息源，全部幂等）：
 *   kb           本地客服知识库（classpath knowledge-base/customer-service-zh-CN.json 的问答对）
 *   policy       平台业务政策事实（classpath knowledge-base/platform-policy-zh-CN.json：支付/退换/发票/账期/入驻/会员/订阅/物流/客服）
 *   membership   会员等级门槛与折扣 + 积分规则（t_member_level + MemberRule）
 *   subscription 启用的订阅方案与价格（t_subscription_plan）
 *   category     行业与商品分类体系（t_industry + t_category）
 *   logistics    物流合作方（t_logistics_provider）
 *   product      在售商品快照（标题/品牌/规格/价格/行业）——夜间刷新
 *   news         外部市场资讯（MarketNews 最新 50 条）——每 6 小时自动拉取入库
 *   learned      运营在客服工作台审核通过的对话学习候选（方向：学习闭环回填向量库）
 * 来源重灌语义：product/news/policy/membership/subscription/category/logistics 先删后插（快照式）；
 * kb 内容哈希幂等（只增不变）；learned 逐条幂等 upsert。
 *
 * 性能设计：每个来源先收集 (refId,title,chunk) 列表，再一次性
 * {@link EmbeddingService#embedBatch(List)} 批量嵌入（Ollama 数组批量 + Redis 嵌入缓存），
 * 最后 upsert；日志输出“N chunks in M ms (embed=..., cacheHitRate=...)”便于观测灌库提速。
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);
    /** 灌库互斥标志（bootstrap 与调度线程并发防护） */
    private final java.util.concurrent.atomic.AtomicBoolean ingestRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 单块文本上限（超出切段，避免嵌入截断失真） */
    private static final int CHUNK_MAX = 1200;

    private final EmbeddingService embeddingService;
    private final RedisVectorStore store;
    private final ProductMapper productMapper;
    private final MarketNewsService newsService;
    private final MemberLevelMapper memberLevelMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final CategoryMapper categoryMapper;
    private final IndustryMapper industryMapper;
    private final LogisticsProviderMapper logisticsProviderMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("classpath:knowledge-base/customer-service-zh-CN.json")
    private Resource knowledgeBaseResource;

    @Value("classpath:knowledge-base/customer-service-en-US.json")
    private Resource knowledgeBaseEnResource;

    @Value("classpath:knowledge-base/platform-policy-zh-CN.json")
    private Resource policyResource;

    @Value("classpath:knowledge-base/platform-policy-en-US.json")
    private Resource policyEnResource;

    public KnowledgeIngestService(EmbeddingService embeddingService, RedisVectorStore store,
                                  ProductMapper productMapper, MarketNewsService newsService,
                                  MemberLevelMapper memberLevelMapper, SubscriptionPlanMapper subscriptionPlanMapper,
                                  CategoryMapper categoryMapper, IndustryMapper industryMapper,
                                  LogisticsProviderMapper logisticsProviderMapper) {
        this.embeddingService = embeddingService;
        this.store = store;
        this.productMapper = productMapper;
        this.newsService = newsService;
        this.memberLevelMapper = memberLevelMapper;
        this.subscriptionPlanMapper = subscriptionPlanMapper;
        this.categoryMapper = categoryMapper;
        this.industryMapper = industryMapper;
        this.logisticsProviderMapper = logisticsProviderMapper;
    }

    /** 待嵌入/入库的分块（先收集再批量嵌入，避免逐条 HTTP 往返） */
    private record PendingChunk(String source, String refId, String title, String text) {}

    // ==================== 定时自动源 ====================

    /** 外部市场资讯：每 6 小时自动拉取入库（外部信息源自动化；灌库互斥） */
    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 120_000L)
    public void scheduledNewsIngest() {
        if (!ingestRunning.compareAndSet(false, true)) {
            log.info("[aikb] news ingest skipped: another ingest is running");
            return;
        }
        try {
            int n = ingestNews();
            log.info("[aikb] news ingest done: {} chunks", n);
        } catch (Exception e) {
            log.warn("[aikb] news ingest failed: {}", e.getMessage());
        } finally {
            ingestRunning.set(false);
        }
    }

    /** 在售商品快照：每日刷新（灌库互斥） */
    @Scheduled(fixedDelay = 24 * 3600_000L, initialDelay = 240_000L)
    public void scheduledProductIngest() {
        if (!ingestRunning.compareAndSet(false, true)) {
            log.info("[aikb] product ingest skipped: another ingest is running");
            return;
        }
        try {
            int n = ingestProducts();
            log.info("[aikb] product ingest done: {} chunks", n);
        } catch (Exception e) {
            log.warn("[aikb] product ingest failed: {}", e.getMessage());
        } finally {
            ingestRunning.set(false);
        }
    }

    // ==================== 批量嵌入 / 入库 ====================

    /**
     * 批量嵌入 + upsert：一次 embedBatch（缓存命中 + ≤32/批 Ollama 数组批量），逐条写入向量库。
     * 日志固定格式：[aikb] ingest &lt;source&gt;: N chunks in M ms (embed=..., cacheHitRate=...)
     */
    private List<String> ingestBatchIds(String sourceLabel, List<PendingChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.info("[aikb] ingest {}: 0 chunks in 0 ms (embed=0 ms, cacheHitRate=0%)", sourceLabel);
            return List.of();
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> before = embeddingService.cacheStats();
        List<float[]> vectors = null;
        try {
            vectors = embeddingService.embedBatch(chunks.stream().map(PendingChunk::text).toList());
        } catch (Exception e) {
            log.warn("[aikb] ingest {}: batch embedding failed: {}", sourceLabel, e.getMessage());
        }
        long embedMs = System.currentTimeMillis() - t0;
        List<String> ids = new ArrayList<>(chunks.size());
        if (vectors != null) {
            for (int i = 0; i < chunks.size(); i++) {
                float[] v = i < vectors.size() ? vectors.get(i) : null;
                if (v == null || v.length == 0) continue;
                PendingChunk c = chunks.get(i);
                ids.add(store.upsert(c.source(), c.refId(), c.title(), c.text(), v));
            }
        }
        long totalMs = System.currentTimeMillis() - t0;
        log.info("[aikb] ingest {}: {} chunks in {} ms (embed={} ms, cacheHitRate={})",
                sourceLabel, ids.size(), totalMs, embedMs, cacheHitRate(before));
        return ids;
    }

    private int ingestBatch(String sourceLabel, List<PendingChunk> chunks) {
        return ingestBatchIds(sourceLabel, chunks).size();
    }

    /** 本批嵌入的缓存命中率（由 cacheStats 前后差值估算，不含历史累计） */
    private String cacheHitRate(Map<String, Object> before) {
        try {
            Map<String, Object> after = embeddingService.cacheStats();
            long hitDelta = num(after.get("hit")) - num(before.get("hit"));
            long missDelta = num(after.get("miss")) - num(before.get("miss"));
            long total = hitDelta + missDelta;
            if (total <= 0) return "0%";
            return (Math.round(hitDelta * 10000.0 / total) / 100.0) + "%";
        } catch (Exception e) {
            return "n/a";
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 收集 kb 问答分块（内容哈希幂等，只增不变） */
    private void readKbInto(Resource res, String label, String answerLabel, String titlePrefix,
                            List<PendingChunk> out) {
        try {
            JsonNode root = objectMapper.readTree(res.getInputStream());
            JsonNode entries = root.path("entries");
            if (!entries.isArray()) return;
            for (JsonNode e : entries) {
                String id = e.path("id").asText("");
                String answer = e.path("answer").asText("");
                if (answer.isBlank()) continue;
                List<String> keywords = new ArrayList<>();
                for (JsonNode k : e.path("keywords")) {
                    if (!k.asText("").isBlank()) keywords.add(k.asText(""));
                }
                String text = "【" + label + "】" + String.join(" / ", keywords) + "\n" + answerLabel + "：" + answer;
                for (String c : chunk(text)) {
                    out.add(new PendingChunk("kb", id, titlePrefix + " · " + id, c));
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] knowledge-base '{}' ingest failed: {}", label, e.getMessage());
        }
    }

    // ==================== 各来源灌装 ====================

    /** 本地客服知识库：中文 + 英文两套 how-to 语料，内容哈希幂等（只增不变） */
    public int ingestKnowledgeBase() {
        List<PendingChunk> chunks = new ArrayList<>();
        readKbInto(knowledgeBaseResource, "平台帮助", "回答", "平台帮助", chunks);
        readKbInto(knowledgeBaseEnResource, "Platform Help", "Answer", "Platform Help", chunks);
        int count = ingestBatch("kb", chunks);
        store.invalidateCache();
        log.info("[aikb] knowledge-base (zh+en) ingested: {} chunks", count);
        return count;
    }

    /** 在售商品快照：先清后灌（快照式，价格/上下架变化自动同步） */
    public int ingestProducts() {
        store.deleteSource("product");
        List<PendingChunk> chunks = new ArrayList<>();
        List<Product> products = productMapper.selectList(Wrappers.<Product>lambdaQuery()
                .eq(Product::getStatus, 1)
                .eq(Product::getReviewStatus, 1));
        for (Product p : products) {
            String text = "【商品】" + p.getTitle()
                    + (p.getBrand() != null ? " · 品牌 " + p.getBrand() : "")
                    + (p.getSpec() != null ? " · 规格 " + p.getSpec() : "")
                    + " · 现价 ¥" + p.getPrice()
                    + (p.getStock() != null ? " · 库存 " + p.getStock() : "");
            for (String c : chunk(text)) {
                chunks.add(new PendingChunk("product", String.valueOf(p.getProdId()), p.getTitle(), c));
            }
            String textEn = "[Product] " + p.getTitle()
                    + (p.getBrand() != null ? " | brand " + p.getBrand() : "")
                    + (p.getSpec() != null ? " | spec " + p.getSpec() : "")
                    + " | price ¥" + p.getPrice()
                    + (p.getStock() != null ? " | stock " + p.getStock() : "")
                    + " | status: on sale (bilingual corpus; English price/stock answer supported)";
            for (String c : chunk(textEn)) {
                chunks.add(new PendingChunk("product", p.getProdId() + "-en", p.getTitle(), c));
            }
        }
        int count = ingestBatch("product", chunks);
        store.invalidateCache();
        return count;
    }

    /** 外部市场资讯：先清后灌最新 50 条 */
    public int ingestNews() {
        store.deleteSource("news");
        List<PendingChunk> chunks = new ArrayList<>();
        List<MarketNews> news = newsService.latest(50);
        for (MarketNews n : news) {
            String text = "【市场资讯 · " + (n.getCategory() == null ? "综合" : n.getCategory()) + "】"
                    + n.getTitle() + (n.getSummary() == null ? "" : "\n" + n.getSummary());
            for (String c : chunk(text)) {
                chunks.add(new PendingChunk("news", String.valueOf(n.getId()),
                        n.getTitle() == null ? "市场资讯" : n.getTitle(), c));
            }
        }
        int count = ingestBatch("news", chunks);
        store.invalidateCache();
        return count;
    }

    /** 平台业务政策：中文 + 英文两套语料，快照式先删后灌 */
    public int ingestPolicy() {
        store.deleteSource("policy");
        List<PendingChunk> chunks = new ArrayList<>();
        readPolicyInto(policyResource, "平台政策", chunks);
        readPolicyInto(policyEnResource, "Platform Policy", chunks);
        int count = ingestBatch("policy", chunks);
        store.invalidateCache();
        log.info("[aikb] policy ingested (zh+en): {} chunks", count);
        return count;
    }

    private void readPolicyInto(Resource res, String labelPrefix, List<PendingChunk> out) {
        try {
            JsonNode root = objectMapper.readTree(res.getInputStream());
            for (JsonNode e : root.path("entries")) {
                String id = e.path("id").asText("");
                String text = e.path("text").asText("");
                if (id.isBlank() || text.isBlank()) continue;
                String title = e.path("title").asText(id);
                List<String> kws = new ArrayList<>();
                for (JsonNode k : e.path("keywords")) if (!k.asText("").isBlank()) kws.add(k.asText(""));
                String chunkText = "【" + labelPrefix + " · " + title + "】" + String.join(" / ", kws) + "\n" + text;
                for (String c : chunk(chunkText)) {
                    out.add(new PendingChunk("policy", id, labelPrefix + " · " + title, c));
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] policy '{}' ingest failed: {}", labelPrefix, e.getMessage());
        }
    }

    /** 会员等级门槛与折扣 + 积分规则：快照式先删后灌（中英双语块） */
    public int ingestMembership() {
        store.deleteSource("membership");
        List<PendingChunk> chunks = new ArrayList<>();
        try {
            List<MemberLevel> levels = memberLevelMapper.selectList(
                    Wrappers.<MemberLevel>lambdaQuery().orderByAsc(MemberLevel::getMinGrowth));
            if (!levels.isEmpty()) {
                StringBuilder sb = new StringBuilder("【会员等级体系】LANLinkshopping 按成长值划分会员等级，等级越高折扣越高：\n");
                for (MemberLevel lv : levels) {
                    sb.append("· ").append(lv.getLevelName())
                      .append("：成长值 ≥ ").append(lv.getMinGrowth() == null ? 0 : lv.getMinGrowth());
                    if (lv.getDiscountRate() != null) {
                        sb.append("，专属折扣 ").append(formatDiscount(lv.getDiscountRate()));
                    }
                    if (lv.getDescription() != null && !lv.getDescription().isBlank()) {
                        sb.append("（").append(lv.getDescription().trim()).append("）");
                    }
                    sb.append("\n");
                }
                sb.append("积分规则：每消费 ").append(com.lanlink.shopping.module.membership.constant.MemberRule.POINTS_PER_YUAN_EARN)
                  .append(" 元获得 1 积分（VIP 消费为 ").append(com.lanlink.shopping.module.membership.constant.MemberRule.VIP_EARN_MULTIPLIER).append(" 倍）；")
                  .append("每 ").append(com.lanlink.shopping.module.membership.constant.MemberRule.REDEEM_POINTS_PER_YUAN)
                  .append(" 积分可抵 1 元，单笔最高抵扣订单应付金额的 ")
                  .append(com.lanlink.shopping.module.membership.constant.MemberRule.REDEEM_MAX_PERCENT).append("%，")
                  .append("订单取消后使用的积分全额返还。具体门槛与折扣以会员中心展示为准。");
                for (String c : chunk(sb.toString())) {
                    chunks.add(new PendingChunk("membership", "member-levels", "会员等级与积分规则", c));
                }
                // English rendering of the same real tier data
                StringBuilder en = new StringBuilder("[Membership Levels] LANLinkshopping defines membership tiers by growth value; higher tiers get higher discounts:\n");
                for (MemberLevel lv : levels) {
                    en.append("- ").append(englishLevelName(lv.getLevelName()))
                      .append(": growth value >= ").append(lv.getMinGrowth() == null ? 0 : lv.getMinGrowth());
                    if (lv.getDiscountRate() != null) en.append(", discount ").append(formatDiscountEn(lv.getDiscountRate()));
                    en.append("\n");
                }
                en.append("Points: earn ").append(com.lanlink.shopping.module.membership.constant.MemberRule.POINTS_PER_YUAN_EARN)
                  .append(" point per ¥1 spent (VIP earns ").append(com.lanlink.shopping.module.membership.constant.MemberRule.VIP_EARN_MULTIPLIER).append("x); ")
                  .append(com.lanlink.shopping.module.membership.constant.MemberRule.REDEEM_POINTS_PER_YUAN)
                  .append(" points redeem ¥1, capped at ").append(com.lanlink.shopping.module.membership.constant.MemberRule.REDEEM_MAX_PERCENT)
                  .append("% of the payable amount per order; points used on a cancelled order are fully refunded. Exact tiers and discounts follow the Membership Center.")
                  .append(" (bilingual corpus; English answer supported)");
                for (String c : chunk(en.toString())) {
                    chunks.add(new PendingChunk("membership", "member-levels-en", "Membership Levels & Points", c));
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] membership ingest failed: {}", e.getMessage());
        }
        int count = ingestBatch("membership", chunks);
        store.invalidateCache();
        log.info("[aikb] membership ingested: {} chunks", count);
        return count;
    }

    /** 启用的订阅方案与价格：快照式先删后灌（中英双语块） */
    public int ingestSubscription() {
        store.deleteSource("subscription");
        List<PendingChunk> chunks = new ArrayList<>();
        try {
            List<SubscriptionPlan> plans = subscriptionPlanMapper.selectList(
                    Wrappers.<SubscriptionPlan>lambdaQuery()
                            .eq(SubscriptionPlan::getEnabled, 1)
                            .orderByAsc(SubscriptionPlan::getSort));
            if (!plans.isEmpty()) {
                StringBuilder sb = new StringBuilder("【订阅方案】LANLinkshopping 提供以下订阅方案（价格为人民币元，以订阅页实时展示为准）：\n");
                for (SubscriptionPlan p : plans) {
                    sb.append("· ").append(p.getPlanName())
                      .append("：¥").append(p.getPriceCents() == null ? 0 : p.getPriceCents())
                      .append("，有效期 ").append(p.getDurationDays() == null ? 0 : p.getDurationDays()).append(" 天");
                    if (p.getDiscountLabel() != null && !p.getDiscountLabel().isBlank()) {
                        sb.append("，").append(p.getDiscountLabel().trim());
                    }
                    if (p.getAutoRenew() != null && p.getAutoRenew() == 1) sb.append("，支持自动续费");
                    sb.append("\n");
                }
                for (String c : chunk(sb.toString())) {
                    chunks.add(new PendingChunk("subscription", "subscription-plans", "订阅方案与价格", c));
                }
                StringBuilder en = new StringBuilder("[Subscription Plans] LANLinkshopping offers these subscription plans (price in CNY; follow the real-time subscription page):\n");
                for (SubscriptionPlan p : plans) {
                    en.append("- ").append(englishPlanName(p.getPlanName()))
                      .append(": ¥").append(p.getPriceCents() == null ? 0 : p.getPriceCents())
                      .append(", valid ").append(p.getDurationDays() == null ? 0 : p.getDurationDays()).append(" days");
                    if (p.getDiscountLabel() != null && !p.getDiscountLabel().isBlank()) en.append(", ").append(translateDiscountLabel(p.getDiscountLabel().trim()));
                    if (p.getAutoRenew() != null && p.getAutoRenew() == 1) en.append(", auto-renew available");
                    en.append("\n");
                }
                en.append("(bilingual corpus; English answer supported)");
                for (String c : chunk(en.toString())) {
                    chunks.add(new PendingChunk("subscription", "subscription-plans-en", "Subscription Plans", c));
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] subscription ingest failed: {}", e.getMessage());
        }
        int count = ingestBatch("subscription", chunks);
        store.invalidateCache();
        log.info("[aikb] subscription ingested: {} chunks", count);
        return count;
    }

    /** 行业与商品分类体系：快照式先删后灌 */
    public int ingestCategory() {
        store.deleteSource("category");
        List<PendingChunk> chunks = new ArrayList<>();
        try {
            List<Industry> industries = industryMapper.selectList(
                    Wrappers.<Industry>lambdaQuery().orderByAsc(Industry::getSort));
            if (!industries.isEmpty()) {
                String names = industries.stream().map(Industry::getName)
                        .filter(n -> n != null && !n.isBlank()).reduce((a, b) -> a + "、" + b).orElse("");
                if (!names.isBlank()) {
                    String c = "【行业分类】平台覆盖的经营行业包括：" + names + "。可在商城按行业分类筛选对应商品与供应商。";
                    for (String seg : chunk(c)) {
                        chunks.add(new PendingChunk("category", "industries", "行业分类", seg));
                    }
                }
            }
            // 顶层商品分类（parentId 为空/0）数量可控，聚合为一条概览
            List<Category> tops = categoryMapper.selectList(
                    Wrappers.<Category>lambdaQuery()
                            .and(w -> w.isNull(Category::getParentId).or().eq(Category::getParentId, 0L))
                            .orderByAsc(Category::getSort));
            if (!tops.isEmpty()) {
                String names = tops.stream().map(Category::getName)
                        .filter(n -> n != null && !n.isBlank()).limit(60).reduce((a, b) -> a + "、" + b).orElse("");
                if (!names.isBlank()) {
                    String c = "【商品一级分类】平台商品一级分类包括：" + names + "。具体子类可在商城对应一级分类下继续展开选择。";
                    for (String seg : chunk(c)) {
                        chunks.add(new PendingChunk("category", "top-categories", "商品一级分类", seg));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] category ingest failed: {}", e.getMessage());
        }
        int count = ingestBatch("category", chunks);
        store.invalidateCache();
        log.info("[aikb] category ingested: {} chunks", count);
        return count;
    }

    /** 物流合作方：启用服务商名称快照式先删后灌（中英双语块，不含密钥/端点等敏感字段） */
    public int ingestLogistics() {
        store.deleteSource("logistics");
        List<PendingChunk> chunks = new ArrayList<>();
        try {
            List<LogisticsProvider> providers = logisticsProviderMapper.selectList(
                    Wrappers.<LogisticsProvider>lambdaQuery()
                            .eq(LogisticsProvider::getStatus, 1)
                            .eq(LogisticsProvider::getDeleted, 0)
                            .orderByAsc(LogisticsProvider::getSort));
            if (!providers.isEmpty()) {
                StringBuilder sb = new StringBuilder("【物流合作方】平台接入的物流 / 配送方式包括：");
                List<String> parts = new ArrayList<>();
                List<String> partsEn = new ArrayList<>();
                for (LogisticsProvider p : providers) {
                    if (p.getProviderName() == null || p.getProviderName().isBlank()) continue;
                    String label = p.getProviderName().trim();
                    if (p.getSettleCycle() != null && !p.getSettleCycle().isBlank()) {
                        label += "（结算周期 " + settleCycleName(p.getSettleCycle()) + "）";
                    }
                    parts.add(label);
                    String labelEn = englishProviderName(p.getProviderName().trim());
                    if (p.getSettleCycle() != null && !p.getSettleCycle().isBlank()) {
                        labelEn += " (" + settleCycleNameEn(p.getSettleCycle()) + ")";
                    }
                    partsEn.add(labelEn);
                }
                sb.append(String.join("、", parts)).append("。发货时效与运费依所选物流及商品履约约定而定，可在物流详情查询运单与轨迹。");
                for (String c : chunk(sb.toString())) {
                    chunks.add(new PendingChunk("logistics", "providers", "物流合作方", c));
                }
                String en = "[Logistics Partners] The platform integrates these logistics / delivery options: "
                        + String.join(", ", partsEn)
                        + ". Shipping, freight and delivery time depend on the chosen carrier and the product's fulfilment terms; track the waybill and shipment trace in the logistics detail. (bilingual corpus; English answer supported)";
                for (String c : chunk(en)) {
                    chunks.add(new PendingChunk("logistics", "providers-en", "Logistics Partners", c));
                }
            }
        } catch (Exception e) {
            log.warn("[aikb] logistics ingest failed: {}", e.getMessage());
        }
        int count = ingestBatch("logistics", chunks);
        store.invalidateCache();
        log.info("[aikb] logistics ingested: {} chunks", count);
        return count;
    }

    /** 学习闭环回填：运营审核通过的候选问答，嵌入并 upsert 进向量库（source=learned，幂等） */
    public int ingestLearned(String entryId, String question, String answer) {
        if (entryId == null || entryId.isBlank() || answer == null || answer.isBlank()) return 0;
        String q = question == null || question.isBlank() ? entryId : question.trim();
        String text = "【客服知识 · " + q + "】\n" + answer.trim();
        List<PendingChunk> chunks = new ArrayList<>();
        for (String c : chunk(text)) chunks.add(new PendingChunk("learned", entryId, q, c));
        int count = ingestBatch("learned", chunks);
        store.invalidateCache();
        log.info("[aikb] learned entry '{}' ingested: {} chunks", entryId, count);
        return count;
    }

    /** 移除某条已学习/已录入知识（按 entryId） */
    public void removeLearned(String entryId) {
        if (entryId == null || entryId.isBlank()) return;
        // learned 文档 id 由内容哈希，无法反推；按 refId 扫描删除
        for (RedisVectorStore.DocInfo d : store.listByRef("learned", entryId)) {
            store.deleteDoc(d.id());
        }
        store.invalidateCache();
    }

    /** 管理员手工录入一条平台知识（source 默认 manual，幂等），返回文档 id */
    public String addManual(String source, String title, String text) {
        String src = (source == null || source.isBlank()) ? "manual" : source.trim();
        String t = title == null ? "" : title.trim();
        String body = (t.isBlank() ? "" : "【" + t + "】") + (text == null ? "" : text.trim());
        if (body.isBlank()) throw new com.lanlink.shopping.common.BusinessException("知识内容不能为空");
        String refId = t.isBlank() ? "manual-" + System.currentTimeMillis() : t;
        List<PendingChunk> chunks = new ArrayList<>();
        for (String c : chunk(body)) chunks.add(new PendingChunk(src, refId, t.isBlank() ? "平台知识" : t, c));
        List<String> ids = ingestBatchIds(src, chunks);
        store.invalidateCache();
        return ids.isEmpty() ? null : ids.get(ids.size() - 1);
    }

    /** 按文档 id 删除一条知识 */
    public boolean removeDoc(String docId) {
        boolean ok = store.deleteDoc(docId);
        store.invalidateCache();
        return ok;
    }

    private static String formatDiscount(java.math.BigDecimal rate) {
        if (rate == null) return "";
        // 0.95 → 95折；1.00 → 无额外折扣
        double v = rate.doubleValue();
        if (v >= 0.999) return "无额外折扣";
        double zhe = v * 10;
        String s = (zhe == Math.floor(zhe)) ? String.valueOf((int) zhe) : String.format("%.1f", zhe);
        return s + "折";
    }

    private static String settleCycleName(String code) {
        if (code == null) return code;
        switch (code.toLowerCase()) {
            case "monthly": return "月结";
            case "weekly": return "周结";
            case "realtime": return "实时";
            default: return code;
        }
    }

    // ==================== 英文语料标签映射（双语入库用） ====================

    private static String firstNonBlank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    static String englishLevelName(String zh) {
        if (zh == null || zh.isBlank()) return zh;
        if (zh.contains("黑钻")) return "Black Diamond";
        if (zh.contains("钻石")) return "Diamond";
        if (zh.contains("铂金")) return "Platinum";
        if (zh.contains("黄金")) return "Gold";
        if (zh.contains("白银") || zh.contains("银卡")) return "Silver";
        if (zh.contains("普通会员") || zh.contains("普通")) return "Regular";
        return zh;
    }

    static String englishPlanName(String zh) {
        if (zh == null || zh.isBlank()) return zh;
        if (zh.contains("连续包月") || zh.contains("自动续费")) return "Auto-renew Monthly";
        if (zh.contains("月度") || zh.contains("包月")) return "Monthly";
        if (zh.contains("季度")) return "Quarterly";
        if (zh.contains("半年")) return "Semi-annual";
        if (zh.contains("年度") || zh.contains("年费")) return "Annual";
        return zh;
    }

    static String englishProviderName(String zh) {
        if (zh == null || zh.isBlank()) return zh;
        if (zh.contains("顺丰")) return "SF Express";
        if (zh.contains("德邦")) return "Deppon Logistics";
        if (zh.contains("京东")) return "JD Logistics";
        if (zh.contains("中通")) return "ZTO Express";
        if (zh.contains("圆通")) return "YTO Express";
        if (zh.contains("申通")) return "STO Express";
        if (zh.contains("韵达")) return "Yunda Express";
        if (zh.contains("自送") || zh.contains("商家自")) return "Merchant self-delivery";
        return zh;
    }

    static String translateDiscountLabel(String label) {
        if (label == null || label.isBlank()) return label;
        String m = label.replaceAll("省\\s*", "save ").replaceAll("优惠\\s*", "save ");
        return firstNonBlank(m, label);
    }

    static String formatDiscountEn(java.math.BigDecimal rate) {
        if (rate == null) return "";
        double v = rate.doubleValue();
        if (v >= 0.999) return "no extra discount";
        int off = (int) Math.round((1 - v) * 100);
        return off + "% off";
    }

    private static String settleCycleNameEn(String code) {
        if (code == null) return code;
        switch (code.toLowerCase()) {
            case "monthly": return "monthly settlement";
            case "weekly": return "weekly settlement";
            case "realtime": return "real-time settlement";
            default: return code;
        }
    }

    /** 全量重灌（管理端触发；灌库互斥） */
    public Map<String, Integer> ingestAll() {
        if (!ingestRunning.compareAndSet(false, true)) {
            throw new com.lanlink.shopping.common.BusinessException("已有灌装任务在进行中，请稍后再试");
        }
        try {
            Map<String, Integer> out = new LinkedHashMap<>();
            out.put("kb", ingestKnowledgeBase());
            out.put("policy", ingestPolicy());
            out.put("membership", ingestMembership());
            out.put("subscription", ingestSubscription());
            out.put("category", ingestCategory());
            out.put("logistics", ingestLogistics());
            out.put("product", ingestProducts());
            out.put("news", ingestNews());
            return out;
        } finally {
            ingestRunning.set(false);
        }
    }

    /** 长文本切块（按句号/换行就近断句，单块 ≤ CHUNK_MAX） */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        if (text.length() <= CHUNK_MAX) { out.add(text); return out; }
        StringBuilder cur = new StringBuilder();
        for (String seg : text.split("(?<=[。！？\\n])")) {
            if (cur.length() + seg.length() > CHUNK_MAX && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(seg);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}

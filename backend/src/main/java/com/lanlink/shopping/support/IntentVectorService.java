package com.lanlink.shopping.support;

import com.lanlink.shopping.module.aikb.EmbeddingService;
import com.lanlink.shopping.module.aikb.RedisVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 意图混合分类（11 类客服意图的自然语言示例语料 + Redis 向量库 KNN + 字符 bigram 词面相似度）。
 *
 * <p>为什么 hybrid：本地嵌入模型 nomic-embed-text 是英文模型，中文意图示例的余弦分数区分度很差
 * （实测各示例都在 0.8+，几乎无法区分），单靠向量检索会误判。因此本服务用
 * <b>0.55 × 嵌入余弦 + 0.45 × 字符 bigram 词面覆盖度</b> 融合打分；若某意图已被规则命中，
 * 再加 +0.10 规则先验。只有最高分 ≥ 阈值（默认 0.55）且与次优意图分差 ≥ 0.02 才采纳，
 * 否则返回 null 由调用方回退规则/本地模型。
 *
 * <p>语料来源 = 内置示例（与 {@link IntentUnderstandingService} 的 RULES code/label 对齐），
 * 启动后异步预灌入向量库（source={@value #SOURCE}）；首次调用仍有懒加载兜底，不重复嵌入。
 * 不写死嵌入维度/模型名：换 bge-m3 等多语言模型后逻辑自动生效（bigram 只依赖中文文本）。
 */
@Service
public class IntentVectorService {

    private static final Logger log = LoggerFactory.getLogger(IntentVectorService.class);

    /** 向量库来源标识（意图示例语料） */
    public static final String SOURCE = "intent";
    /** 单次 KNN 检索条数 */
    private static final int TOP_K = 8;
    /** 返回给调用方的候选意图上限 */
    private static final int MAX_CANDIDATES = 3;

    /** hybrid 打分权重：嵌入余弦 / 字符 bigram 词面覆盖度 */
    static final double EMBED_WEIGHT = 0.55;
    static final double LEXICAL_WEIGHT = 0.45;
    /** 已被规则命中的意图先验加成 */
    static final double RULE_PRIOR_BONUS = 0.10;
    /** 采纳最小分差：低于该分差视为仍歧义，回退规则 */
    static final double MIN_MARGIN = 0.02;

    private final EmbeddingService embeddingService;
    private final RedisVectorStore store;
    /** 采纳阈值（hybrid 分；默认 0.55，可用 AI_INTENT_VECTOR_MIN_SCORE 覆盖） */
    private final double minScore;

    private final AtomicBoolean seedChecked = new AtomicBoolean(false);
    private final Object seedLock = new Object();
    private volatile int seededCount = 0;

    public IntentVectorService(EmbeddingService embeddingService, RedisVectorStore store,
                               @Value("${AI_INTENT_VECTOR_MIN_SCORE:0.55}") double minScore) {
        this.embeddingService = embeddingService;
        this.store = store;
        this.minScore = minScore > 0 && minScore <= 1 ? minScore : 0.55;
    }

    /** 向量分类候选（按 hybrid 分降序） */
    public record Candidate(String intent, String label, double score) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", intent);
            m.put("label", label);
            m.put("score", score);
            return m;
        }
    }

    /** 一次混合分类结果：intent/label 为最高分意图，margin 为与次优意图的分差 */
    public record Classification(String intent, String label, double score, double margin,
                                 List<Candidate> candidates) {
    }

    /** 无规则先验的兼容入口 */
    public Classification classify(String question) {
        return classify(question, List.of());
    }

    /**
     * 混合意图分类（嵌入余弦 + 字符 bigram 词面覆盖度 + 规则先验）。
     *
     * <p>注意：{@link RedisVectorStore#search} 两种引擎（原生 RediSearch KNN / 内存余弦回退）
     * 的分值口径已统一为余弦相似度（越大越相近），这里直接作为嵌入分量使用。
     *
     * @param ruleCandidateIntents 规则已命中的意图 code（命中的意图 +0.10 先验，可空）
     * @return 分类结果；无命中/低于阈值/分差不足/任何异常时返回 null（调用方回退规则）
     */
    public Classification classify(String question, Collection<String> ruleCandidateIntents) {
        if (question == null || question.isBlank()) return null;
        try {
            ensureSeeded();
            float[] qv = embeddingService.embed(question.trim());
            if (qv == null || qv.length == 0) return null;
            List<RedisVectorStore.Hit> hits = store.search(qv, TOP_K, SOURCE);
            if (hits == null || hits.isEmpty()) return null;
            Set<String> priorIntents = ruleCandidateIntents == null || ruleCandidateIntents.isEmpty()
                    ? Set.of() : new HashSet<>(ruleCandidateIntents);
            // 按 refId（意图 code）聚合：同一意图多条示例取最高 hybrid 分
            Map<String, Double> bestByIntent = new LinkedHashMap<>();
            for (RedisVectorStore.Hit hit : hits) {
                String intent = hit.refId();
                if (intent == null || !LABELS.containsKey(intent)) continue;
                double embeddingSimilarity = Double.isFinite(hit.score()) ? hit.score() : 0.0;
                double lexical = bigramCoverage(question, hit.text());
                double hybrid = hybridScore(embeddingSimilarity, lexical, priorIntents.contains(intent));
                bestByIntent.merge(intent, hybrid, Math::max);
            }
            if (bestByIntent.isEmpty()) return null;
            List<Candidate> candidates = new ArrayList<>(bestByIntent.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(MAX_CANDIDATES)
                    .map(e -> new Candidate(e.getKey(), LABELS.get(e.getKey()), round(e.getValue())))
                    .toList());
            Candidate best = candidates.get(0);
            double second = candidates.size() > 1 ? candidates.get(1).score() : 0.0;
            double margin = Math.max(0.0, round(best.score() - second));
            if (best.score() < minScore || margin < MIN_MARGIN) return null;
            return new Classification(best.intent(), best.label(), best.score(), margin, candidates);
        } catch (Exception e) {
            // 静默回退：向量增强失败绝不影响规则分类主链路
            log.debug("[intent-vector] classify failed, fallback to local rules: {}", e.getMessage());
            return null;
        }
    }

    /**
     * hybrid 融合打分：0.55×嵌入余弦 + 0.45×字面 bigram 覆盖度（+0.10 规则先验），裁剪到 [0,1]。
     * 纯函数，便于单测验证权重与先验。
     */
    static double hybridScore(double embeddingSimilarity, double bigramCoverage, boolean rulePrior) {
        double score = EMBED_WEIGHT * clamp01(embeddingSimilarity) + LEXICAL_WEIGHT * clamp01(bigramCoverage);
        if (rulePrior) score += RULE_PRIOR_BONUS;
        return round(clamp01(score));
    }

    /**
     * 字符 bigram 词面覆盖度（中文双字组）：query 的 CJK bigram 在示例文本中命中的比例。
     * 对 nomic-embed-text 这类英文嵌入源，这是中文意图最有效的区分信号。
     */
    static double bigramCoverage(String query, String example) {
        Set<String> queryBigrams = cjkBigrams(query);
        if (queryBigrams.isEmpty()) return 0.0;
        Set<String> exampleBigrams = cjkBigrams(example);
        if (exampleBigrams.isEmpty()) return 0.0;
        int hit = 0;
        for (String bigram : queryBigrams) {
            if (exampleBigrams.contains(bigram)) hit++;
        }
        return (double) hit / queryBigrams.size();
    }

    /** 提取 CJK 连续片段的两字组（标点/数字/英文视为分隔） */
    private static Set<String> cjkBigrams(String text) {
        if (text == null || text.isEmpty()) return Set.of();
        Set<String> bigrams = new LinkedHashSet<>();
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                segment.append(c);
            } else if (segment.length() > 0) {
                addBigrams(bigrams, segment);
                segment.setLength(0);
            }
        }
        if (segment.length() > 0) addBigrams(bigrams, segment);
        return bigrams;
    }

    private static void addBigrams(Set<String> target, StringBuilder segment) {
        if (segment.length() < 2) return;
        for (int i = 0; i + 1 < segment.length(); i++) {
            target.add(segment.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 启动后异步预灌示例语料：保证管理端可见 intent 语料，且 LLM/网关不可用时向量回退立即可用 */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        Thread t = new Thread(() -> {
            try {
                ensureSeeded();
            } catch (Exception e) {
                log.warn("[intent-vector] startup seed skipped (will retry on demand): {}", e.getMessage());
            }
        }, "intent-vector-seed");
        t.setDaemon(true);
        t.start();
    }

    /** 懒灌装示例语料：向量库 source=intent 为空时逐条嵌入并 upsert（幂等，仅灌一次） */
    public void ensureSeeded() {
        if (seedChecked.get()) return;
        synchronized (seedLock) {
            if (seedChecked.get()) return;
            try {
                List<RedisVectorStore.DocInfo> existing = store.list(SOURCE, 1);
                if (existing != null && !existing.isEmpty()) {
                    if (corpusCompatible()) {
                        seedChecked.set(true);
                        return;
                    }
                    // 嵌入模型/维度已切换（如 nomic-embed-text → bge-m3 重建向量库）：旧示例不可检索，重灌
                    log.info("[intent-vector] stale intent corpus detected (embedding model/dimension changed), reseeding");
                    store.deleteSource(SOURCE);
                    store.invalidateCache();
                }
                int count = 0;
                for (CandidateExample example : CORPUS) {
                    float[] vector = embeddingService.embed(example.text());
                    if (vector == null || vector.length == 0) continue;
                    store.upsert(SOURCE, example.intent(), LABELS.get(example.intent()), example.text(), vector);
                    count++;
                }
                if (count > 0) {
                    store.invalidateCache();
                    seededCount = count;
                    seedChecked.set(true);
                    log.info("[intent-vector] seeded {} intent examples into vector source '{}'", count, SOURCE);
                }
            } catch (Exception e) {
                // 不置 seedChecked，下次调用重试（Redis 未就绪时静默降级）
                log.warn("[intent-vector] seed skipped (will retry): {}", e.getMessage());
            }
        }
    }

    /**
     * 既有语料是否仍与当前嵌入模型兼容（不写死维度/模型名，动态判定）：
     * 以示例探针嵌入的维度与向量库记录的维度对比，维度一致时再做一次探针检索确认可命中。
     * 无法确认（嵌入降级为哈希/维度未知/查询异常）时返回 true，避免误删既有语料。
     */
    private boolean corpusCompatible() {
        try {
            float[] probe = embeddingService.embed(CORPUS.get(0).text());
            if (probe == null || probe.length == 0) return true;
            if ("hash-fallback".equals(embeddingService.getProvider())) return true;
            int storedDim = store.dim();
            if (storedDim <= 0) return true;
            if (storedDim != probe.length) return false;
            List<RedisVectorStore.Hit> probeHits = store.search(probe, 1, SOURCE);
            return probeHits != null && !probeHits.isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    /** 当前生效的采纳阈值（供 IntentUnderstandingService 二次校验） */
    public double minScore() {
        return minScore;
    }

    /** 已灌装的示例条数（0 = 尚未灌装/灌装失败；管理端/测试可观测） */
    public int seededCount() {
        return seededCount;
    }

    /** 内置语料总条数（测试断言用） */
    static int corpusSize() {
        return CORPUS.size();
    }

    /** 意图 code → 中文标签（与 IntentUnderstandingService.RULES 对齐） */
    static final Map<String, String> LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("account_access", "账号注册与登录");
        labels.put("merchant_onboarding", "商户入驻");
        labels.put("product_browse", "商品浏览");
        labels.put("cart_order", "购物车与订单");
        labels.put("wallet_credit", "钱包与账期");
        labels.put("membership_activity", "会员与活动");
        labels.put("message_privacy", "消息与隐私");
        labels.put("logistics_tracking", "物流与配送");
        labels.put("refund_aftersale", "退款与售后");
        labels.put("payment_invoice", "支付与发票");
        labels.put("complaint_human", "人工客服与投诉");
        LABELS = Map.copyOf(labels);
    }

    /** 示例语料（每类 6 条自然语言问法，覆盖同义表达/口语化表达） */
    private record CandidateExample(String intent, String text) {
    }

    private static final List<CandidateExample> CORPUS = List.of(
            // account_access 账号注册与登录
            new CandidateExample("account_access", "我注册账号收不到短信验证码"),
            new CandidateExample("account_access", "忘记登录密码了怎么找回"),
            new CandidateExample("account_access", "想修改账号绑定的手机号"),
            new CandidateExample("account_access", "账号被锁定了怎么解锁"),
            new CandidateExample("account_access", "登录的时候一直提示密码错误"),
            new CandidateExample("account_access", "短信验证码一直收不到怎么登录"),
            // merchant_onboarding 商户入驻
            new CandidateExample("merchant_onboarding", "我想申请商户入驻平台"),
            new CandidateExample("merchant_onboarding", "供应商入驻需要提交什么资质"),
            new CandidateExample("merchant_onboarding", "开店要交多少保证金"),
            new CandidateExample("merchant_onboarding", "企业入驻审核一般多久通过"),
            new CandidateExample("merchant_onboarding", "怎么把商品上架到我的店铺"),
            new CandidateExample("merchant_onboarding", "入驻平台的资质审核没通过怎么办"),
            // product_browse 商品浏览
            new CandidateExample("product_browse", "这个商品的规格参数怎么看"),
            new CandidateExample("product_browse", "你们商城有哪些行业分类"),
            new CandidateExample("product_browse", "想找一款耐高温的工业材料"),
            new CandidateExample("product_browse", "这个型号还有现货库存吗"),
            new CandidateExample("product_browse", "商品的材质和保质期是多少"),
            new CandidateExample("product_browse", "想看看这个产品的详细参数和规格"),
            // cart_order 购物车与订单
            new CandidateExample("cart_order", "购物车里的商品怎么一起结算"),
            new CandidateExample("cart_order", "我刚下的订单什么时候发货"),
            new CandidateExample("cart_order", "订单号在哪里可以查询"),
            new CandidateExample("cart_order", "想把订单的收货地址改一下"),
            new CandidateExample("cart_order", "提交订单的时候一直失败"),
            new CandidateExample("cart_order", "单子下了怎么还没动静"),
            // wallet_credit 钱包与账期
            new CandidateExample("wallet_credit", "钱包余额怎么提现"),
            new CandidateExample("wallet_credit", "企业账期授信额度怎么申请"),
            new CandidateExample("wallet_credit", "账单逾期了会有什么影响"),
            new CandidateExample("wallet_credit", "给账户充值没有到账"),
            new CandidateExample("wallet_credit", "还款方式有哪些"),
            new CandidateExample("wallet_credit", "企业账期额度不够用怎么提额"),
            // membership_activity 会员与活动
            new CandidateExample("membership_activity", "会员等级怎么提升"),
            new CandidateExample("membership_activity", "积分可以抵扣现金吗"),
            new CandidateExample("membership_activity", "最近有什么促销优惠券可以领"),
            new CandidateExample("membership_activity", "满减活动和会员折扣能叠加吗"),
            new CandidateExample("membership_activity", "会员积分在哪里查看"),
            new CandidateExample("membership_activity", "会员日有什么优惠活动"),
            // message_privacy 消息与隐私
            new CandidateExample("message_privacy", "怎么关闭站内信通知"),
            new CandidateExample("message_privacy", "系统消息太多想设置免打扰"),
            new CandidateExample("message_privacy", "我的个人信息会被泄露吗"),
            new CandidateExample("message_privacy", "隐私政策在哪里查看"),
            new CandidateExample("message_privacy", "关闭 cookie 之后还能正常使用吗"),
            new CandidateExample("message_privacy", "不想收到平台的推销通知"),
            // logistics_tracking 物流与配送
            new CandidateExample("logistics_tracking", "我的快递到哪里了"),
            new CandidateExample("logistics_tracking", "物流信息一直不更新"),
            new CandidateExample("logistics_tracking", "怎么查询运单号和配送进度"),
            new CandidateExample("logistics_tracking", "订单显示已发货但一直没收到包裹"),
            new CandidateExample("logistics_tracking", "运费是怎么计算的"),
            new CandidateExample("logistics_tracking", "快递到哪了怎么还没送到"),
            // refund_aftersale 退款与售后
            new CandidateExample("refund_aftersale", "商品有质量问题想申请退款"),
            new CandidateExample("refund_aftersale", "收到的东西损坏了怎么换货"),
            new CandidateExample("refund_aftersale", "七天无理由退货怎么操作"),
            new CandidateExample("refund_aftersale", "申请的退款什么时候能到账"),
            new CandidateExample("refund_aftersale", "少发了一个配件想补发"),
            new CandidateExample("refund_aftersale", "收到的钢材规格不对想退款"),
            // payment_invoice 支付与发票
            new CandidateExample("payment_invoice", "订单怎么开发票"),
            new CandidateExample("payment_invoice", "开增值税专用发票需要提供什么"),
            new CandidateExample("payment_invoice", "付款失败了怎么重新支付"),
            new CandidateExample("payment_invoice", "可以用对公转账付款吗"),
            new CandidateExample("payment_invoice", "发票抬头开错了能重新开吗"),
            new CandidateExample("payment_invoice", "发票什么时候能开出来"),
            // complaint_human 人工客服与投诉
            new CandidateExample("complaint_human", "我要找人工客服处理"),
            new CandidateExample("complaint_human", "帮我转人工客服"),
            new CandidateExample("complaint_human", "我要投诉商家的服务态度"),
            new CandidateExample("complaint_human", "对处理结果不满意想投诉"),
            new CandidateExample("complaint_human", "在线人工客服几点上班"),
            new CandidateExample("complaint_human", "订单发货太慢我要投诉客服")
    );
}

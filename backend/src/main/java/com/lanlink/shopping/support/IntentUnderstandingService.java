package com.lanlink.shopping.support;

import com.lanlink.shopping.module.aikb.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 意图理解服务：本地规则分类为主 + 本地 7B 文本模型 + 混合向量分类 + 外部模型可选增强。
 *
 * <p>降级顺序（逐级兜底，任一级失败都不影响最终返回）：
 * <ol>
 *   <li><b>external</b>：既有外部模型增强（后台开关 intent_external_enabled + 白名单，最高优先级，
 *       与升级前行为一致）；</li>
 *   <li><b>local</b>：11 类意图关键词规则。关键词按「长度 + 命中位置」加权（越靠前权重越高，
 *       长关键词额外加成），加权分最高且领先次优 ≥1 即视为规则决胜，<b>不再调用模型/向量</b>；</li>
 *   <li><b>llm</b>：规则未命中或真正歧义（打平）时，优先调用本地 qwen2.5-7B 文本模型
 *       （开关 AI_INTENT_LLM_ENABLED，超时 12s，只允许输出 11 个 code 之一，白名单校验）；</li>
 *   <li><b>vector</b>：本地模型不可用/输出非法时才用向量 hybrid 兜底
 *       （0.55×嵌入余弦 + 0.45×字符 bigram 词面覆盖度 + 规则命中先验 +0.10，要求
 *       最高分 ≥ AI_INTENT_VECTOR_MIN_SCORE 且分差 ≥0.02）；</li>
 *   <li>最终仍无法判定 → 保留规则结果（local）。</li>
 * </ol>
 *
 * <p>约束：外部模型/本地文本模型都只输出预定义意图标签，绝不生成回答文本；结果 candidates
 * 合并规则/本地模型/向量三路来源；每次分析写意图日志供统计。
 */
@Service
public class IntentUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(IntentUnderstandingService.class);

    /** 规则决胜最小加权分差（≥1 个加权单位即认为规则明确，不再走模型/向量） */
    private static final int DECISIVE_MARGIN = 1;
    /** 本地文本模型分类超时（秒）：超时即回退向量/规则，不拖慢客服接口 */
    private static final long LLM_TIMEOUT_SECONDS = 12;
    /** 位置加权：命中位置 0-4 → +2，5-9 → +1，更晚不加成 */
    private static final int POSITION_BONUS_NEAR = 2;
    private static final int POSITION_BONUS_MID = 1;

    private static final List<IntentRule> RULES = List.of(
            new IntentRule("account_access", "账号注册与登录", List.of(
                    "注册", "登录", "登陆", "验证码", "密码", "账号", "找回密码", "修改密码",
                    "短信验证码", "注销", "绑定手机", "换绑", "忘记密码", "安全验证", "重置密码",
                    // 英文问法（客服双语支持）
                    "register", "registration", "sign up", "signup", "sign in", "login", "log in",
                    "password", "verification code", "verify code", "sms code", "account", "locked",
                    "reset password", "forgot password", "two-factor", "2fa")),
            new IntentRule("merchant_onboarding", "商户入驻", List.of(
                    "商户", "供应商", "入驻", "开店", "招商", "企业入驻", "资质审核", "保证金",
                    "上架商品", "商户合作", "供应商入驻",
                    // 英文问法
                    "merchant", "merchants", "onboarding", "onboard", "supplier", "suppliers",
                    "vendor", "seller", "deposit", "qualification", "kyc")),
            new IntentRule("product_browse", "商品浏览", List.of(
                    "商品", "产品", "商城", "分类", "行业", "规格", "参数", "型号", "材质",
                    "库存", "现货", "详情", "保质期",
                    // 价格类问法（"螺纹钢现在什么价格？"等询价场景）
                    "价格", "报价", "询价", "多少钱", "涨价", "降价", "行情",
                    // 英文问法
                    "product", "products", "specification", "specifications", "spec", "specs",
                    "parameter", "parameters", "model", "models", "material", "materials",
                    "category", "categories", "catalog", "in stock", "stock", "price", "prices",
                    "quote", "quotation", "pricing", "cost", "how much")),
            new IntentRule("cart_order", "购物车与订单", List.of(
                    "购物车", "结算", "订单", "下单", "采购", "提交订单", "订单号", "改地址",
                    "催单", "取消订单", "合并付款",
                    // 英文问法
                    "order", "orders", "cart", "shopping cart", "checkout", "purchase", "purchases",
                    "procurement", "order number", "cancel order", "delivery address",
                    "shipping address")),
            new IntentRule("wallet_credit", "钱包与账期", List.of(
                    "钱包", "充值", "提现", "账期", "授信", "额度", "还款", "账单", "逾期",
                    "账户余额", "转账",
                    // 英文问法
                    "wallet", "credit", "credit line", "credit limit", "repay", "repayment",
                    "balance", "account balance", "recharge", "top up", "withdraw", "withdrawal",
                    "bill", "bills", "overdue", "installment", "installments", "transfer")),
            new IntentRule("membership_activity", "会员与活动", List.of(
                    "会员", "积分", "活动", "促销", "优惠", "优惠券", "满减", "折扣", "秒杀",
                    "会员等级", "抵扣",
                    // 英文问法
                    "member", "membership", "points", "coupon", "coupons", "discount",
                    "promotion", "activity", "campaign", "loyalty", "redeem", "reward", "rewards",
                    "vip")),
            new IntentRule("message_privacy", "消息与隐私", List.of(
                    "消息", "通知", "cookie", "隐私", "站内信", "个人信息", "权限", "免打扰",
                    "隐私政策",
                    // 英文问法
                    "message", "messages", "notification", "notifications", "notice", "privacy",
                    "cookies", "personal information", "opt out", "do not disturb", "permission",
                    "permissions", "unsubscribe")),
            new IntentRule("logistics_tracking", "物流与配送", List.of(
                    "物流", "快递", "运单", "发货", "签收", "配送", "运费", "揽收", "快递单号",
                    "到哪了", "运输中", "派件", "查物流", "包裹", "几天到",
                    // 英文问法
                    "shipping", "shipment", "shipments", "tracking", "track", "delivery",
                    "logistics", "carrier", "waybill", "parcel", "parcels", "package", "packages",
                    "freight", "express", "dispatch", "where is my order")),
            new IntentRule("refund_aftersale", "退款与售后", List.of(
                    "退款", "退货", "售后", "换货", "维修", "赔付", "退运费", "退差价", "损坏",
                    "补发", "质量问题", "7天无理由", "七天无理由", "不想要了",
                    // 英文问法
                    "refund", "refunds", "return", "returns", "exchange", "after-sales",
                    "aftersales", "after sales", "repair", "warranty", "damage", "damaged",
                    "defective", "replacement", "compensation", "missing item", "quality issue",
                    "broken")),
            new IntentRule("payment_invoice", "支付与发票", List.of(
                    "支付", "付款", "发票", "开票", "报销", "对公", "微信支付", "支付宝", "增值税",
                    "专票", "普票", "税号", "付款失败",
                    // 英文问法
                    "payment", "payments", "pay", "paid", "invoice", "invoices", "billing",
                    "receipt", "reimbursement", "reimburse", "tax", "vat", "corporate payment")),
            new IntentRule("complaint_human", "人工客服与投诉", List.of(
                    "人工", "客服", "找人工", "转人工", "人工客服", "真人", "在线人工", "投诉",
                    "差评", "态度恶劣", "投诉商家",
                    // 英文问法
                    "human agent", "human", "agent", "customer service", "customer support",
                    "complaint", "complaints", "complain", "escalate", "representative",
                    "live agent", "transfer me"))
    );

    /** 本地文本模型可选切换的意图标签全集（11 类，不含 other） */
    private static final List<String> INTENT_CODES = RULES.stream().map(IntentRule::code).toList();

    /** 本地 7B 分类提示词：固定 system，只允许输出 11 个 code 之一（白名单二次校验） */
    private static final String LLM_INTENT_SYSTEM_PROMPT =
            "你是意图分类器。只输出一个英文标签，不要输出任何其他文字、解释或标点。"
                    + "可选标签：account_access（账号注册登录）、merchant_onboarding（商户入驻）、"
                    + "product_browse（商品浏览）、cart_order（购物车订单）、wallet_credit（钱包账期）、"
                    + "membership_activity（会员活动）、message_privacy（消息隐私）、"
                    + "logistics_tracking（物流追踪）、refund_aftersale（退款售后）、"
                    + "payment_invoice（支付发票）、complaint_human（人工客服与投诉）。";

    private final OutboundModelConfigService configService;
    private final ExternalIntentClient externalIntentClient;
    private final JdbcTemplate jdbc;
    /** 混合向量分类（可空：纯本地模式/旧测试构造器） */
    private final IntentVectorService intentVectorService;
    /** 本地文本模型入口（可空：纯本地模式/旧测试构造器） */
    private final EmbeddingService embeddingService;

    /** 本地 7B 意图兜底开关（AI_INTENT_LLM_ENABLED，默认开启） */
    @Value("${AI_INTENT_LLM_ENABLED:true}")
    private boolean llmEnabled = true;

    /** 纯本地模式（测试/无外部依赖场景） */
    public IntentUnderstandingService() {
        this(null, null, null, null, null);
    }

    /** 兼容旧构造（外部增强测试/纯本地模式）：不启用向量与本地文本模型 */
    public IntentUnderstandingService(OutboundModelConfigService configService,
                                      ExternalIntentClient externalIntentClient,
                                      JdbcTemplate jdbc) {
        this(configService, externalIntentClient, jdbc, null, null);
    }

    @Autowired
    public IntentUnderstandingService(OutboundModelConfigService configService,
                                      ExternalIntentClient externalIntentClient,
                                      JdbcTemplate jdbc,
                                      IntentVectorService intentVectorService,
                                      EmbeddingService embeddingService) {
        this.configService = configService;
        this.externalIntentClient = externalIntentClient;
        this.jdbc = jdbc;
        this.intentVectorService = intentVectorService;
        this.embeddingService = embeddingService;
    }

    /**
     * 快速意图（仅规则加权，不调用外部模型 / 本地 7B / 向量）：
     * 供客服应答附加意图元数据，避免歧义问题在响应链路上同步等待 7B（最长 12s）。
     */
    public Result analyzeFast(String question) {
        String text = normalize(question);
        IntentRule best = null;
        int bestScore = 0;
        int second = 0;
        for (IntentRule rule : RULES) {
            int score = weightedRuleScore(rule, text);
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = rule;
            } else if (score > second) {
                second = score;
            }
        }
        boolean decisive = best != null && bestScore > 0 && (bestScore - second) >= DECISIVE_MARGIN;
        String intent = best == null ? "other" : best.code();
        String category = best == null ? "其他" : best.label();
        double confidence = best == null ? 0.0 : Math.min(0.99, 0.55 + bestScore * 0.08);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("category", category);
        result.put("confidence", confidence);
        result.put("ambiguous", !decisive);
        result.put("source", "local-fast");
        result.put("clarification", decisive ? "" : "请说明您要处理的是账号、商品、订单、商户、钱包、物流还是售后问题。");
        result.put("reason", "规则快速匹配（应答元数据，不调用模型）。");
        result.put("candidates", ruleCandidates(text));
        result.put("vectorScore", null);
        logIntent(question, intent, "local-fast");
        return new Result(result);
    }

    public Result analyze(String question, List<String> recentTurns) {
        String text = normalize(question);
        String context = recentTurns == null ? "" : normalize(String.join(" ", recentTurns));
        String combined = text + " " + context;

        // 1) 规则加权打分 + 位置决胜（越靠前权重越高，长关键词额外加成）
        IntentRule best = null;
        int bestScore = 0;
        int second = 0;
        for (IntentRule rule : RULES) {
            int score = weightedRuleScore(rule, combined);
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = rule;
            } else if (score > second) {
                second = score;
            }
        }
        boolean rulesDecisive = best != null && bestScore > 0 && (bestScore - second) >= DECISIVE_MARGIN;
        String intent = best == null ? "other" : best.code();
        String category = best == null ? "其他" : best.label();
        String source = "local";
        String reason = best == null ? "未命中已配置意图；建议澄清业务对象或操作。"
                : rulesDecisive ? "根据当前问题及最近对话关键词加权匹配（命中位置越靠前权重越高）。"
                : "命中多个相近意图且无法决胜；需要补充业务对象或具体操作。";
        double confidence = best == null ? 0.0
                : Math.min(0.99, 0.55 + bestScore * 0.08 - (rulesDecisive ? 0 : 0.12));
        Double vectorScore = null;
        boolean ambiguous = !rulesDecisive;
        List<Map<String, Object>> ruleCandidates = ruleCandidates(combined);
        List<Map<String, Object>> vectorCandidates = List.of();

        // 2) 既有外部模型增强：仅在规则无法决胜时作为增强——
        //    规则明确决胜时不被外部模型覆盖（实测外部模型会把"订单+发货"误判为物流，
        //    而关键词加权已能稳定判定为购物车订单）。外部模型仍保留"只出标签、白名单校验"约束。
        String externalLabel = rulesDecisive ? null : tryExternalClassify(question);
        if (externalLabel != null) {
            intent = externalLabel;
            category = labelOf(externalLabel, category);
            source = "external";
            reason = "规则未命中或存在歧义，由已配置的外部模型增强判定（意图标签白名单校验通过）。";
        } else if (!rulesDecisive) {
            // 3) 规则未命中/真正歧义 → 优先本地 7B 文本模型（白名单标签）
            String llmLabel = tryLlmClassify(question);
            if (llmLabel != null) {
                intent = llmLabel;
                category = labelOf(llmLabel, category);
                confidence = 0.8;
                source = "llm";
                ambiguous = false;
                reason = "规则未命中或存在歧义，由本地 qwen2.5 文本模型判定（意图标签白名单校验通过）。";
            } else {
                // 4) 本地模型不可用 → 向量 hybrid 兜底（规则命中意图带 +0.10 先验）
                IntentVectorService.Classification vector = tryVectorClassify(question, ruleCandidates);
                if (vector != null) {
                    intent = vector.intent();
                    category = vector.label();
                    confidence = Math.min(0.99, vector.score());
                    source = "vector";
                    vectorScore = vector.score();
                    ambiguous = false;
                    reason = "规则未命中或存在歧义，向量 hybrid 检索（0.55×嵌入余弦 + 0.45×字面 bigram）"
                            + "匹配最相近意图示例（得分 " + formatScore(vector.score())
                            + "，与次优意图分差 " + formatScore(vector.margin()) + "）。";
                    List<Map<String, Object>> mergedVector = new ArrayList<>();
                    for (IntentVectorService.Candidate candidate : vector.candidates()) {
                        mergedVector.add(candidate.toMap());
                    }
                    vectorCandidates = mergedVector;
                }
            }
        }

        // candidates 合并三路来源（规则 / 本地模型 / 向量），采纳意图优先，最多 3 条
        List<Map<String, Object>> candidates = "local".equals(source)
                ? (best == null ? ruleCandidates
                        : mergeCandidates(intent, category, (double) bestScore, ruleCandidates, null))
                : mergeCandidates(intent, category,
                        "vector".equals(source) && vectorScore != null ? vectorScore : confidence,
                        ruleCandidates, vectorCandidates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent);
        result.put("category", category);
        result.put("confidence", confidence);
        result.put("ambiguous", ambiguous);
        result.put("source", source);
        result.put("clarification", ambiguous ? "请说明您要处理的是账号、商品、订单、商户、钱包、物流还是售后问题。" : "");
        result.put("reason", reason);
        result.put("candidates", candidates == null ? List.of() : candidates);
        result.put("vectorScore", vectorScore);
        logIntent(question, intent, source);
        return new Result(result);
    }

    // ==================== 规则打分（加权 + 位置决胜） ====================

    /**
     * 规则加权打分：每个关键词命中的权重 = 关键词长度 + 长词加成(len/4) + 位置加成
     * （命中位置 0-4 → +2，5-9 → +1，更晚 0），重复命中累加。
     * 例如"我要退款，买的钢材规格不对"中靠前的"退款"得分高于靠后的"规格"，避免打平误判。
     */
    private static int weightedRuleScore(IntentRule rule, String combined) {
        if (rule == null || combined == null || combined.isEmpty()) return 0;
        int score = 0;
        for (String keyword : rule.keywords()) {
            int from = 0;
            while (from < combined.length()) {
                int position = combined.indexOf(keyword, from);
                if (position < 0) break;
                score += keywordWeight(keyword, position);
                from = position + keyword.length();
            }
        }
        return score;
    }

    /** 单个关键词命中权重 */
    static int keywordWeight(String keyword, int position) {
        int weight = keyword.length() + keyword.length() / 4;
        if (position <= 4) {
            weight += POSITION_BONUS_NEAR;
        } else if (position <= 9) {
            weight += POSITION_BONUS_MID;
        }
        return weight;
    }

    /** 规则候选意图（按加权得分降序，最多 3 条，供前端展示/调试与向量先验） */
    private static List<Map<String, Object>> ruleCandidates(String combined) {
        List<Map.Entry<IntentRule, Integer>> scored = new ArrayList<>();
        for (IntentRule rule : RULES) {
            int score = weightedRuleScore(rule, combined);
            if (score > 0) scored.add(Map.entry(rule, score));
        }
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<IntentRule, Integer> entry : scored) {
            if (out.size() >= 3) break;
            out.add(candidate(entry.getKey().code(), entry.getKey().label(), (double) entry.getValue()));
        }
        return out;
    }

    /** candidates 合并：采纳意图优先，其次向量候选，再次规则候选，按 intent 去重后最多 3 条 */
    private static List<Map<String, Object>> mergeCandidates(String adoptedIntent, String adoptedLabel,
                                                             Double adoptedScore,
                                                             List<Map<String, Object>> ruleCandidates,
                                                             List<Map<String, Object>> vectorCandidates) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        if (adoptedIntent != null && !"other".equals(adoptedIntent) && adoptedLabel != null) {
            merged.put(adoptedIntent, candidate(adoptedIntent, adoptedLabel,
                    adoptedScore == null ? 0.0 : adoptedScore));
        }
        if (vectorCandidates != null) {
            for (Map<String, Object> candidate : vectorCandidates) {
                Object intent = candidate.get("intent");
                if (intent instanceof String code) merged.putIfAbsent(code, candidate);
            }
        }
        if (ruleCandidates != null) {
            for (Map<String, Object> candidate : ruleCandidates) {
                Object intent = candidate.get("intent");
                if (intent instanceof String code) merged.putIfAbsent(code, candidate);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.values());
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    private static Map<String, Object> candidate(String intent, String label, Double score) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("intent", intent);
        candidate.put("label", label);
        candidate.put("score", score);
        return candidate;
    }

    // ==================== 增强链路 ====================

    /** 向量 hybrid 分类：服务缺失/低于阈值/分差不足时返回 null（静默回退规则） */
    private IntentVectorService.Classification tryVectorClassify(String question,
                                                                 List<Map<String, Object>> ruleCandidates) {
        if (intentVectorService == null) return null;
        try {
            List<String> ruleIntents = new ArrayList<>();
            if (ruleCandidates != null) {
                for (Map<String, Object> candidate : ruleCandidates) {
                    Object intent = candidate.get("intent");
                    if (intent instanceof String code) ruleIntents.add(code);
                }
            }
            IntentVectorService.Classification classification = intentVectorService.classify(question, ruleIntents);
            if (classification == null) return null;
            if (classification.score() < intentVectorService.minScore()) return null;
            if (!INTENT_CODES.contains(classification.intent())) return null;
            return classification;
        } catch (Exception e) {
            log.warn("[intent-enhance] vector classify failed, fallback local rules: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 本地 qwen2.5 文本模型意图判定（兜底）：只接受 11 个预定义 code 之一，
     * 非白名单输出/调用失败/超时一律返回 null（保持规则结果，不影响主链路）。
     */
    private String tryLlmClassify(String question) {
        if (!llmEnabled || embeddingService == null) return null;
        if (question == null || question.isBlank()) return null;
        try {
            String raw = CompletableFuture
                    .supplyAsync(() -> embeddingService.chatText(LLM_INTENT_SYSTEM_PROMPT, "用户问题：" + question))
                    .get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String label = parseLlmLabel(raw);
            if (label == null) {
                log.debug("[intent-enhance] local text model output discarded (not in whitelist)");
            }
            return label;
        } catch (Exception e) {
            log.warn("[intent-enhance] local text model classify failed, fallback to vector/rules: {}", e.getMessage());
            return null;
        }
    }

    /** 解析文本模型输出中的第一处白名单 code（大小写/标点容错；非法输出返回 null） */
    static String parseLlmLabel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.toLowerCase(Locale.ROOT);
        String matched = null;
        int matchedAt = Integer.MAX_VALUE;
        for (String code : INTENT_CODES) {
            int index = indexOfToken(text, code);
            if (index >= 0 && index < matchedAt) {
                matched = code;
                matchedAt = index;
            }
        }
        return matched;
    }

    /** 按词边界查找 code（避免 cart_order 命中 xcart_orderx 之类的自由文本） */
    private static int indexOfToken(String text, String token) {
        int from = 0;
        while (from <= text.length() - token.length()) {
            int index = text.indexOf(token, from);
            if (index < 0) return -1;
            int end = index + token.length();
            boolean leftOk = index == 0 || !isLabelChar(text.charAt(index - 1));
            boolean rightOk = end >= text.length() || !isLabelChar(text.charAt(end));
            if (leftOk && rightOk) return index;
            from = index + 1;
        }
        return -1;
    }

    private static boolean isLabelChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String labelOf(String code, String fallback) {
        return RULES.stream().filter(r -> r.code().equals(code))
                .map(IntentRule::label).findFirst().orElse(fallback);
    }

    private static String formatScore(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 外部意图增强（可选）：开关关闭/未配置/调用失败/违规一律返回 null 回退本地 */
    private String tryExternalClassify(String question) {
        if (configService == null || externalIntentClient == null) return null;
        try {
            if (!configService.toggle("intent_external_enabled")) return null;
            FallbackPolicyGuard.Intent guardIntent = FallbackPolicyGuard.classify(question);
            String denial = FallbackPolicyGuard.authorizeExternalIntent(guardIntent, true);
            if (denial != null) return null;
            String label = externalIntentClient.classify(question, guardIntent);
            // 纵深防御：服务层再校验一次白名单（即使客户端实现有缺陷也不会放行非法标签）
            if (!FallbackPolicyGuard.isSafeIntentLabel(label, ExternalIntentClient.ALLOWED_LABELS)) return null;
            return label;
        } catch (Exception e) {
            log.warn("[intent-enhance] external classify failed, fallback local: {}", e.getMessage());
            return null;
        }
    }

    /** 意图日志（统计用，静默失败不影响主链路） */
    private void logIntent(String question, String intent, String source) {
        if (jdbc == null) return;
        try {
            String hash = CustomerCommunicationService.sha256(
                    question == null ? "" : question.trim());
            jdbc.update("INSERT INTO t_support_intent_log (question_hash, intent, source, created_at) VALUES (?, ?, ?, NOW())",
                    hash, intent, source);
        } catch (Exception e) {
            log.debug("[intent-log] write failed: {}", e.getMessage());
        }
    }

    /** 意图统计（管理端）：按意图/来源分组计数 */
    public List<Map<String, Object>> stats() {
        if (jdbc == null) return List.of();
        try {
            return jdbc.queryForList(
                    "SELECT intent, source, COUNT(*) AS count FROM t_support_intent_log "
                            + "GROUP BY intent, source ORDER BY count DESC LIMIT 50");
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 本地文本模型开关（测试/运维动态关闭用；默认读 AI_INTENT_LLM_ENABLED） */
    void setLlmEnabled(boolean enabled) {
        this.llmEnabled = enabled;
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    public record Result(Map<String, Object> data) {}
    private record IntentRule(String code, String label, List<String> keywords) {}
}

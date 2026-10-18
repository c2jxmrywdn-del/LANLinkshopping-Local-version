package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Every answer returned here is an exact string loaded from the local knowledge base. */
@Service
public class SupportAnswerService {
    private static final int MAX_QUESTION_LENGTH = 300;
    private static final List<String> INJECTION_SIGNALS = List.of(
            "system prompt", "developer message", "prompt injection", "jailbreak",
            "ignore previous instructions", "ignore all instructions", "disregard instructions",
            "reverse proxy", "api key", "access token", "environment variable", "shell command",
            "执行代码", "运行代码", "生成代码", "输出代码", "工具调用", "调用工具",
            "系统提示词", "系统指令", "内部配置", "环境变量", "反向代理", "跨域调用",
            "知识库绕过", "提示注入", "越权", "管理员权限", "显示密钥", "读取配置",
            "输出提示词", "打印系统提示", "泄露配置", "秘密指令", "内部规则", "绕过限制"
    );
    private static final List<String> UNSUPPORTED_BUSINESS_SIGNALS = List.of(
            "价格", "报价", "库存", "现货", "物流", "快递", "运费", "发货", "配送",
            "退货", "退款", "售后", "发票", "支付", "付款", "到账", "提现", "优惠券",
            "交货期", "到货时间", "具体规格", "商品规格", "成分", "材质", "支付失败",
            "密码重置", "重置密码", "找回密码", "忘记密码", "修改密码",
            "订单状态", "订单号", "查询余额", "余额是多少", "我的余额", "我的积分",
            "积分余额", "我的订单状态", "授信额度", "账期额度", "申请条件", "审核结果",
            "我的收货地址", "地址是什么", "具体地址", "读取地址", "我的手机号", "身份证号",
            "我的密码", "个人资料内容", "私人信息", "用户隐私数据", "个人订单", "订单个人信息",
            "活动规则", "优惠规则", "用户数据", "用户信息", "账号申诉"
    );
    // Only questions within the platform's supported e-commerce domains can enter the learning sandbox.
    private static final List<String> LEARNING_DOMAIN_SIGNALS = List.of(
            "账号", "注册", "登录", "登陆", "验证码", "商品", "产品", "商城", "分类",
            "购物车", "结算", "订单", "下单", "商户", "供应商", "入驻", "开店",
            "钱包", "充值", "账期", "会员", "积分", "活动", "促销", "优惠",
            "消息", "通知", "cookie", "隐私", "收货地址", "地址管理"
    );
    private static final Pattern INJECTION_COMBINATION = Pattern.compile(
            "(?i)(ignore|disregard|forget|override|bypass)[\\s\\S]{0,80}(instruction|prompt|system|policy|knowledge base|rules)"
    );
    private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    /** 所有回复统一使用的标准问候语前缀（沟通礼仪）。 */
    private static final String GREETING_PREFIX = "您好！";
    /** 纯寒暄问候：仅含问候语（可重复、可带语气标点），走独立应答而非拒答兜底。 */
    private static final Pattern PURE_GREETING = Pattern.compile(
            "^(?:(?:你好|您好|哈喽|哈啰|嗨|嘿|在吗|在不在|在么|有人吗|早上好|中午好|下午好|晚上好|"
                    + "hi|hello|hey|good morning|good afternoon|good evening)"
                    + "[\\s!！?？。.，,、～~·啊呀嘛呗哦呢哈]*)+$", Pattern.CASE_INSENSITIVE);
    /** 自我介绍 / 能力说明类提问，走自我介绍应答。 */
    private static final Pattern SELF_INTRO = Pattern.compile(
            "^(?:你叫什么(?:名字)?|你叫啥|你是谁|你是什么(?:人|东西|客服|机器人|智能体)?|"
                    + "介绍(?:一下)?(?:你)?自己|你能做什么|你会什么|你能帮(?:我)?(?:做)?什么|"
                    + "你有什么(?:功能|用|作用|本事)|你能回答什么|你都会什么|"
                    + "who are you|what(?:'s| is) your name|what can you do)"
                    + "[\\s!！?？。.，,、～~·啊呀嘛呗哦呢]*$", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_GREETING =
            "您好！我是 LANLinkshopping 专属智能客服，很高兴为您服务。请问有什么可以帮您？";
    private static final String DEFAULT_SELF_INTRO =
            "您好！我是 LANLinkshopping 专属智能客服，可解答平台账号注册与登录、商品浏览、购物车与订单、"
                    + "商户入驻、钱包与账期、会员与活动、站内消息及 Cookie 设置等电商问题。请问您想了解哪一方面？";

    private final ObjectMapper objectMapper;
    private final Resource knowledgeBaseResource;
    private final JdbcTemplate jdbcTemplate;
    private volatile String fallback;
    private volatile String welcome;
    private volatile String greeting;
    private volatile String selfIntro;
    private volatile List<Entry> entries = List.of();
    private volatile long publishedCacheLoadedAtMs;
    private volatile List<Entry> publishedEntriesCache = List.of();
    private final Object publishedCacheLock = new Object();

    @Autowired
    public SupportAnswerService(
            ObjectMapper objectMapper,
            @Value("classpath:knowledge-base/customer-service-zh-CN.json") Resource knowledgeBaseResource,
            JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.knowledgeBaseResource = knowledgeBaseResource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Kept for the isolated unit tests that exercise the static knowledge base without a database. */
    public SupportAnswerService(ObjectMapper objectMapper, Resource knowledgeBaseResource) {
        this.objectMapper = objectMapper;
        this.knowledgeBaseResource = knowledgeBaseResource;
        this.jdbcTemplate = null;
    }

    public boolean hasStaticEntryId(String entryId) {
        if (!StringUtils.hasText(entryId)) return false;
        return entries.stream().anyMatch(entry -> entry.id().equals(entryId.trim()));
    }

    @PostConstruct
    public void loadKnowledgeBase() throws IOException {
        JsonNode root;
        try (var in = knowledgeBaseResource.getInputStream()) {
            root = objectMapper.readTree(in);
        }
        String loadedFallback = root.path("fallback").asText("");
        String loadedWelcome = root.path("welcome").asText("");
        if (!StringUtils.hasText(loadedFallback) || !root.path("entries").isArray()) {
            throw new IllegalStateException("Customer support knowledge base is missing required fields");
        }
        List<Entry> loadedEntries = new ArrayList<>();
        for (JsonNode node : root.path("entries")) {
            String id = node.path("id").asText("");
            String answer = node.path("answer").asText("");
            JsonNode keywordNodes = node.path("keywords");
            List<String> keywords = new ArrayList<>();
            if (keywordNodes.isArray()) {
                keywordNodes.forEach(keyword -> {
                    if (keyword.isTextual() && StringUtils.hasText(keyword.asText())) {
                        keywords.add(normalize(keyword.asText()));
                    }
                });
            }
            if (!StringUtils.hasText(id) || !StringUtils.hasText(answer) || keywords.isEmpty()) {
                throw new IllegalStateException("Customer support knowledge base contains an incomplete entry");
            }
            loadedEntries.add(new Entry(id, List.copyOf(keywords), answer));
        }
        if (loadedEntries.isEmpty()) throw new IllegalStateException("Customer support knowledge base must not be empty");
        this.fallback = loadedFallback;
        this.welcome = StringUtils.hasText(loadedWelcome) ? loadedWelcome : loadedFallback;
        String loadedGreeting = root.path("greeting").asText("");
        String loadedSelfIntro = root.path("selfIntro").asText("");
        this.greeting = StringUtils.hasText(loadedGreeting) ? loadedGreeting : DEFAULT_GREETING;
        this.selfIntro = StringUtils.hasText(loadedSelfIntro) ? loadedSelfIntro : DEFAULT_SELF_INTRO;
        this.entries = List.copyOf(loadedEntries);
    }

    public String fallback() { return fallback; }
    public String welcome() { return welcome; }
    public String greeting() { return greeting; }
    public String selfIntro() { return selfIntro; }

    public String answer(String rawQuestion) {
        String question = validatedQuestion(rawQuestion);
        // 纯寒暄（你好/在吗 等）：直接问候应答，不再落入拒答兜底。
        if (question != null && PURE_GREETING.matcher(question).matches()) {
            return withGreeting(greeting);
        }
        // 自我介绍 / 能力说明类提问：返回客服身份与能力范围。
        if (question != null && SELF_INTRO.matcher(question).matches()) {
            return withGreeting(selfIntro);
        }
        if (question == null || isBlockedQuestion(question)) return withGreeting(fallback);
        Entry entry = findEntry(question);
        return entry == null ? withGreeting(fallback) : withGreeting(entry.answer());
    }

    /** 统一在回复开头加标准问候语；已带问候语的文本（欢迎语/兜底等）不重复添加。 */
    private String withGreeting(String text) {
        if (!StringUtils.hasText(text)) return text;
        String trimmed = text.trim();
        if (trimmed.startsWith("您好") || trimmed.startsWith("你好") || trimmed.startsWith(GREETING_PREFIX)) {
            return trimmed;
        }
        return GREETING_PREFIX + trimmed;
    }

    /** Exposes the intent selected by the exact same matcher for the admin-only sandbox. */
    public String matchedEntryId(String rawQuestion) {
        String question = validatedQuestion(rawQuestion);
        if (question == null || isBlockedQuestion(question)) return "";
        Entry entry = findEntry(question);
        return entry == null ? "" : entry.id();
    }

    /**
     * A question may be considered for a human-reviewed draft only when it is safe
     * and clearly within the platform's documented e-commerce scope.
     */
    public boolean isEligibleForKnowledgeLearning(String rawQuestion) {
        String question = validatedQuestion(rawQuestion);
        return question != null && !isBlockedQuestion(question)
                && containsAny(question, LEARNING_DOMAIN_SIGNALS);
    }

    /** Candidate keywords are tested in memory only; they are never persisted by this service. */
    public boolean candidateKeywordsMatch(String rawQuestion, List<String> candidateKeywords) {
        if (!isEligibleForKnowledgeLearning(rawQuestion) || candidateKeywords == null) return false;
        String question = validatedQuestion(rawQuestion);
        return candidateKeywords.stream()
                .filter(StringUtils::hasText)
                .filter(keyword -> keyword.length() <= 80)
                .map(SupportAnswerService::normalize)
                .filter(StringUtils::hasText)
                .anyMatch(question::contains);
    }

    private String validatedQuestion(String rawQuestion) {
        if (!StringUtils.hasText(rawQuestion) || rawQuestion.length() > MAX_QUESTION_LENGTH) return null;
        if (rawQuestion.chars().anyMatch(Character::isISOControl)
                || rawQuestion.contains("<") || rawQuestion.contains(">")
                || rawQuestion.indexOf(96) >= 0) return null;
        String question = normalize(INVISIBLE_CHARACTERS.matcher(rawQuestion).replaceAll(""));
        return question.isEmpty() ? null : question;
    }

    private boolean isBlockedQuestion(String question) {
        return isPromptInjection(question) || containsAny(question, UNSUPPORTED_BUSINESS_SIGNALS);
    }

    private Entry findEntry(String question) {
        List<Entry> available = new ArrayList<>(entries);
        available.addAll(publishedEntries());
        return available.stream()
                .map(entry -> new Match(entry, entry.keywords().stream()
                        .filter(question::contains)
                        .max(Comparator.comparingInt(String::length)).orElse(null)))
                .filter(match -> match.keyword() != null)
                .max(Comparator.comparingInt(match -> match.keyword().length()))
                .map(Match::entry)
                .orElse(null);
    }

    /** Only admin-published, human-reviewed candidates join the live knowledge matcher. */
    private List<Entry> publishedEntries() {
        if (jdbcTemplate == null) return List.of();
        long now = System.currentTimeMillis();
        if (now - publishedCacheLoadedAtMs < 5_000L) return publishedEntriesCache;
        synchronized (publishedCacheLock) {
            now = System.currentTimeMillis();
            if (now - publishedCacheLoadedAtMs < 5_000L) return publishedEntriesCache;
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT entry_id AS entryId, keywords_json AS keywordsJson, answer_text AS answerText " +
                        "FROM t_support_learning_candidate WHERE status = 'published' ORDER BY published_at DESC, id DESC LIMIT 500");
                List<Entry> published = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Object idValue = row.get("entryId");
                    Object keywordValue = row.get("keywordsJson");
                    Object answerValue = row.get("answerText");
                    if (idValue == null || keywordValue == null || answerValue == null) continue;
                    try {
                        List<String> keywords = objectMapper.readValue(
                                String.valueOf(keywordValue), new TypeReference<List<String>>() {});
                        List<String> normalized = keywords.stream()
                                .filter(StringUtils::hasText)
                                .map(SupportAnswerService::normalize)
                                .filter(StringUtils::hasText)
                                .toList();
                        String text = String.valueOf(answerValue);
                        if (!normalized.isEmpty() && StringUtils.hasText(text)) {
                            published.add(new Entry(String.valueOf(idValue), normalized, text));
                        }
                    } catch (JsonProcessingException ignored) {
                        // Ignore one malformed candidate without affecting the versioned knowledge base.
                    }
                }
                publishedEntriesCache = List.copyOf(published);
                publishedCacheLoadedAtMs = now;
                return publishedEntriesCache;
            } catch (DataAccessException ignored) {
                if (publishedCacheLoadedAtMs == 0L) publishedCacheLoadedAtMs = now;
                return publishedEntriesCache;
            }
        }
    }

    /** Reject exact keyword collisions across distinct knowledge entries. */
    public boolean hasKeywordConflict(List<String> candidateKeywords) {
        if (candidateKeywords == null || candidateKeywords.isEmpty()) return true;
        List<String> normalized = candidateKeywords.stream()
                .filter(StringUtils::hasText)
                .map(SupportAnswerService::normalize)
                .filter(StringUtils::hasText)
                .toList();
        List<Entry> available = new ArrayList<>(entries);
        available.addAll(publishedEntries());
        return available.stream().anyMatch(entry ->
                entry.keywords().stream().anyMatch(normalized::contains));
    }

    /**
     * 自动学习候选用：提取问题中命中的全部知识关键词（去重，最长优先），供候选关键词自动填充。
     * 未命中返回空列表（调用方兜底取问题片段）。
     */
    public List<String> extractMatchedKeywords(String question) {
        if (!StringUtils.hasText(question)) return List.of();
        String normalizedQuestion = normalize(question);
        List<Entry> available = new ArrayList<>(entries);
        available.addAll(publishedEntries());
        return available.stream()
                .flatMap(entry -> entry.keywords().stream())
                .filter(k -> normalizedQuestion.contains(k))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(16)
                .toList();
    }

    private static boolean isPromptInjection(String question) {
        if (containsAny(question, INJECTION_SIGNALS)) return true;
        return INJECTION_COMBINATION.matcher(question).find()
                || (containsAny(question, List.of("忽略", "无视", "覆盖", "绕过", "忘记"))
                && containsAny(question, List.of("指令", "提示", "规则", "系统", "开发者", "知识库", "限制", "之前")));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
    private static boolean containsAny(String text, List<String> phrases) {
        for (String phrase : phrases) if (text.contains(normalize(phrase))) return true;
        return false;
    }
    private record Entry(String id, List<String> keywords, String answer) {}
    private record Match(Entry entry, String keyword) {}
}

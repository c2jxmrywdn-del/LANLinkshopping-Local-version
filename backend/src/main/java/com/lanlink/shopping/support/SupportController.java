package com.lanlink.shopping.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/support")
public class SupportController {
    private static final Logger log = LoggerFactory.getLogger(SupportController.class);
    private static final long MAX_BODY_BYTES = 4096L;
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final SupportAnswerService answers;
    private final SupportRequestGuard guard;
    private final ObjectMapper objectMapper;
    private final CustomerCommunicationService communications;
    private final SupportFallbackService fallbackService;
    private final IntentUnderstandingService intents;
    private final SupportAiAnswerService supportAi;
    private final com.lanlink.shopping.module.aikb.KnowledgeRagService rag;
    /** 向量检索命中最低相似度：低于此值视为无相关资料，回退兜底而非强行作答。 */
    private static final double RAG_MIN_SCORE = 0.12d;
    /** 英文提问未命中平台资料时的英文兜底引导（避免中文残留）。 */
    private static final String EN_OUT_OF_SCOPE_FALLBACK =
            "I'm the LANLinkshopping assistant. I can help with account, orders, payment, credit terms, "
                    + "membership, logistics, returns and more. If I can't confirm this from the platform's information, "
                    + "please open a ticket under Settings → Human Support and our team will follow up during service hours.";

    public SupportController(SupportAnswerService answers, SupportRequestGuard guard,
                             ObjectMapper objectMapper, CustomerCommunicationService communications,
                             SupportFallbackService fallbackService, IntentUnderstandingService intents,
                             SupportAiAnswerService supportAi,
                             com.lanlink.shopping.module.aikb.KnowledgeRagService rag) {
        this.answers = answers;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.communications = communications;
        this.fallbackService = fallbackService;
        this.intents = intents;
        this.supportAi = supportAi;
        this.rag = rag;
    }

    @PostMapping("/ask")
    public ResponseEntity<R<Map<String, Object>>> ask(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Vary", "Origin");

        String fallback = answers.fallback();
        if (!guard.isAuthorizedProxy(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(R.<Map<String, Object>>fail(403, fallback));
        }
        // 客户端 IP：优先可信代理注入的 X-Support-Client-IP，缺失时回退 socket 远端地址兜底，
        // 避免代理链路偶发丢头导致所有请求被限流拒绝。
        String clientIp = request.getHeader("X-Support-Client-IP");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        }
        if (!guard.allowRequest(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(R.<Map<String, Object>>fail(429, fallback));
        }

        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")
                || request.getContentLengthLong() > MAX_BODY_BYTES
                || rawBody == null || rawBody.length() > MAX_BODY_BYTES) {
            return ResponseEntity.ok(R.ok(Map.of("answer", fallback)));
        }

        try {
            JsonNode body = objectMapper.readTree(rawBody);
            if (body == null || !body.isObject()) {
                return ResponseEntity.ok(R.ok(Map.of("answer", fallback)));
            }
            String action = body.path("action").asText("ask");
            String incomingToken = body.path("visitorToken").asText("");
            String visitorToken = UUID_TOKEN.matcher(incomingToken).matches()
                    ? incomingToken : UUID.randomUUID().toString();

            if ("history".equals(action)) {
                long afterId = body.path("afterMessageId").canConvertToLong()
                        ? Math.max(0L, body.path("afterMessageId").asLong()) : 0L;
                List<Map<String, Object>> messages = communications.visitorHistory(visitorToken, afterId);
                return ResponseEntity.ok(R.ok(Map.of(
                        "answer", "", "visitorToken", visitorToken,
                        "lastMessageId", afterId, "messages", messages)));
            }
            if (!"ask".equals(action) || !body.path("question").isTextual()
                    || body.path("question").asText().length() > 300) {
                return ResponseEntity.ok(R.ok(Map.of(
                        "answer", fallback, "visitorToken", visitorToken, "lastMessageId", 0L)));
            }

            String question = body.path("question").asText();
            String answer;
            String answerSource = "KB";
            boolean english = !containsCjk(question);
            try {
                // 先取本地知识库精确答案（含问候/自我介绍/拒答兜底），作为事实来源与最终回退。
                String kbAnswer = answers.answer(question);
                answer = kbAnswer;
                String matched = answers.matchedEntryId(question);
                boolean substantive = answers.eligibleForAiModel(question);
                // 统一"组合接地"策略：命中权威条目时以其打底，再叠加向量检索到的本站真实数据
                // （政策/会员/订阅/商品/物流/学习候选…），交本地 qwen2.5-vl 生成——既摆脱模板腔，
                // 又让"会员折扣/物流合作方/退换政策"这类事实子问题以入库数据为准，抑制臆造。
                if (substantive && supportAi.available()) {
                    String ragContext = "";
                    try {
                        com.lanlink.shopping.module.aikb.KnowledgeRagService.RagContext ctx =
                                rag.buildContext(question, 6, RAG_MIN_SCORE, english);
                        if (ctx.usable()) ragContext = ctx.text();
                    } catch (Exception ragEx) {
                        log.warn("[support] rag retrieval skipped: {}", ragEx.getMessage());
                    }
                    StringBuilder grounding = new StringBuilder();
                    if (StringUtils.hasText(matched)) grounding.append(kbAnswer);
                    if (!ragContext.isEmpty()) {
                        if (grounding.length() > 0) grounding.append("\n\n");
                        grounding.append(ragContext);
                    }
                    String modelText = null;
                    if (grounding.length() > 0) {
                        modelText = supportAi.answerRag(question, grounding.toString()).orElse(null);
                    } else if (StringUtils.hasText(matched)) {
                        // 无检索资料但命中条目：对权威答案做纯润色
                        modelText = supportAi.answer(question, kbAnswer).orElse(null);
                    }
                    boolean hasRag = !ragContext.isEmpty();
                    if (modelText != null) {
                        answer = modelText;
                        answerSource = hasRag ? "RAG" : "AI";
                    }
                    boolean deflected = modelText == null || modelText.contains("超出知识库范围")
                            || (english && containsCjk(modelText));
                    if (english && hasRag && deflected) {
                        String extracted = contextExtract(ragContext, true);
                        if (!extracted.isBlank()) {
                            answer = extracted;
                            answerSource = "RAG";
                        }
                    }
                }
            } catch (Exception ex) {
                // 主客服服务异常 → 备用链路接管（知识问答仍只走本地知识库；外部模型仅限寒暄润色）
                log.error("[support] primary answer failed, switching to fallback", ex);
                answer = fallbackService.answerInBackup(question);
            }
            // 英文提问下的问候/自我介绍/兜底：按语言切换为英文变体，杜绝中文残留。
            if (english && "KB".equals(answerSource) && answer != null) {
                if (answer.equals(answers.greeting())) answer = answers.englishGreeting();
                else if (answer.equals(answers.selfIntro())) answer = answers.englishSelfIntro();
                else if (answer.equals(answers.fallback())) answer = answers.englishFallback();
            }
            // 英文模式下的终态清洗：去除模型可能夹带的残留汉字/假名/全角标点（保留 ¥ 与数字）。
            if (english && ("RAG".equals(answerSource) || "AI".equals(answerSource))) {
                answer = stripCjkForEnglish(answer);
            }
            // 自主学习：未命中知识库的合格提问自动沉淀为待审核候选（不自动发布）；
            // 异步执行且吞异常，绝不影响回答主链路
            try {
                // matchedEntryId 未命中时返回空字符串（非 null），须用 hasText 判断
                if (!StringUtils.hasText(answers.matchedEntryId(question))
                        && answers.isEligibleForKnowledgeLearning(question)
                        && FallbackPolicyGuard.classify(question) != FallbackPolicyGuard.Intent.PROHIBITED) {
                    final String learnQuestion = question;
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            communications.createAutoCandidate(learnQuestion);
                        } catch (Exception e) {
                            log.warn("[support-learning] auto candidate skipped: {}", e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("[support-learning] auto candidate check failed: {}", e.getMessage());
            }
            long lastMessageId = 0L;
            if (body.path("persistConversation").asBoolean(false)) {
                try {
                    CustomerCommunicationService.TurnResult turn =
                            communications.recordCustomerTurn(visitorToken, question, answer);
                    visitorToken = turn.visitorToken();
                    lastMessageId = turn.lastMessageId();
                } catch (DataAccessException | IllegalStateException ex) {
                    // Knowledge replies remain available if optional conversation logging is temporarily degraded.
                    log.warn("[support] opted-in conversation persistence unavailable; question omitted from logs: {}",
                            ex.getMessage());
                }
            }
            return ResponseEntity.ok(R.ok(Map.of(
                    "answer", answer, "visitorToken", visitorToken, "lastMessageId", lastMessageId,
                    "source", answerSource,
                    "intent", intents.analyzeFast(question).data())));
        } catch (JsonProcessingException ex) {
            return ResponseEntity.ok(R.ok(Map.of("answer", fallback)));
        }
    }

    /**
     * 备用链路监控状态（权限校验：仅平台运营 admin:all 可见）。
     * 暴露：备用态开关、违规模型调用计数、外部渠道配置状态。
     */
    @PostMapping("/fallback/status")
    @RequirePerm("admin:all")
    public ResponseEntity<R<Map<String, Object>>> fallbackStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("backupActive", fallbackService.isBackupActive());
        status.put("violationCount", fallbackService.violationCount());
        // 真实反映配置有效性：至少配置一个外部渠道 API Key 才为 true（不再硬编码）
        status.put("externalChannelConfigured", fallbackService.externalChannelConfigured());
        status.put("policy", "knowledge=local-kb-only; external-model=greeting-only; anti-proxy=enforced");
        return ResponseEntity.ok(R.ok(status));
    }

    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff)
                    || (c >= 0xac00 && c <= 0xd7af) || (c >= 0x3400 && c <= 0x4dbf)) return true;
        }
        return false;
    }

    /**
     * 兜底接地提取：模型拒答/不可用/未按语言作答时，直接以检索到的语料块拼成可读回答。
     * english=true → 取拉丁为主的块并清洗残留中文；english=false → 取含中文的块。
     */
    private static String contextExtract(String ragContext, boolean english) {
        if (ragContext == null || ragContext.isBlank()) return english ? EN_OUT_OF_SCOPE_FALLBACK : "";
        String[] blocks = ragContext.split("\\n\\s*\\n");
        java.util.List<String> keep = new java.util.ArrayList<>();
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String b : blocks) {
            String body = b.replaceFirst("^\\s*\\[\\d+\\]（[^）]*）", "").trim();
            if (body.isEmpty()) continue;
            all.add(body);
            double ratio = cjkRatio(body);
            if (english ? ratio < 0.15 : ratio > 0) keep.add(body);
        }
        java.util.List<String> chosen = keep.isEmpty() ? all : keep;
        StringBuilder sb = new StringBuilder();
        for (String body : chosen) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(body.replaceAll("\\s+", " "));
        }
        String out = sb.toString().trim();
        if (english) {
            out = out.replaceAll("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef\\u2018\\u2019\\u201c\\u201d《》、：（）　]", " ")
                     .replaceAll("\\s+", " ").trim();
            if (out.isEmpty()) return EN_OUT_OF_SCOPE_FALLBACK;
        }
        if (out.length() > 700) out = out.substring(0, 700).trim() + "…";
        return out;
    }

    private static double cjkRatio(String s) {
        int total = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3040 && c <= 0x30ff)
                    || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0xac00 && c <= 0xd7af)) cjk++;
        }
        return total == 0 ? 1 : (double) cjk / total;
    }

    /**
     * 英文模式终态清洗：删除模型可能夹带的汉字/假名/韩文与全角标点（保留 ¥ 与数字/字母），
     * 保证英文回答零中文残留。清洗后为空则回退英文兜底串。
     */
    private static String stripCjkForEnglish(String s) {
        if (s == null || s.isBlank()) return EN_OUT_OF_SCOPE_FALLBACK;
        // 汉字/假名/韩文/扩展A + 全角标点（但排除 U+FFE5 ¥，保留货币符号）
        String cleaned = s
                .replaceAll("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]", " ")
                .replaceAll("[\\u3000-\\u303f\\uff00-\\uffff&&[^\\uffe5]]", " ")
                .replaceAll("[《》、：（）【】〔〕　\\u2018\\u2019\\u201c\\u201d]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 去掉行尾悬挂的孤立标点/逗号
        cleaned = cleaned.replaceAll("[,;:.\\s]+$", "").trim();
        if (cleaned.isEmpty()) return EN_OUT_OF_SCOPE_FALLBACK;
        return cleaned;
    }
}

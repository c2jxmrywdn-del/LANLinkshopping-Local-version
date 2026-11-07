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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @Autowired(required = false)
    private com.lanlink.shopping.mapper.ProductMapper productMapper;

    @Autowired(required = false)
    private com.lanlink.shopping.module.aikb.KnowledgeIngestService knowledgeIngestService;

    @Autowired(required = false)
    private ExternalFallbackService externalFallbackService;

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
        SupportRequestGuard.ProxyChannel channel = guard.authorizedChannel(request);
        if (channel == SupportRequestGuard.ProxyChannel.NONE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(R.<Map<String, Object>>fail(403, fallback));
        }
        // 客户端 IP：仅在请求经可信代理层（代理密钥通道）时才采信 X-Support-Client-IP——
        // 该头由 nginx（$remote_addr）/Vite（socket remoteAddress）覆写注入，缺失时回退
        // socket 远端地址兜底。平台 API key 属直连调用，调用方可任意伪造轮换该头获得多份
        // 限流配额，故一律以 socket 远端地址为限流键。
        String clientIp;
        if (channel == SupportRequestGuard.ProxyChannel.PROXY_SECRET) {
            String headerIp = request.getHeader("X-Support-Client-IP");
            clientIp = (headerIp == null || headerIp.isBlank()) ? request.getRemoteAddr() : headerIp;
        } else {
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
                    // 商品实体精准回补：若向量检索未命中或未命中具体商品，从 t_product 在售表精准回补最新商品知识卡片
                    if (ragContext.isEmpty() || !ragContext.contains("【商品编号")) {
                        String entityCard = tryProductEntityFallback(question, english);
                        if (!entityCard.isEmpty()) {
                            if (grounding.length() > 0) grounding.append("\n\n");
                            grounding.append(entityCard);
                        }
                    }

                    String modelText = null;
                    if (grounding.length() > 0) {
                        modelText = supportAi.answerRag(question, grounding.toString()).orElse(null);
                        // 本地模型未给出有效答案时，尝试经由本地知识库 Grounding 触发外部大模型兜底
                        if (modelText == null && externalFallbackService != null) {
                            try {
                                var ext = externalFallbackService.answer(question, english, grounding.toString(), 1);
                                if (ext != null && StringUtils.hasText(ext.answer())) {
                                    modelText = ext.answer();
                                }
                            } catch (Exception extEx) {
                                log.warn("[support] external fallback grounding call failed: {}", extEx.getMessage());
                            }
                        }
                    } else if (StringUtils.hasText(matched)) {
                        // 无检索资料但命中条目：对权威答案做纯润色
                        modelText = supportAi.answer(question, kbAnswer).orElse(null);
                    }
                    boolean hasRag = !ragContext.isEmpty() || grounding.length() > 0;
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
     * 流式智能客服接口（SSE 代理）：基于 SseEmitter 异步中继本地网关推理流与思维链。
     * 支持反向代理不缓冲（X-Accel-Buffering: no），首包透传三级缓存命中元数据，异常秒级兜底。
     */
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request,
            HttpServletResponse response) {

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Vary", "Origin");

        SseEmitter emitter = new SseEmitter(60_000L);
        emitter.onTimeout(() -> {
            log.debug("[support-stream] emitter timed out");
            try { emitter.complete(); } catch (Exception ignored) {}
        });
        emitter.onError(t -> {
            log.debug("[support-stream] emitter error: {}", t != null ? t.getMessage() : "unknown");
            try { emitter.complete(); } catch (Exception ignored) {}
        });

        String fallback = answers.fallback();
        SupportRequestGuard.ProxyChannel channel = guard.authorizedChannel(request);
        if (channel == SupportRequestGuard.ProxyChannel.NONE) {
            SupportAiAnswerService.sendSimulatedStream(emitter, fallback, "KB");
            return emitter;
        }

        String clientIp = (channel == SupportRequestGuard.ProxyChannel.PROXY_SECRET)
                ? (request.getHeader("X-Support-Client-IP") != null && !request.getHeader("X-Support-Client-IP").isBlank()
                    ? request.getHeader("X-Support-Client-IP") : request.getRemoteAddr())
                : request.getRemoteAddr();

        if (!guard.allowRequest(clientIp)) {
            SupportAiAnswerService.sendSimulatedStream(emitter, "当前咨询量较大，请稍后重试。", "LIMIT");
            return emitter;
        }

        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("application/json")
                || request.getContentLengthLong() > MAX_BODY_BYTES
                || rawBody == null || rawBody.length() > MAX_BODY_BYTES) {
            SupportAiAnswerService.sendSimulatedStream(emitter, fallback, "KB");
            return emitter;
        }

        try {
            JsonNode body = objectMapper.readTree(rawBody);
            if (body == null || !body.isObject()) {
                SupportAiAnswerService.sendSimulatedStream(emitter, fallback, "KB");
                return emitter;
            }
            String incomingToken = body.path("visitorToken").asText("");
            String visitorToken = UUID_TOKEN.matcher(incomingToken).matches()
                    ? incomingToken : UUID.randomUUID().toString();
            String question = body.path("question").asText("");
            boolean persist = body.path("persistConversation").asBoolean(true);

            if (!StringUtils.hasText(question) || question.length() > 300) {
                SupportAiAnswerService.sendSimulatedStream(emitter, fallback, "KB");
                return emitter;
            }

            boolean substantive = answers.eligibleForAiModel(question);
            boolean english = !containsCjk(question);
            String kbAnswer = answers.answer(question);
            String matched = answers.matchedEntryId(question);

            // 若命中静态问候、自我介绍或非大模型咨询内容，快速流式直出
            if (!substantive || !supportAi.available()) {
                String directAnswer = kbAnswer;
                if (english && directAnswer != null) {
                    if (directAnswer.equals(answers.greeting())) directAnswer = answers.englishGreeting();
                    else if (directAnswer.equals(answers.selfIntro())) directAnswer = answers.englishSelfIntro();
                    else if (directAnswer.equals(answers.fallback())) directAnswer = answers.englishFallback();
                }
                SupportAiAnswerService.sendSimulatedStream(emitter, directAnswer, "KB");
                return emitter;
            }

            // 组合接地检索 (KB + Hybrid RAG + 商品在售卡片)
            final String finalVisitorToken = visitorToken;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String ragContext = "";
                    try {
                        com.lanlink.shopping.module.aikb.KnowledgeRagService.RagContext ctx =
                                rag.buildContext(question, 6, RAG_MIN_SCORE, english);
                        if (ctx.usable()) ragContext = ctx.text();
                    } catch (Exception ragEx) {
                        log.warn("[support-stream] rag retrieval skipped: {}", ragEx.getMessage());
                    }

                    StringBuilder grounding = new StringBuilder();
                    if (StringUtils.hasText(matched)) grounding.append(kbAnswer);
                    if (!ragContext.isEmpty()) {
                        if (grounding.length() > 0) grounding.append("\n\n");
                        grounding.append(ragContext);
                    }
                    if (ragContext.isEmpty() || !ragContext.contains("【商品编号")) {
                        String entityCard = tryProductEntityFallback(question, english);
                        if (!entityCard.isEmpty()) {
                            if (grounding.length() > 0) grounding.append("\n\n");
                            grounding.append(entityCard);
                        }
                    }

                    supportAi.answerStream(question, grounding.toString(), emitter, (finalText) -> {
                        if (persist && StringUtils.hasText(finalText)) {
                            try {
                                communications.recordCustomerTurn(finalVisitorToken, question, finalText);
                            } catch (Exception e) {
                                log.warn("[support-stream] persist turn failed: {}", e.getMessage());
                            }
                        }
                    });
                } catch (Exception ex) {
                    log.error("[support-stream] pipeline execution error", ex);
                    SupportAiAnswerService.sendSimulatedStream(emitter, kbAnswer, "FALLBACK");
                }
            });

        } catch (Exception ex) {
            log.error("[support-stream] request parsing failed", ex);
            SupportAiAnswerService.sendSimulatedStream(emitter, fallback, "KB");
        }

        return emitter;
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

    /**
     * 实体精准回补（Entity Fallback Grounding）：
     * 当向量知识库未检索到足够上下文（或上新商品尚未被向量模型命中），
     * 尝试根据用户提问提取商品编号或多词关键词，精准匹配在售商品库（t_product），
     * 实时组装高置信度的 B2B 商品知识卡片，防止大模型发生"未来上新产品无知识"或断崖式硬拒答。
     */
    private String tryProductEntityFallback(String question, boolean english) {
        if (productMapper == null || knowledgeIngestService == null || question == null || question.isBlank()) {
            return "";
        }
        try {
            // 提取商品编号：如 #123 或 商品 123
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("#?(\\d{1,8})").matcher(question);
            if (m.find()) {
                try {
                    long prodId = Long.parseLong(m.group(1));
                    com.lanlink.shopping.entity.Product p = productMapper.selectById(prodId);
                    if (p != null && p.getStatus() != null && p.getStatus() == 1
                            && p.getReviewStatus() != null && p.getReviewStatus() == 1
                            && (p.getDeleted() == null || p.getDeleted() == 0)) {
                        return english
                                ? knowledgeIngestService.formatProductKnowledgeCardEn(p)
                                : knowledgeIngestService.formatProductKnowledgeCard(p);
                    }
                } catch (Exception ignored) {}
            }

            // 提取关键词多词匹配在售商品
            List<String> tokens = com.lanlink.shopping.module.aikb.KnowledgeRagService.lexicalTokens(question);
            if (tokens.isEmpty()) return "";

            List<String> validTokens = tokens.stream()
                    .filter(t -> t.length() >= 2 && !"你们".equals(t) && !"多少".equals(t) && !"有没".equals(t)
                            && !"请问".equals(t) && !"可以".equals(t) && !"怎么".equals(t) && !"什么".equals(t))
                    .limit(3)
                    .toList();
            if (validTokens.isEmpty()) return "";

            var query = com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.lanlink.shopping.entity.Product>lambdaQuery()
                    .eq(com.lanlink.shopping.entity.Product::getStatus, 1)
                    .eq(com.lanlink.shopping.entity.Product::getReviewStatus, 1)
                    .and(w -> {
                        for (String token : validTokens) {
                            w.or().like(com.lanlink.shopping.entity.Product::getTitle, token)
                             .or().like(com.lanlink.shopping.entity.Product::getBrand, token)
                             .or().like(com.lanlink.shopping.entity.Product::getSpec, token);
                        }
                    })
                    .last("LIMIT 2");

            List<com.lanlink.shopping.entity.Product> list = productMapper.selectList(query);
            if (list == null || list.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (com.lanlink.shopping.entity.Product p : list) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(english
                        ? knowledgeIngestService.formatProductKnowledgeCardEn(p)
                        : knowledgeIngestService.formatProductKnowledgeCard(p));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[support] product entity fallback failed: {}", e.getMessage());
            return "";
        }
    }
}

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

    public SupportController(SupportAnswerService answers, SupportRequestGuard guard,
                             ObjectMapper objectMapper, CustomerCommunicationService communications,
                             SupportFallbackService fallbackService, IntentUnderstandingService intents) {
        this.answers = answers;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.communications = communications;
        this.fallbackService = fallbackService;
        this.intents = intents;
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
            try {
                answer = answers.answer(question);
            } catch (Exception ex) {
                // 主客服服务异常 → 备用链路接管（知识问答仍只走本地知识库；外部模型仅限寒暄润色）
                log.error("[support] primary answer failed, switching to fallback", ex);
                answer = fallbackService.answerInBackup(question);
            }
            // 自主学习：未命中知识库的合格提问自动沉淀为待审核候选（不自动发布）；
            // 异步执行且吞异常，绝不影响回答主链路
            try {
                if (answers.matchedEntryId(question) == null
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
                    "intent", intents.analyze(question, List.of()).data())));
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
}

package com.lanlink.shopping.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/support")
public class SupportController {
    private static final long MAX_BODY_BYTES = 4096L;
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final SupportAnswerService answers;
    private final SupportRequestGuard guard;
    private final ObjectMapper objectMapper;
    private final CustomerCommunicationService communications;

    public SupportController(SupportAnswerService answers, SupportRequestGuard guard,
                             ObjectMapper objectMapper, CustomerCommunicationService communications) {
        this.answers = answers;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.communications = communications;
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
        if (!guard.allowRequest(request.getHeader("X-Support-Client-IP"))) {
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
            String answer = answers.answer(question);
            long lastMessageId = 0L;
            if (body.path("persistConversation").asBoolean(false)) {
                try {
                    CustomerCommunicationService.TurnResult turn =
                            communications.recordCustomerTurn(visitorToken, question, answer);
                    visitorToken = turn.visitorToken();
                    lastMessageId = turn.lastMessageId();
                } catch (DataAccessException | IllegalStateException ex) {
                    // Knowledge replies remain available if optional conversation logging is temporarily degraded.
                    System.err.println("[support] opted-in conversation persistence unavailable; question omitted from logs");
                }
            }
            return ResponseEntity.ok(R.ok(Map.of(
                    "answer", answer, "visitorToken", visitorToken, "lastMessageId", lastMessageId)));
        } catch (JsonProcessingException ex) {
            return ResponseEntity.ok(R.ok(Map.of("answer", fallback)));
        }
    }
}

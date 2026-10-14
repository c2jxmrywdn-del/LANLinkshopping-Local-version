package com.lanlink.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Streamable HTTP MCP client for Agent Loadout outbound email.
 *
 * Credentials are read only from deployment environment variables and are never
 * included in logs, responses, repository files, or exception messages.
 */
@Component
public class AgentLoadoutMcpClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final URI endpoint;
    private final String apiKey;
    private final String inboxId;
    private volatile boolean initialized;
    private volatile String cachedSessionId = "";
    private final AtomicInteger rpcSequence = new AtomicInteger(1);

    public AgentLoadoutMcpClient(
            @Value("${AGENT_LOADOUT_MCP_URL:https://agent-loadout.com/api/mcp}") String endpoint,
            @Value("${AGENT_LOADOUT_API_KEY:}") String apiKey,
            @Value("${AGENT_LOADOUT_INBOX_ID:}") String inboxId) {
        this.endpoint = validateEndpoint(endpoint);
        this.apiKey = normalize(apiKey);
        this.inboxId = normalize(inboxId);
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !inboxId.isBlank();
    }

    /**
     * Queue one message using Agent Loadout's send_message tool.
     * Each campaign recipient should be sent individually to avoid address leakage.
     */
    public void sendMessage(String to, String subject, String text, String html, String idempotencyKey) {
        if (!isConfigured()) {
            throw new BusinessException("Agent Loadout 邮件通道尚未配置");
        }
        if (to == null || to.isBlank() || subject == null || subject.isBlank()
                || ((text == null || text.isBlank()) && (html == null || html.isBlank()))) {
            throw new BusinessException("邮件收件人、主题和正文不能为空");
        }

        try {
            String sessionId = ensureInitialized();
            ObjectNode args = JSON.createObjectNode();
            args.put("inbox_id", inboxId);
            args.putArray("to").add(to);
            args.put("subject", subject);
            if (text != null && !text.isBlank()) args.put("text", text);
            if (html != null && !html.isBlank()) args.put("html", html);
            args.put("idempotency_key", normalize(idempotencyKey).isBlank()
                    ? UUID.randomUUID().toString() : idempotencyKey);

            ObjectNode params = JSON.createObjectNode();
            params.put("name", "send_message");
            params.set("arguments", args);
            JsonNode result = postRpc("tools/call", params, rpcSequence.incrementAndGet(), sessionId, false);
            if (result.has("error") || result.path("result").isMissingNode()) {
                throw new BusinessException("Agent Loadout 邮件发送失败，请检查认证权限和邮箱配置");
            }
            JsonNode toolResult = result.path("result");
            if (toolResult.path("isError").asBoolean(false)) {
                throw new BusinessException("Agent Loadout 邮件发送失败，请检查发送权限或邮箱状态");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Agent Loadout 邮件请求被中断，请稍后重试");
        } catch (IOException | RuntimeException ex) {
            // Do not log message bodies, recipient addresses, API keys, or verification codes.
            throw new BusinessException("Agent Loadout 邮件服务暂时不可用，请检查连接配置后重试");
        }
    }

    private String ensureInitialized() throws IOException, InterruptedException {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    ObjectNode params = JSON.createObjectNode();
                    params.put("protocolVersion", "2025-03-26");
                    params.set("capabilities", JSON.createObjectNode());
                    ObjectNode clientInfo = JSON.createObjectNode();
                    clientInfo.put("name", "LANLinkshopping");
                    clientInfo.put("version", "1.0.0");
                    params.set("clientInfo", clientInfo);

                    JsonNode response = postRpc("initialize", params, 1, null, false);
                    if (response.has("error") || response.path("result").isMissingNode()) {
                        throw new BusinessException("Agent Loadout MCP 初始化失败，请检查服务器地址和 API Key");
                    }
                    // Stateless MCP servers omit this header; session-based servers return it.
                    cachedSessionId = response.path("_mcpSessionId").asText("");
                    if (!cachedSessionId.isBlank()) {
                        postRpc("notifications/initialized", JSON.createObjectNode(), 0, cachedSessionId, true);
                    }
                    initialized = true;
                }
            }
        }
        return cachedSessionId;
    }

    private JsonNode postRpc(String method, JsonNode params, int id, String sessionId, boolean notification)
            throws IOException, InterruptedException {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (!notification) envelope.put("id", id);
        envelope.put("method", method);
        if (params != null) envelope.set("params", params);

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(envelope), StandardCharsets.UTF_8));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
            builder.header("MCP-Protocol-Version", "2025-03-26");
        }

        HttpResponse<String> response = HTTP.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (status == 401 || status == 403) {
                throw new BusinessException("Agent Loadout 认证失败或当前 API Key 没有邮件发送权限");
            }
            throw new BusinessException("Agent Loadout MCP 请求失败，HTTP " + status);
        }
        String returnedSessionId = response.headers().firstValue("Mcp-Session-Id").orElse("");
        if (notification) return JSON.createObjectNode();

        JsonNode parsed = parseResponse(response.body());
        if (!returnedSessionId.isBlank() && parsed.isObject()) {
            ((ObjectNode) parsed).put("_mcpSessionId", returnedSessionId);
        }
        return parsed;
    }

    private JsonNode parseResponse(String body) throws IOException {
        if (body == null || body.isBlank()) {
            throw new IOException("Empty MCP response");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JSON.readTree(trimmed);
        }

        // Streamable HTTP can frame a JSON-RPC message as a Server-Sent Event.
        JsonNode last = null;
        for (String line : trimmed.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.startsWith("data:")) continue;
            String data = candidate.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                last = JSON.readTree(data);
            } catch (IOException ignored) {
                // Continue; only JSON data frames are relevant.
            }
        }
        if (last == null) throw new IOException("MCP response did not contain a JSON data frame");
        return last;
    }

    private static URI validateEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(normalize(value));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Agent Loadout MCP URL", ex);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && isLoopback(uri.getHost());
        if (uri.getHost() == null || (!https && !loopbackHttp) || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Agent Loadout MCP URL must use HTTPS (HTTP only for loopback tests)");
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException ex) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.lanlink.shopping.module.aikb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 {@code ai-gateway/MCP_KB_SPEC.md} 读取平台向量知识库的 MCP 客户端。
 *
 * <p>只做传输，不做业务判断：任何失败（未配置/超时/非 2xx/isError/不可解析）一律返回空结果并
 * 置 unavailable，调用方回退自己的直连检索——客服不能因为网关抖动而失去 grounding。
 * 连续失败后短路一段时间，避免每个提问都白等一个超时。
 *
 * <p>只读公开库。商户范围内的知识仍走带用户令牌的 /internal/ai/tools 链路，本客户端不是它的替代。
 */
@Component
public class KbMcpClient {

    private static final Logger log = LoggerFactory.getLogger(KbMcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 这是客服链路上的可选加速通道，失败就回退直连检索：宁可早放弃，不要拖慢一次提问。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long COOLDOWN_MILLIS = 60_000L;
    private static final String TOOL_NAME = "lanlink_kb_search";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final URI endpoint;
    private final String apiKey;
    private final boolean enabled;

    private volatile boolean initialized;
    private volatile long openUntilMillis;
    private final java.util.concurrent.atomic.AtomicBoolean handshakeInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger rpcSequence = new AtomicInteger(1);

    public KbMcpClient(@Value("${AI_GATEWAY_MCP_URL:http://host.docker.internal:8090/mcp}") String endpointUrl,
                       @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String apiKey,
                       @Value("${AI_KB_MCP_ENABLED:true}") boolean enabled) {
        this.endpoint = parseEndpoint(endpointUrl);
        // 凭据多半来自密钥管理器粘贴，夹带换行会让 JDK 抛 "invalid header value: <整值>"，
        // 那条消息又被记进日志——先把不可见字符剔掉，密钥就不可能经异常消息外泄。
        this.apiKey = apiKey == null ? "" : apiKey.replaceAll("[^\\x21-\\x7e]", "");
        this.enabled = enabled;
    }

    @jakarta.annotation.PostConstruct
    void logChannel() {
        log.info("[aikb-mcp] 向量库 MCP 通道 {}，端点 {}（密钥不记录）",
                isConfigured() ? "已启用" : "未启用",
                endpoint == null ? "未配置" : endpoint.getHost() + ":" + endpoint.getPort() + endpoint.getPath());
    }

    /** 检索结果：{@code servedByVectorIndex} 为 false 时 hits 为空，含义由 backend 说明。 */
    public record Outcome(List<RedisVectorStore.Hit> hits, String backend, boolean servedByVectorIndex) {
        public static Outcome unavailable(String backend) {
            return new Outcome(List.of(), backend, false);
        }
    }

    public boolean isConfigured() {
        return enabled && endpoint != null && !apiKey.isBlank();
    }

    /**
     * @param minScore 作用在命中行的 relevance（密集向量余弦相似度）上，与本地向量分同尺度
     */
    public Outcome search(String question, int topK, double minScore) {
        if (!isConfigured()) return Outcome.unavailable("disabled");
        if (endpoint == null) return Outcome.unavailable("invalid-endpoint");
        long now = System.currentTimeMillis();
        if (now < openUntilMillis) return Outcome.unavailable("cooldown");

        try {
            initializeOnce();
            JsonNode structured = callSearch(question, topK, minScore);
            String backend = structured.path("backend").asText("");
            JsonNode rows = structured.path("hits");
            List<RedisVectorStore.Hit> hits = new ArrayList<>();
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    String text = row.path("snippet").asText("");
                    if (text.isBlank()) continue;
                    // Hit.score 用规范里的 score（相关度分，密集路为余弦相似度），
                    // 与本地向量检索同尺度，下游 minScore / topScore 判断才不会被名次分带偏。
                    hits.add(new RedisVectorStore.Hit(
                            row.path("docId").asText(""),
                            row.path("source").asText(""),
                            row.path("refId").asText(""),
                            row.path("title").asText(""),
                            text,
                            row.path("score").asDouble(0.0)));
                }
            }
            onSuccess();
            // TF-IDF 兜底库既没有 refId（无法回源实时商品事实），也没有双路融合，
            // 质量低于本地直连路径：交回调用方走原检索，而不是当命中。
            boolean vectorServed = "redis-vector".equals(backend);
            return new Outcome(vectorServed ? hits : List.of(), backend, vectorServed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onFailure("interrupted");
            return Outcome.unavailable("interrupted");
        } catch (Exception e) {
            String reason = rootCause(e);
            onFailure(reason);
            // 把原因带进结果，调用方（与真机探针）才能只看 backend 就判断是哪一类失败
            return Outcome.unavailable("error: " + reason);
        }
    }

    /**
     * 只取异常类型与消息前 200 字，便于从日志判断是 401 还是超时；不打印响应正文。
     * IllegalArgumentException 单独处理：JDK 的 header 校验会把整个头部值（含密钥）写进消息。
     */
    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        if (t instanceof IllegalArgumentException) {
            return "IllegalArgumentException: 请求构造非法（消息含敏感头部值，已省略）";
        }
        String msg = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ");
        if (msg.length() > 200) msg = msg.substring(0, 200);
        return t.getClass().getSimpleName() + ": " + msg;
    }

    private JsonNode callSearch(String question, int topK, double minScore) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("query", question);
        args.put("topK", Math.max(1, Math.min(topK, 10)));
        args.put("minScore", minScore);

        ObjectNode params = JSON.createObjectNode();
        params.put("name", TOOL_NAME);
        params.set("arguments", args);

        JsonNode response = postRpc("tools/call", params);
        JsonNode result = response.path("result");
        if (result.isMissingNode() || result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("mcp tools/call returned error");
        }
        JsonNode structured = result.path("structuredContent");
        if (structured.isObject()) return structured;

        // 老客户端或未支持 structuredContent 的服务端：取最后一段 JSON 文本
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (int i = content.size() - 1; i >= 0; i--) {
                String text = content.get(i).path("text").asText("");
                if (text.trim().startsWith("{")) {
                    JsonNode parsed = JSON.readTree(text);
                    if (parsed.has("hits")) return parsed;
                }
            }
        }
        throw new IllegalStateException("mcp result has no structured payload");
    }

    /**
     * 网关是无状态 MCP 服务，{@code tools/call} 不依赖会话即可受理，所以握手按"进程内尽力一次"处理：
     * 只放一个线程在请求线程上补做，其余线程直接发起调用。若网关正卡着，不能让所有客服请求排队等一把
     * 会阻塞 5 秒的锁——那会把可选加速通道变成故障放大器。
     */
    private void initializeOnce() {
        if (initialized || !handshakeInFlight.compareAndSet(false, true)) return;
        try {
            ObjectNode params = JSON.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            params.set("capabilities", JSON.createObjectNode());
            ObjectNode clientInfo = JSON.createObjectNode();
            clientInfo.put("name", "lanlink-shopping-backend");
            clientInfo.put("version", "1.0.0");
            params.set("clientInfo", clientInfo);
            JsonNode response = postRpc("initialize", params);
            if (!response.has("error") && !response.path("result").isMissingNode()) {
                initialized = true;
            }
        } catch (Exception e) {
            // 握手失败不记入熔断：紧随其后的 tools/call 才是真正决定本次能否用 MCP 的那一次
            log.debug("[aikb-mcp] initialize 未完成: {}", e.getClass().getSimpleName());
        } finally {
            handshakeInFlight.set(false);
        }
    }

    private JsonNode postRpc(String method, JsonNode params) throws Exception {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", rpcSequence.getAndIncrement());
        envelope.put("method", method);
        envelope.set("params", params);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(envelope), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // 不回显响应体：里面可能带内部堆栈，日志只记状态码
            throw new IllegalStateException("mcp http " + status);
        }
        return JSON.readTree(response.body());
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis = 0L;
    }

    private void onFailure(String reason) {
        initialized = false;
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= MAX_CONSECUTIVE_FAILURES) {
            openUntilMillis = System.currentTimeMillis() + COOLDOWN_MILLIS;
            consecutiveFailures.set(0);
            log.warn("[aikb-mcp] 连续 {} 次失败，短路 {}ms 后重试（{}）",
                    MAX_CONSECUTIVE_FAILURES, COOLDOWN_MILLIS, reason);
        } else {
            log.warn("[aikb-mcp] 检索失败，回退直连检索（{}）", reason);
        }
    }

    private static URI parseEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException bad) {
            log.warn("[aikb-mcp] AI_GATEWAY_MCP_URL 不可解析，知识库 MCP 通道关闭");
            return null;
        }
    }
}

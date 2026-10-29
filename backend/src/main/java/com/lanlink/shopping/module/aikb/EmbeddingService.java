package com.lanlink.shopping.module.aikb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量嵌入服务（本地 Ollama 优先，确定性哈希降级）。
 *
 * 链路：AI_EMBED_MODEL（线上 bge-m3 / 也可 nomic-embed-text；维度不写死，按模型实际输出）→
 * 确定性哈希嵌入（零依赖，保证系统在任何环境可用，检索质量退化为关键词级）。
 *
 * 性能设计：
 *   - 批量嵌入：{@link #embedBatch(List)} 未命中文本按 ≤32 条一组调用 Ollama /api/embed
 *     数组批量接口（一次 HTTP 多条向量），失败再逐条回退单条链路；
 *   - 嵌入缓存：aikb:emb:{model}:{sha256(text)} → "<dim>:<base64(float32 LE bytes)>"，
 *     TTL 7 天；只缓存真实模型向量（哈希降级不缓存），重复文本零模型开销；
 *   - cacheStats() 暴露命中/未命中/命中率/缓存条目数，供管理端观测。
 *
 * 文本生成：
 *   - chatText() 优先 ai-gateway 的本地 qwen2.5-7B（AI_GATEWAY_TEXT_MODEL），
 *     失败回退 Ollama AI_TEXT_MODEL（轻量 0.5b，num_ctx 默认 16384）；
 *   - chatViaGatewayVl() 走显式 VL 模型 AI_GATEWAY_VL_MODEL（默认 qwen2.5-vl）；
 *   - chat()/chatViaGateway() 为旧入口兼容，保持 VL 模型语义。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    /** 哈希降级嵌入的维度 */
    public static final int FALLBACK_DIM = 384;

    /** 批量嵌入单批上限（Ollama /api/embed 数组批量） */
    private static final int EMBED_BATCH_SIZE = 32;
    /** 批量嵌入 HTTP 超时（CPU 推理 + 首次载入模型可能远超单条的 8s） */
    private static final int EMBED_BATCH_TIMEOUT_SECONDS = 120;
    /** 嵌入缓存前缀：aikb:emb:{model}:{sha256(text)} */
    private static final String EMB_CACHE_PREFIX = "aikb:emb:";
    private static final Duration EMB_CACHE_TTL = Duration.ofDays(7);
    /** chatText 回退 Ollama 文本模型时的 keep_alive */
    private static final String DEFAULT_TEXT_KEEP_ALIVE = "5m";

    private final String ollamaBaseUrl;
    private final String embedModel;
    private final String chatModel;
    /** 长上下文文本模型（Ollama 侧轻量兜底；默认 0.5b） */
    private final String textModel;
    private final String gatewayBaseUrl;
    private final String gatewayApiKey;
    private final String gatewayModel;
    /** 网关文本模型（本地 qwen2.5-7B，由 ai-gateway 暴露） */
    private final String gatewayTextModel;
    /**
     * 网关 VL 模型（AI_GATEWAY_VL_MODEL，默认 qwen2.5-vl）。
     * 字段注入：保持构造器签名不变（既有单测/调用方兼容），Spring 运行期注入。
     */
    @Value("${AI_GATEWAY_VL_MODEL:qwen2.5-vl}")
    private String gatewayVlModel;
    /**
     * chatText 回退 Ollama 文本模型时的 num_ctx（AI_TEXT_NUM_CTX，默认 16384）。
     * 字段注入而非硬编码：与 compose/.env 的 KV 上限配置真正联动。
     */
    @Value("${AI_TEXT_NUM_CTX:16384}")
    private int defaultTextNumCtx;
    /** 嵌入缓存（Redis；可为空——为空或 Redis 不可用时自动降级为无缓存） */
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 当前生效的嵌入提供方（统计/健康度展示用）："ollama:<model>" 或 "hash-fallback" */
    private volatile String provider = "uninitialized";
    /** 最近一次对话实际走的后端（线程隔离，供路由/日志标注 gateway:xxx / ollama:xxx） */
    private final ThreadLocal<String> lastChatBackend = new ThreadLocal<>();
    /** 熔断器：模型 → [连续失败次数, 最近失败毫秒]。连续 3 次失败禁用冷却（半开自动恢复） */
    private final Map<String, long[]> breaker = new ConcurrentHashMap<>();
    private static final int BREAKER_THRESHOLD = 3;
    private static final long BREAKER_COOLDOWN_MS = 30 * 60_000L;

    /** 嵌入缓存统计（命中率展示；无 Redis 时仍可观测进程内计数） */
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    /** 缓存写入次数（Redis SCAN 不可用时的 size 兜底估值） */
    private final AtomicLong cacheWrites = new AtomicLong();

    public EmbeddingService(
            @Value("${AI_OLLAMA_BASE_URL:http://host.docker.internal:11434}") String ollamaBaseUrl,
            @Value("${AI_EMBED_MODEL:nomic-embed-text}") String embedModel,
            @Value("${AI_LOCAL_CHAT_MODEL:qwen2.5:7b}") String chatModel,
            @Value("${AI_TEXT_MODEL:qwen2.5:0.5b}") String textModel,
            @Value("${AI_GATEWAY_BASE_URL:http://host.docker.internal:8090/v1}") String gatewayBaseUrl,
            @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String gatewayApiKey,
            @Value("${AI_GATEWAY_MODEL:qwen2.5-vl}") String gatewayModel,
            @Value("${AI_GATEWAY_TEXT_MODEL:qwen2.5-7b}") String gatewayTextModel,
            StringRedisTemplate redis) {
        this.ollamaBaseUrl = trimSlash(ollamaBaseUrl);
        this.embedModel = embedModel;
        this.chatModel = chatModel;
        this.textModel = textModel;
        this.gatewayBaseUrl = trimSlash(gatewayBaseUrl);
        this.gatewayApiKey = gatewayApiKey;
        this.gatewayModel = gatewayModel;
        this.gatewayTextModel = gatewayTextModel;
        this.redis = redis;
    }

    public String getProvider() { return provider; }
    public String getChatModel() { return chatModel; }
    public String getTextModel() { return textModel; }
    public String getGatewayModel() { return gatewayModel; }
    public String getGatewayTextModel() { return gatewayTextModel; }
    /** 网关 VL 模型（未注入/空白时回退 AI_GATEWAY_MODEL，保证手工构造场景可用） */
    public String getGatewayVlModel() {
        return (gatewayVlModel == null || gatewayVlModel.isBlank()) ? gatewayModel : gatewayVlModel;
    }
    /** 当前线程最近一次对话实际命中的后端（"gateway:<model>" / "ollama:<model>"；无则 null） */
    public String getLastChatBackend() { return lastChatBackend.get(); }

    // ==================== 文本生成（qwen2.5-7B 文本 / qwen2.5-vl 视觉） ====================

    /**
     * 网关 VL 模型对话（显式 AI_GATEWAY_VL_MODEL，默认 qwen2.5-vl 常驻）。
     * 路由的 VL 目标只走本方法，避免误用文本模型或与 7B 反复切换。
     */
    public String chatViaGatewayVl(String systemPrompt, String userPrompt) {
        return chatViaGateway(getGatewayVlModel(), systemPrompt, userPrompt);
    }

    /** 网关对话（显式模型名重载：视觉=qwen2.5-vl / 文本=qwen2.5-7b；超时 180s，失败返回 null） */
    public String chatViaGateway(String model, String systemPrompt, String userPrompt) {
        return chatOpenAiCompatible(gatewayBaseUrl + "/chat/completions", model,
                "Bearer " + gatewayApiKey, systemPrompt, userPrompt, 180, null, null);
    }

    /** 旧方法兼容（原“VL 常驻模型”语义）：等价 {@link #chatViaGatewayVl(String, String)} */
    public String chatViaGateway(String systemPrompt, String userPrompt) {
        return chatViaGatewayVl(systemPrompt, userPrompt);
    }

    /** 网关文本模型对话（本地 qwen2.5-7B，经 ai-gateway 暴露；超时 180s，失败返回 null） */
    public String chatViaGatewayText(String systemPrompt, String userPrompt) {
        return chatViaGateway(gatewayTextModel, systemPrompt, userPrompt);
    }

    /**
     * 文本生成统一入口（外部资讯摘要 / 意图分类等非视觉链路使用）：
     * ai-gateway qwen2.5-7B 优先，失败回退 Ollama 轻量文本模型（num_ctx=16384, keep_alive=5m）。
     * @return 生成文本；两侧均不可达返回 null
     */
    public String chatText(String systemPrompt, String userPrompt) {
        String viaGateway = chatViaGatewayText(systemPrompt, userPrompt);
        if (viaGateway != null) return viaGateway;
        return chatViaOllamaText(systemPrompt, userPrompt, defaultTextNumCtx, DEFAULT_TEXT_KEEP_ALIVE);
    }

    /** 长上下文文本模型对话（Ollama，按需载入；num_ctx 加强 KV 上限，keep_alive 到期自动卸载） */
    public String chatViaOllamaText(String systemPrompt, String userPrompt, int numCtx, String keepAlive) {
        return chatOpenAiCompatible(ollamaBaseUrl + "/v1/chat/completions", textModel,
                null, systemPrompt, userPrompt, 180, numCtx, keepAlive);
    }

    /** 卸载长上下文文本模型（keep_alive=0 立即释放显存/内存） */
    public void unloadOllamaTextModel() {
        try {
            String body = objectMapper.writeValueAsString(Map.of("model", textModel, "keep_alive", 0));
            HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/generate"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("[aikb] ollama text model {} unloaded", textModel);
        } catch (Exception e) {
            log.warn("[aikb] ollama unload failed: {}", e.getMessage());
        }
    }

    /** Ollama 当前已加载模型名（/api/ps） */
    public List<String> ollamaLoadedModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/ps"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode models = objectMapper.readTree(resp.body()).path("models");
            List<String> out = new ArrayList<>();
            for (JsonNode m : models) out.add(m.path("name").asText(""));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 嵌入（单条 / 批量 / 缓存） ====================

    /** 单条文本嵌入：Redis 缓存命中直接返回；未命中走模型，失败降级哈希。 */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return hashEmbed("");
        String clipped = clip(text);
        float[] cached = cacheGet(clipped);
        if (cached != null) {
            cacheHits.incrementAndGet();
            provider = "ollama:" + embedModel;
            return cached;
        }
        cacheMisses.incrementAndGet();
        return embedUncached(clipped);
    }

    /**
     * 批量嵌入：先查 Redis 缓存，未命中按 ≤32 条一批调用 Ollama /api/embed 数组接口；
     * 某批失败时对该批逐条回退 {@link #embedUncached(String)}（内含熔断与哈希降级），
     * 因此返回列表长度恒等于入参长度、且不抛异常。
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<float[]> out = new ArrayList<>(texts.size());
        List<Integer> pending = new ArrayList<>();
        List<String> pendingTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String clipped = clip(texts.get(i));
            out.add(null);
            if (clipped.isEmpty()) {
                // 空文本同样交给模型（保证与模型维度一致）；模型不可用时由 embedUncached 统一 384 维哈希
                pending.add(i);
                pendingTexts.add("");
                continue;
            }
            float[] cached = cacheGet(clipped);
            if (cached != null) {
                out.set(i, cached);
                cacheHits.incrementAndGet();
                provider = "ollama:" + embedModel;
            } else {
                cacheMisses.incrementAndGet();
                pending.add(i);
                pendingTexts.add(clipped);
            }
        }
        for (int from = 0; from < pendingTexts.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(pendingTexts.size(), from + EMBED_BATCH_SIZE);
            List<String> batch = pendingTexts.subList(from, to);
            List<float[]> vectors = null;
            if (modelAlive(embedModel)) {
                BatchEmbedResult r = tryOllamaEmbedBatch(batch, embedModel);
                List<float[]> rv = r.vectors();
                record(embedModel, new EmbedResult(rv == null || rv.isEmpty() ? null : rv.get(0), r.permanentFail()));
                vectors = rv;
                if (rv != null && !rv.isEmpty()) provider = "ollama:" + embedModel;
            }
            for (int j = from; j < to; j++) {
                int idx = pending.get(j);
                String text = batch.get(j - from);
                float[] v = (vectors != null && j - from < vectors.size()) ? vectors.get(j - from) : null;
                if (v != null) {
                    out.set(idx, v);
                    if (!text.isEmpty()) cachePut(text, v); // 只缓存真实模型向量（哈希降级不缓存）
                } else {
                    out.set(idx, embedUncached(text)); // 批失败 → 单条回退（熔断/哈希降级）
                }
            }
        }
        return out;
    }

    /**
     * 嵌入缓存统计：hit/miss/hitRate/size。
     * size 通过 SCAN aikb:emb:{model}:* 实时统计；Redis 不可用时退回进程内写入计数。
     */
    public Map<String, Object> cacheStats() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        double hitRate = total == 0 ? 0.0 : Math.round(hits * 10000.0 / total) / 10000.0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hit", hits);
        m.put("miss", misses);
        m.put("hitRate", hitRate);
        m.put("size", cacheSize());
        return m;
    }

    private long cacheSize() {
        if (redis == null) return cacheWrites.get();
        long n = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(EMB_CACHE_PREFIX + embedModel + ":*").count(1000).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                n++;
            }
        } catch (Exception e) {
            return cacheWrites.get();
        }
        return n;
    }

    /** 未命中缓存后的真实计算：模型嵌入 → 失败统一哈希降级（不再读写缓存，避免统计重复计数） */
    private float[] embedUncached(String clipped) {
        if (clipped == null || clipped.isBlank()) return hashEmbed("");
        // 仅尝试配置的嵌入模型（对话模型绝不作为嵌入器——7B 载入即 CPU 炸弹）。
        // 熔断器：404=模型未安装视为永久失败（长冷却），超时类失败短冷却半开重试。
        float[] v = null;
        if (modelAlive(embedModel)) {
            EmbedResult r = tryOllamaEmbed(clipped, embedModel);
            record(embedModel, r);
            v = r.vector();
        }
        if (v != null) {
            provider = "ollama:" + embedModel;
            cachePut(clipped, v);
            return v;
        }
        provider = "hash-fallback";
        return hashEmbed(clipped);
    }

    /** 本地 qwen 对话补全（RAG 生成，兼容旧入口）：ai-gateway 的 VL 模型优先 → Ollama 备选；均不可达返回 null 降级 */
    public String chat(String systemPrompt, String userPrompt) {
        String viaGateway = chatViaGatewayVl(systemPrompt, userPrompt);
        if (viaGateway != null) return viaGateway;
        return chatOpenAiCompatible(ollamaBaseUrl + "/v1/chat/completions", chatModel,
                null, systemPrompt, userPrompt, 120);
    }

    /**
     * 工具增强问答（tools 自动路由）：走网关 tools="auto" 注入内置工具集
     * （web_search/fetch_page/context7_docs/think），由模型自主决定调用，工具结果在网关侧
     * agent 循环内回灌。返回 {answer, backend, toolTrace}；网关/模型不可达返回 null 由调用方兜底。
     */
    public Map<String, Object> chatViaGatewayTools(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", gatewayTextModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            body.put("stream", false);
            body.put("temperature", 0.3);
            body.put("max_tokens", 1024);
            body.put("use_kb", false);
            body.put("tools", "auto");
            HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180)) // agent 循环多轮推理 + 工具外呼，取长超时
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + gatewayApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[aikb] chat tools via gateway http {}", resp.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return null;
            provider = "gateway:" + gatewayTextModel + ":tools";
            lastChatBackend.set(provider);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("answer", content.trim());
            out.put("backend", provider);
            List<Object> toolTrace = new ArrayList<>();
            JsonNode trace = root.path("lanlink").path("tools").path("trace");
            if (trace.isArray()) {
                trace.forEach(t -> toolTrace.add(t));
            }
            out.put("toolTrace", toolTrace);
            return out;
        } catch (Exception e) {
            log.warn("[aikb] chat tools via gateway failed: {}", e.getMessage());
            return null;
        }
    }

    private String chatOpenAiCompatible(String url, String model, String bearer,
                                        String systemPrompt, String userPrompt, int timeoutSeconds) {
        return chatOpenAiCompatible(url, model, bearer, systemPrompt, userPrompt, timeoutSeconds, null, null);
    }

    /** OpenAI 兼容对话；numCtx/keepAlive 仅 Ollama 生效（num_ctx 加强 KV 上限，keep_alive 控制驻留） */
    private String chatOpenAiCompatible(String url, String model, String bearer,
                                        String systemPrompt, String userPrompt, int timeoutSeconds,
                                        Integer numCtx, String keepAlive) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            body.put("stream", false);
            body.put("temperature", 0.3);
            if (numCtx != null || keepAlive != null) {
                Map<String, Object> options = new LinkedHashMap<>();
                if (numCtx != null) options.put("num_ctx", numCtx);
                options.put("temperature", 0.3);
                body.put("options", options);
                if (keepAlive != null) body.put("keep_alive", keepAlive);
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (bearer != null) b.header("Authorization", bearer);
            HttpRequest req = b.build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[aikb] chat via {} http {}", model, resp.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return null;
            provider = (bearer != null ? "gateway:" : "ollama:") + model;
            lastChatBackend.set(provider);
            return content.trim();
        } catch (Exception e) {
            log.warn("[aikb] chat via {} failed: {}", model, e.getMessage());
            return null;
        }
    }

    // ==================== 嵌入缓存读写（Redis 不可用时透明降级） ====================

    private String cacheKey(String text) {
        return EMB_CACHE_PREFIX + embedModel + ":" + sha256Hex(text);
    }

    private float[] cacheGet(String text) {
        if (redis == null || text == null || text.isEmpty()) return null;
        try {
            var ops = redis.opsForValue();
            if (ops == null) return null;
            String raw = ops.get(cacheKey(text));
            if (raw == null || raw.isEmpty()) return null;
            int sep = raw.indexOf(':');
            String b64 = sep >= 0 ? raw.substring(sep + 1) : raw;
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (bytes.length < 4 || bytes.length % 4 != 0) return null;
            float[] v = new float[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
            double sq = 0;
            for (float x : v) sq += x * x;
            if (sq < 1e-12) return null;
            return v;
        } catch (Exception e) {
            return null; // 缓存故障不影响嵌入主链路
        }
    }

    private void cachePut(String text, float[] vector) {
        if (redis == null || text == null || text.isEmpty() || vector == null || vector.length == 0) return;
        try {
            ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float x : vector) buf.putFloat(x);
            String value = vector.length + ":" + Base64.getEncoder().encodeToString(buf.array());
            redis.opsForValue().set(cacheKey(text), value, EMB_CACHE_TTL);
            cacheWrites.incrementAndGet();
        } catch (Exception e) {
            // 缓存写入失败可忽略（下次重新计算）
        }
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    // ==================== 内部记录 / 熔断 ====================

    private record EmbedResult(float[] vector, boolean permanentFail) {}

    private record BatchEmbedResult(List<float[]> vectors, boolean permanentFail) {}

    private void record(String model, EmbedResult r) {
        if (model == null) return;
        if (r != null && r.vector() != null) { breaker.remove(model); return; }
        boolean permanent = r != null && r.permanentFail();
        long cooldown = permanent ? 24 * 3600_000L : BREAKER_COOLDOWN_MS;
        long[] cur = breaker.compute(model, (k, v) -> v == null
                ? new long[]{1, System.currentTimeMillis()}
                : new long[]{v[0] + 1, System.currentTimeMillis()});
        if (cur[0] >= BREAKER_THRESHOLD) {
            log.warn("[aikb] embed model {} tripped breaker ({} fails, permanent={}) — hash fallback for {}s",
                    model, cur[0], permanent, cooldown / 1000);
        }
    }

    private boolean modelAlive(String model) {
        if (model == null) return false;
        long[] cur = breaker.get(model);
        if (cur == null || cur[0] < BREAKER_THRESHOLD) return true;
        long cooldown = cur[0] >= BREAKER_THRESHOLD * 4 ? 24 * 3600_000L : BREAKER_COOLDOWN_MS;
        if (System.currentTimeMillis() - cur[1] >= cooldown) {
            breaker.remove(model); // 半开：冷却期后放行一次重试
            return true;
        }
        return false;
    }

    // ==================== Ollama 嵌入调用 ====================

    /** 批量嵌入：Ollama /api/embed 数组接口，一次 HTTP 返回多条向量（均 L2 归一化） */
    private BatchEmbedResult tryOllamaEmbedBatch(List<String> texts, String model) {
        if (model == null || model.isBlank() || texts == null || texts.isEmpty()) {
            return new BatchEmbedResult(null, true);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);
            HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(EMBED_BATCH_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                boolean permanent = resp.statusCode() == 404;
                log.info("[aikb] ollama batch embed {} unavailable (http {}, n={}, permanent={})",
                        model, resp.statusCode(), texts.size(), permanent);
                return new BatchEmbedResult(null, permanent);
            }
            JsonNode arr = objectMapper.readTree(resp.body()).path("embeddings");
            if (!arr.isArray() || arr.size() != texts.size()) {
                log.info("[aikb] ollama batch embed {} unexpected response (n={}, got={})",
                        model, texts.size(), arr.size());
                return new BatchEmbedResult(null, false);
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (JsonNode node : arr) {
                if (!node.isArray() || node.isEmpty()) return new BatchEmbedResult(null, false);
                vectors.add(toFloatArray(node));
            }
            return new BatchEmbedResult(vectors, false);
        } catch (Exception e) {
            log.info("[aikb] ollama batch embed {} failed: {}", model, e.getMessage());
            return new BatchEmbedResult(null, false);
        }
    }

    private EmbedResult tryOllamaEmbed(String text, String model) {
        if (model == null || model.isBlank()) return new EmbedResult(null, true);
        try {
            // 优先 /api/embed（新接口），失败回退 /api/embeddings（旧接口）
            String embedBody = objectMapper.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(embedBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode arr = objectMapper.readTree(resp.body()).path("embeddings");
                if (arr.isArray() && arr.size() > 0 && arr.get(0).isArray() && arr.get(0).size() > 0) {
                    return new EmbedResult(toFloatArray(arr.get(0)), false);
                }
            }
            String legacyBody = objectMapper.writeValueAsString(Map.of("model", model, "prompt", text));
            HttpRequest legacyReq = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/embeddings"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(legacyBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> legacyResp = http.send(legacyReq, HttpResponse.BodyHandlers.ofString());
            if (legacyResp.statusCode() == 200) {
                JsonNode arr = objectMapper.readTree(legacyResp.body()).path("embedding");
                if (arr.isArray() && arr.size() > 0) return new EmbedResult(toFloatArray(arr), false);
            }
            // 404 = 模型未安装（ollama pull 缺失）→ 永久失败，长冷却；避免反复把大模型载入拖垮 CPU
            boolean permanent = resp.statusCode() == 404 || legacyResp.statusCode() == 404;
            log.info("[aikb] ollama embed model {} unavailable (http {}/{}, permanent={})",
                    model, resp.statusCode(), legacyResp.statusCode(), permanent);
            return new EmbedResult(null, permanent);
        } catch (Exception e) {
            log.info("[aikb] ollama embed model {} failed: {}", model, e.getMessage());
            return new EmbedResult(null, false);
        }
    }

    private float[] toFloatArray(JsonNode arr) {
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) v[i] = (float) arr.get(i).asDouble();
        return normalize(v);
    }

    /**
     * 确定性哈希嵌入（降级）：CJK 双字组 + ASCII 词元多桶投影，L2 归一化。
     * 同文本恒得同向量（幂等），不同文本方向不同——检索退化为词面相似，但功能完整。
     */
    static float[] hashEmbed(String text) {
        float[] v = new float[FALLBACK_DIM];
        if (text == null || text.isEmpty()) return v;
        List<String> tokens = new ArrayList<>();
        String t = text.toLowerCase();
        // 英数词元
        for (String w : t.split("[^a-z0-9\\u4e00-\\u9fff]+")) {
            if (!w.isBlank()) tokens.add(w);
        }
        // CJK 单字 + 双字组
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                tokens.add(String.valueOf(c));
                if (i + 1 < t.length()) {
                    char n = t.charAt(i + 1);
                    if (n >= 0x4e00 && n <= 0x9fff) tokens.add(t.substring(i, i + 2));
                }
            }
        }
        for (String token : tokens) {
            int h = token.hashCode();
            int idx = Math.floorMod(h, FALLBACK_DIM);
            float sign = (h >>> 31) == 0 ? 1f : -1f;
            v[idx] += sign;
            // 第二桶降低碰撞影响
            int idx2 = Math.floorMod(h >>> 13, FALLBACK_DIM);
            v[idx2] += sign * 0.5f;
        }
        return normalize(v);
    }

    static float[] normalize(float[] v) {
        double sq = 0;
        for (float x : v) sq += x * x;
        double norm = Math.sqrt(sq);
        if (norm < 1e-9) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}

package com.lanlink.shopping.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 出站模型渠道配置管理（DeepSeek / 小米 MiMo / 阿里百炼 / Mimosa 等外部模型的 API Key 与地址）。
 *
 * <p>数据安全：
 * <ul>
 *   <li>API Key 用 AES-GCM 加密存储（t_outbound_model_config.api_key_cipher），
 *       主密钥取环境变量 OUTBOUND_KEY_SECRET（缺省退化为 support.outbound.key-secret）；</li>
 *   <li>回显接口只返回 api_key_prefix（如 sk-abc****），绝不回传明文；</li>
 *   <li>写入时 apiKey 为空 = 保留旧密钥不改；审计日志只记动作不记密钥。</li>
 * </ul>
 *
 * <p>运行时生效：{@link ExternalGreetingClient} 每次调用前从本服务读取启用渠道，
 * 改配置无需重启应用。
 */
@Service
public class OutboundModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(OutboundModelConfigService.class);
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    /**
     * 复用的 HTTP 客户端单例：HttpClient 内部维护连接池，反复新建会导致连接资源无法复用。
     * 连接/总耗时由每次请求的 HttpRequest.timeout 单独约束，故共享同一实例是安全的。
     */
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    // 连接测试/调用会真正触发一次模型推理：MiMo 等渠道首 token 到结束常需 5~15s，
    // 过短的超时（原 6s/25s）会把"能用但慢"的渠道误判为不可达，故放宽超时并单独标注超时。
    private static final int TEST_TIMEOUT_SECONDS = 30;
    private static final int INVOKE_TIMEOUT_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ChatCall chatCall;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 密钥加密主密钥：环境变量优先，缺省退化为配置值（开发环境便利，生产必须走环境变量） */
    @Value("${OUTBOUND_KEY_SECRET:${support.outbound.key-secret:lanlink-outbound-key-dev-secret}}")
    private String keySecret;

    @Value("${support.fallback.mimo-api-key:${MIMO_API_KEY:}}")
    private String envMimoApiKey;

    @Value("${support.fallback.mimo-base-url:${MIMO_BASE_URL:https://api.xiaomimimo.com/v1}}")
    private String envMimoBaseUrl;

    @Value("${support.fallback.qwen-api-key:${QWEN_API_KEY:${DASHSCOPE_API_KEY:}}}")
    private String envQwenApiKey;

    @Value("${support.fallback.qwen-base-url:${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}}")
    private String envQwenBaseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboundModelConfigService(JdbcTemplate jdbc, AuditService auditService) {
        this(jdbc, auditService, null);
    }

    /** 测试专用构造器：注入可 Mock 的 HTTP 调用，便于稳定覆盖各类网络异常边界。 */
    OutboundModelConfigService(JdbcTemplate jdbc, AuditService auditService, ChatCall chatCall) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.chatCall = chatCall == null ? this::callChat : chatCall;
    }

    @jakarta.annotation.PostConstruct
    public void initEnvChannels() {
        try {
            if (envMimoApiKey != null && !envMimoApiKey.isBlank()) {
                if (storedChannel("mimo") == null) {
                    upsert("mimo", envMimoBaseUrl, envMimoApiKey, "mimo-v2.6-flash", true, 0L, null);
                    log.info("[outbound-config] MIMO API Key auto-configured from environment");
                }
            }
            if (envQwenApiKey != null && !envQwenApiKey.isBlank()) {
                if (storedChannel("qwen") == null) {
                    upsert("qwen", envQwenBaseUrl, envQwenApiKey, "qwen-plus", true, 0L, null);
                    log.info("[outbound-config] Qwen API Key auto-configured from environment");
                }
            }
        } catch (Exception e) {
            log.warn("[outbound-config] env channels init skipped: {}", e.getMessage());
        }
    }

    /**
     * 允许的渠道白名单（防任意渠道名注入）。
     * 列表顺序 = 备用链路逐个降级尝试的顺序；新增渠道排在既有渠道之后，不改变原有优先级。
     */
    private static final List<String> ALLOWED_CHANNELS = List.of(
            "deepseek", "mimosa", "openai", "qwen", "chatglm", "ollama",
            // 小米 MiMo 开放平台（OpenAI 兼容协议）
            "mimo",
            // 阿里百炼 / DashScope（OpenAI 兼容模式）
            "bailian",
            "custom");

    /**
     * 统一拼接 OpenAI Chat Completions 端点，兼容 Base URL 是否已带版本段：
     * <ul>
     *   <li>https://api.deepseek.com → https://api.deepseek.com/v1/chat/completions</li>
     *   <li>https://api.deepseek.com/v1 → https://api.deepseek.com/v1/chat/completions</li>
     *   <li>https://open.bigmodel.cn/api/paas/v4 → …/v4/chat/completions</li>
     *   <li>…/v1/chat/completions → 原样返回</li>
     * </ul>
     */
    public static String chatCompletionsEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (base.isEmpty()) return "";
        if (base.endsWith("/chat/completions")) return base;
        if (base.matches("(?i).*/v\\d+[a-z0-9]*$")) return base + "/chat/completions";
        return base + "/v1/chat/completions";
    }

    public static String defaultModelFor(String channel) {
        if (channel == null) return "deepseek-flash";
        return switch (channel.toLowerCase().trim()) {
            // DeepSeek 当前支持的模型名（官方 400 提示：deepseek-flash / deepseek-v4-pro）
            case "deepseek" -> "deepseek-flash";
            case "openai" -> "gpt-4o-mini";
            case "qwen" -> "qwen-plus";
            case "chatglm" -> "glm-4-flash";
            case "mimosa" -> "mimosa";
            // 小米 MiMo 开放平台（https://api.xiaomimimo.com/v1），flash 版面向高频短任务
            case "mimo" -> "mimo-v2.6-flash";
            // 阿里百炼 DashScope 兼容模式（…/compatible-mode/v1）
            case "bailian" -> "qwen-plus";
            case "ollama" -> "qwen2.5:7b";
            default -> "default";
        };
    }

    public static String defaultBaseUrlFor(String channel) {
        if (channel == null) return "https://api.deepseek.com/v1";
        return switch (channel.toLowerCase().trim()) {
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "mimo" -> "https://api.xiaomimimo.com/v1";
            case "qwen", "bailian" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "openai" -> "https://api.openai.com/v1";
            case "chatglm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "ollama" -> "http://localhost:11434/v1";
            case "mimosa" -> "http://localhost:8090/v1";
            default -> "";
        };
    }

    /**
     * 脱敏列表（回显给管理端）：只含 channel/baseUrl/apiKeyPrefix/modelName/enabled，无明文。
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT channel, base_url, api_key_prefix, enabled FROM t_outbound_model_config ORDER BY channel");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            String ch = String.valueOf(row.get("channel"));
            m.put("channel", ch);
            m.put("baseUrl", row.get("base_url"));
            m.put("apiKeyPrefix", row.get("api_key_prefix"));
            m.put("enabled", ((Number) row.get("enabled")).intValue() == 1);
            String model = null;
            try {
                model = jdbc.query("SELECT model_name FROM t_outbound_model_config WHERE channel = ?",
                        rs -> rs.next() ? rs.getString(1) : null, ch);
            } catch (Exception ignored) {}
            m.put("modelName", org.springframework.util.StringUtils.hasText(model) ? model : defaultModelFor(ch));
            out.add(m);
        }
        return out;
    }

    /**
     * 新增/更新渠道配置。apiKey 为空或 null = 保留旧密钥（仅更新地址/模型/启用状态）。
     */
    public void upsert(String channel, String baseUrl, String apiKey, String modelName, boolean enabled,
                       Long userId, HttpServletRequest request) {
        String ch = channel == null ? "" : channel.trim().toLowerCase();
        if (!ALLOWED_CHANNELS.contains(ch)) {
            throw new IllegalArgumentException("不支持的渠道：" + channel);
        }
        if (baseUrl == null || baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            throw new IllegalArgumentException("Base URL 必须是 http(s) 地址");
        }
        boolean hasNewKey = apiKey != null && !apiKey.isBlank();
        String cipherText;
        String prefix;
        if (hasNewKey) {
            if (apiKey.length() < 8 || apiKey.length() > 200) {
                throw new IllegalArgumentException("API Key 长度异常（8-200 字符）");
            }
            cipherText = encrypt(apiKey);
            prefix = maskPrefix(apiKey);
        } else {
            // 保留旧密钥
            String oldCipher = jdbc.query(
                    "SELECT api_key_cipher FROM t_outbound_model_config WHERE channel = ?",
                    rs -> rs.next() ? rs.getString(1) : null, ch);
            if (oldCipher == null) {
                throw new IllegalArgumentException("首次配置该渠道必须提供 API Key");
            }
            cipherText = oldCipher;
            prefix = jdbc.query(
                    "SELECT api_key_prefix FROM t_outbound_model_config WHERE channel = ?",
                    rs -> rs.next() ? rs.getString(1) : "***", ch);
        }
        int updated = jdbc.update(
                "UPDATE t_outbound_model_config SET base_url = ?, api_key_cipher = ?, api_key_prefix = ?, "
                        + "enabled = ?, updated_by = ? WHERE channel = ?",
                baseUrl.trim(), cipherText, prefix, enabled ? 1 : 0, userId, ch);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO t_outbound_model_config (channel, base_url, api_key_cipher, api_key_prefix, enabled, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    ch, baseUrl.trim(), cipherText, prefix, enabled ? 1 : 0, userId);
        }
        if (modelName != null && !modelName.isBlank()) {
            try {
                jdbc.update("UPDATE t_outbound_model_config SET model_name = ? WHERE channel = ?", modelName.trim(), ch);
            } catch (Exception ignored) {}
        }
        // 审计：只记动作与渠道，不含密钥明文
        auditService.record(userId, "OUTBOUND_MODEL_CONFIG_UPDATED",
                "渠道 " + ch + (hasNewKey ? "（密钥已更换）" : "（密钥保留）") + " baseUrl=" + baseUrl.trim()
                        + " enabled=" + enabled, request);
        log.info("[outbound-config] channel={} updated, keyReplaced={}", ch, hasNewKey);
    }

    public void upsert(String channel, String baseUrl, String apiKey, boolean enabled,
                       Long userId, HttpServletRequest request) {
        upsert(channel, baseUrl, apiKey, null, enabled, userId, request);
    }

    /** 渠道配置（含解密后的密钥，仅供内部客户端调用，绝不外发） */
    public record ChannelConfig(String channel, String baseUrl, String apiKey, String modelName) {
        public ChannelConfig(String channel, String baseUrl, String apiKey) {
            this(channel, baseUrl, apiKey, defaultModelFor(channel));
        }
    }

    /** 取启用渠道的配置（解密返回）；不存在或未启用返回空。 */
    public Optional<ChannelConfig> activeChannel(String channel) {
        String ch = channel == null ? "" : channel.trim().toLowerCase();
        try {
            return jdbc.query(
                    "SELECT channel, base_url, api_key_cipher, enabled, model_name FROM t_outbound_model_config WHERE channel = ?",
                    rs -> {
                        if (!rs.next()) return Optional.empty();
                        if (rs.getInt("enabled") != 1) return Optional.empty();
                        String modelName = rs.getString("model_name");
                        if (!org.springframework.util.StringUtils.hasText(modelName)) {
                            modelName = defaultModelFor(rs.getString("channel"));
                        }
                        return Optional.of(new ChannelConfig(rs.getString("channel"), rs.getString("base_url"),
                                decrypt(rs.getString("api_key_cipher")), modelName.trim()));
                    }, ch);
        } catch (Exception e) {
            // 兼容尚未添加 model_name 列的旧库
            return jdbc.query(
                    "SELECT channel, base_url, api_key_cipher, enabled FROM t_outbound_model_config WHERE channel = ?",
                    rs -> {
                        if (!rs.next()) return Optional.empty();
                        if (rs.getInt("enabled") != 1) return Optional.empty();
                        return Optional.of(new ChannelConfig(rs.getString("channel"), rs.getString("base_url"),
                                decrypt(rs.getString("api_key_cipher")), defaultModelFor(rs.getString("channel"))));
                    }, ch);
        }
    }

    /** 所有启用渠道供客户端降级尝试。 */
    public List<ChannelConfig> activeChannels() {
        List<ChannelConfig> out = new ArrayList<>();
        for (String ch : ALLOWED_CHANNELS) {
            activeChannel(ch).ifPresent(out::add);
        }
        return out;
    }

    /**
     * 连接测试：向指定模型地址发送轻量连通性探测请求。
     */
    public Map<String, Object> testConnection(String channel, String baseUrl, String apiKey, String modelName) {
        String ch = channel == null ? "" : channel.trim().toLowerCase();
        String targetUrl = baseUrl == null ? "" : baseUrl.trim();
        String targetKey = apiKey == null ? "" : apiKey.trim();
        String targetModel = org.springframework.util.StringUtils.hasText(modelName) ? modelName.trim() : defaultModelFor(ch);

        if (targetKey.isBlank()) {
            // 优先从已保存配置中读取（不限 enabled 状态，便于停用渠道在测试成功后再行启用）
            ChannelConfig stored = storedChannel(ch);
            if (stored != null && org.springframework.util.StringUtils.hasText(stored.apiKey())) {
                targetKey = stored.apiKey();
                if (targetUrl.isBlank()) targetUrl = stored.baseUrl();
                // 未显式传模型名时，使用已保存渠道的模型，避免测试与实际调用不一致
                if (!org.springframework.util.StringUtils.hasText(modelName)) {
                    targetModel = stored.modelName();
                }
            } else {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("success", false);
                fail.put("reachable", false);
                fail.put("statusCode", 400);
                fail.put("error", "未提供 API Key，且该渠道尚未配置已保存的密钥");
                return fail;
            }
        }
        if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("reachable", false);
            fail.put("statusCode", 400);
            fail.put("error", "Base URL 必须是以 http(s) 开头的有效地址");
            return fail;
        }
        long start = System.currentTimeMillis();
        try {
            String endpoint = chatCompletionsEndpoint(targetUrl);
            List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "ping"));
            String minimalBody = objectMapper.writeValueAsString(Map.of(
                    "model", targetModel,
                    "messages", messages
            ));
            String fullBody = objectMapper.writeValueAsString(Map.of(
                    "model", targetModel,
                    "messages", messages,
                    "max_tokens", 5,
                    "temperature", 0
            ));
            Upstream res = chatCall.send(endpoint, targetKey, fullBody, TEST_TIMEOUT_SECONDS);
            boolean retried = false;
            // 部分兼容服务不接受 max_tokens/temperature，HTTP 400 时用最小请求体重试一次
            if (res.status() == 400) {
                res = chatCall.send(endpoint, targetKey, minimalBody, TEST_TIMEOUT_SECONDS);
                retried = true;
            }
            long latency = System.currentTimeMillis() - start;
            if (res.status() >= 200 && res.status() < 300) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("success", true);
                ok.put("reachable", true);
                ok.put("statusCode", res.status());
                ok.put("latencyMs", latency);
                ok.put("model", targetModel);
                ok.put("channel", ch);
                if (retried) ok.put("note", "已用最小请求体重试成功（上游不接受 max_tokens/temperature）");
                return ok;
            }
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("reachable", false);
            fail.put("statusCode", res.status());
            fail.put("latencyMs", latency);
            fail.put("error", upstreamErrorText(res));
            return fail;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            boolean timeout = isTimeout(e);
            // 连接被拒绝、域名无法解析等异常说明网络层未连通，必须判定为不可达；
            // 仅响应超时视为“可能已连通但响应慢”，保持 reachable=true 以提示稍后重试。
            boolean unreachable = isUnreachable(e);
            fail.put("reachable", !unreachable);
            fail.put("timeout", timeout);
            fail.put("statusCode", timeout ? 504 : 500);
            fail.put("latencyMs", latency);
            String errMsg = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            fail.put("error", timeout
                    ? "模型响应超时（渠道已连通，但该模型返回较慢，可稍后重试或改用更快的模型）"
                    : "请求异常：" + errMsg);
            return fail;
        }
    }

    /** 上游 HTTP 响应（状态码 + 响应体），包级可见以便测试注入 Mock 响应。 */
    record Upstream(int status, String body) {}

    /** 可替换的 HTTP 调用入口：生产走真实 HttpClient，测试注入 Mock 异常/响应。 */
    @FunctionalInterface
    interface ChatCall {
        Upstream send(String endpoint, String apiKey, String payload, int timeoutSeconds) throws Exception;
    }

    private Upstream callChat(String endpoint, String apiKey, String payload, int timeoutSeconds)
            throws Exception {
        // 复用单例 HTTP_CLIENT（共享连接池）；单次总耗时由 HttpRequest.timeout 约束
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build();
        var res = HTTP_CLIENT.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        return new Upstream(res.statusCode(), res.body() == null ? "" : res.body());
    }

    /** 是否超时：优先识别 HttpClient 超时类型，兼容部分 JDK/驱动仅返回 "timed out" 文案。 */
    static boolean isTimeout(Throwable e) {
        return e instanceof java.net.http.HttpTimeoutException
                || (e != null && e.getMessage() != null
                && e.getMessage().toLowerCase().contains("timed out"));
    }

    /**
     * 是否属于网络层不可达：连接被拒绝、域名无法解析、路由不可达、地址无法解析、连接超时。
     * 同时遍历 cause 链，避免异常被 IOException 等包装后误判为“可达”。
     */
    static boolean isUnreachable(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.nio.channels.UnresolvedAddressException
                    || current instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 从上游响应提取可读错误（OpenAI 兼容格式 error.message），避免前端只显示“HTTP 400”。 */
    private String upstreamErrorText(Upstream res) {
        String message = "";
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(res.body());
            message = root.path("error").path("message").asText("");
            if (message.isBlank()) message = root.path("message").asText("");
            if (message.isBlank()) message = root.path("detail").asText("");
        } catch (Exception ignored) {}
        if (message.isBlank()) message = res.body();
        message = abbreviate(message);
        return "上游模型返回 HTTP " + res.status() + (message.isBlank() ? "" : "：" + message);
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 400 ? oneLine.substring(0, 400) + "…" : oneLine;
    }

    /**
     * 通用外部大模型调用（管理后台调试 / 业务接入验证）。
     *
     * <p>baseUrl / apiKey 未填时回退到已保存的渠道配置（优先指定渠道，其次任一启用渠道）；
     * 响应不包含任何密钥信息，审计只记录渠道与耗时，不记录 prompt 内容。
     */
    public Map<String, Object> invoke(String channel, String baseUrl, String apiKey, String modelName,
                                      String prompt, Double temperature, Integer maxTokens,
                                      Long userId, HttpServletRequest request) {
        String cleanPrompt = prompt == null ? "" : prompt.trim();
        if (cleanPrompt.isEmpty()) throw new IllegalArgumentException("prompt 不能为空");
        if (cleanPrompt.length() > 4000) throw new IllegalArgumentException("prompt 过长（最多 4000 字符）");

        String ch = channel == null ? "" : channel.trim().toLowerCase();
        if (!ch.isEmpty() && !ALLOWED_CHANNELS.contains(ch)) {
            throw new IllegalArgumentException("不支持的渠道：" + channel);
        }

        String targetUrl = baseUrl == null ? "" : baseUrl.trim();
        String targetKey = apiKey == null ? "" : apiKey.trim();
        String targetModel = org.springframework.util.StringUtils.hasText(modelName)
                ? modelName.trim() : defaultModelFor(ch);

        ChannelConfig saved = null;
        if (!ch.isEmpty()) {
            saved = activeChannel(ch).orElse(null);
            if (saved == null) saved = storedChannel(ch);
        }
        if (saved == null) {
            saved = activeChannels().stream().findFirst().orElse(null);
        }
        if (saved != null) {
            if (targetUrl.isBlank()) targetUrl = saved.baseUrl();
            if (targetKey.isBlank()) targetKey = saved.apiKey();
            if (!org.springframework.util.StringUtils.hasText(modelName)) targetModel = saved.modelName();
            if (ch.isEmpty()) ch = saved.channel();
        }

        if (targetUrl.isBlank()) {
            return Map.of("success", false, "error", "未提供 Base URL，且没有可回退的已保存渠道");
        }
        if (!targetUrl.startsWith("http")) {
            return Map.of("success", false, "error", "Base URL 必须是以 http(s) 开头的有效地址");
        }
        if (targetKey.isBlank()) {
            return Map.of("success", false, "error", "未提供 API Key，且该渠道尚未保存密钥");
        }

        long start = System.currentTimeMillis();
        try {
            String endpoint = chatCompletionsEndpoint(targetUrl);
            int max = maxTokens == null ? 400 : Math.min(Math.max(1, maxTokens), 4000);
            double temp = temperature == null ? 0.3 : Math.min(Math.max(0, temperature), 2);
            List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", cleanPrompt));
            String minimalBody = objectMapper.writeValueAsString(Map.of(
                    "model", targetModel,
                    "messages", messages
            ));
            String fullBody = objectMapper.writeValueAsString(Map.of(
                    "model", targetModel,
                    "messages", messages,
                    "max_tokens", max,
                    "temperature", temp
            ));
            Upstream res = callChat(endpoint, targetKey, fullBody, INVOKE_TIMEOUT_SECONDS);
            boolean retried = false;
            // 部分兼容服务不接受 max_tokens/temperature，HTTP 400 时用最小请求体重试一次
            if (res.status() == 400) {
                res = callChat(endpoint, targetKey, minimalBody, INVOKE_TIMEOUT_SECONDS);
                retried = true;
            }
            long latency = System.currentTimeMillis() - start;
            String body = res.body();

            if (res.status() >= 200 && res.status() < 300) {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
                com.fasterxml.jackson.databind.JsonNode choice = root.path("choices").path(0);
                String content = choice.path("message").path("content").asText("");
                String note = null;
                // 推理类模型在 token 预算较小时只输出 reasoning_content、正文为空
                if (content.isBlank()) {
                    String reasoning = choice.path("message").path("reasoning_content").asText("");
                    if (!reasoning.isBlank()) {
                        content = reasoning;
                        note = "该模型仅返回推理内容（reasoning_content）";
                    }
                }
                if (content.isBlank()) {
                    note = "上游返回空内容，可能是 max_tokens 过小或模型未输出正文";
                }
                if (content.length() > 8000) content = content.substring(0, 8000) + "\n…（已截断）";
                auditService.record(userId, "OUTBOUND_MODEL_INVOKED",
                        "渠道 " + ch + " 调用成功 promptLen=" + cleanPrompt.length() + " latencyMs=" + latency, request);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("channel", ch);
                out.put("model", targetModel);
                out.put("latencyMs", latency);
                out.put("content", content);
                out.put("finishReason", choice.path("finish_reason").asText(""));
                if (retried) out.put("note", "已用最小请求体重试成功（上游不接受 max_tokens/temperature）");
                else if (note != null) out.put("note", note);
                return out;
            }

            auditService.record(userId, "OUTBOUND_MODEL_INVOKED",
                    "渠道 " + ch + " 调用失败 HTTP " + res.status(), request);
            log.warn("[outbound-invoke] channel {} upstream {}: {}", ch, res.status(), abbreviate(body));
            return Map.of("success", false, "statusCode", res.status(), "latencyMs", latency,
                    "error", upstreamErrorText(res));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            auditService.record(userId, "OUTBOUND_MODEL_INVOKED", "渠道 " + ch + " 调用异常", request);
            log.warn("[outbound-invoke] channel {} invoke failed: {}", ch, e.getMessage());
            boolean timeout = e instanceof java.net.http.HttpTimeoutException
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out"));
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("timeout", timeout);
            err.put("statusCode", timeout ? 504 : 500);
            err.put("latencyMs", latency);
            err.put("error", timeout
                    ? "模型响应超时（渠道已连通，但该模型返回较慢，可稍后重试或改用更快的模型）"
                    : "请求异常：" + e.getMessage());
            return err;
        }
    }

    /**
     * 读取已保存渠道配置（不校验 enabled，用于停用渠道的调试与重新启用前验证）。
     */
    private ChannelConfig storedChannel(String channel) {
        String ch = channel == null ? "" : channel.trim().toLowerCase();
        try {
            return jdbc.query(
                    "SELECT channel, base_url, api_key_cipher, model_name FROM t_outbound_model_config WHERE channel = ?",
                    rs -> {
                        if (!rs.next()) return null;
                        String model = rs.getString("model_name");
                        if (!org.springframework.util.StringUtils.hasText(model)) {
                            model = defaultModelFor(rs.getString("channel"));
                        }
                        return new ChannelConfig(rs.getString("channel"), rs.getString("base_url"),
                                decrypt(rs.getString("api_key_cipher")), model);
                    }, ch);
        } catch (Exception e) {
            // 兼容尚未添加 model_name 列的旧库
            return jdbc.query(
                    "SELECT channel, base_url, api_key_cipher FROM t_outbound_model_config WHERE channel = ?",
                    rs -> rs.next()
                            ? new ChannelConfig(rs.getString("channel"), rs.getString("base_url"),
                                    decrypt(rs.getString("api_key_cipher")), defaultModelFor(rs.getString("channel")))
                            : null,
                    ch);
        }
    }

    /**
     * AI 知识草稿生成（用于自主学习候选审核与沙盒）
     */
    public Map<String, Object> generateAiDraft(String question) {
        String cleanQ = question == null ? "" : question.trim();
        List<ChannelConfig> channels = activeChannels();
        if (!channels.isEmpty()) {
            for (ChannelConfig ch : channels) {
                try {
                    String endpoint = chatCompletionsEndpoint(ch.baseUrl());
                    String sysPrompt = "你是 LANLinkshopping 电商平台的专业客服与知识库专家。请根据用户的电商咨询问题，生成一段规范、亲切、准确的中文客服回答草稿（150字以内），并提取3至5个最核心的搜索匹配关键词（以逗号分隔）。"
                            + "输出格式必须严格为：\n回答：[回答内容]\n关键词：[词1,词2,词3]";
                    String userPrompt = "用户咨询问题：" + cleanQ;
                    String reqBody = objectMapper.writeValueAsString(Map.of(
                            "model", ch.modelName(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", sysPrompt),
                                    Map.of("role", "user", "content", userPrompt)
                            ),
                            "max_tokens", 300,
                            "temperature", 0.3
                    ));
                    var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                            .timeout(java.time.Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + ch.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody))
                            .build();
                    var res = HTTP_CLIENT.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() >= 200 && res.statusCode() < 300) {
                        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(res.body());
                        String content = root.path("choices").path(0).path("message").path("content").asText("");
                        if (org.springframework.util.StringUtils.hasText(content)) {
                            return parseDraftResponse(content, cleanQ);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[outbound-draft] channel {} generate draft failed: {}", ch.channel(), e.getMessage());
                }
            }
        }
        return generateLocalDraft(cleanQ);
    }

    private Map<String, Object> parseDraftResponse(String content, String question) {
        String answer = "";
        List<String> keywords = new ArrayList<>();
        String[] lines = content.split("\n");
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("回答：") || l.startsWith("回答:")) {
                answer = l.substring(3).trim();
            } else if (l.startsWith("关键词：") || l.startsWith("关键词:")) {
                String kwStr = l.substring(4).trim();
                for (String kw : kwStr.split("[,，、 ]+")) {
                    if (org.springframework.util.StringUtils.hasText(kw) && kw.length() <= 20) keywords.add(kw.trim());
                }
            }
        }
        if (answer.isBlank()) answer = content.replaceAll("(?m)^(回答|关键词)[：:].*", "").trim();
        if (keywords.isEmpty()) {
            keywords = List.of(question.length() > 10 ? question.substring(0, 10) : question);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestedAnswer", answer);
        out.put("suggestedKeywords", keywords);
        out.put("source", "ai");
        return out;
    }

    private Map<String, Object> generateLocalDraft(String question) {
        String q = question.toLowerCase();
        String answer = "您好！感谢您的咨询。我们已为您记录该问题，平台将持续完善相关服务。如需即时人工协助，请联系客服热线或在线客服。";
        List<String> keywords = new ArrayList<>();
        if (q.contains("物流") || q.contains("快递") || q.contains("发货") || q.contains("到哪")) {
            answer = "您好！关于物流配送进度，您可进入【我的订单】点击对应订单查询实时物流轨迹与运单号；如遇配送延误，我们可为您联系承运方催促。";
            keywords.addAll(List.of("物流", "快递", "运单", "发货", "配送"));
        } else if (q.contains("退款") || q.contains("退货") || q.contains("售后")) {
            answer = "您好！关于售后退换，在订单签收后支持通过订单详情申请售后服务，平台将在审核通过后为您原路退款或换货。";
            keywords.addAll(List.of("退款", "退货", "售后", "换货"));
        } else if (q.contains("发票") || q.contains("开票") || q.contains("支付")) {
            answer = "您好！订单支付完成后，可在【我的订单】-【申请开票】填写抬头信息开具电子发票；支持微信、支付宝及企业对公账户付款。";
            keywords.addAll(List.of("发票", "开票", "支付", "报销"));
        } else if (q.contains("优惠") || q.contains("活动") || q.contains("积分") || q.contains("会员")) {
            answer = "您好！平台会定期上线会员专享与满减促销活动，您可在【领券中心】与【会员专区】领取抵扣优惠并查看积分累计规则。";
            keywords.addAll(List.of("会员", "积分", "活动", "优惠", "促销"));
        } else {
            keywords.add(question.length() > 15 ? question.substring(0, 15) : question);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suggestedAnswer", answer);
        out.put("suggestedKeywords", keywords);
        out.put("source", "rule_template");
        return out;
    }

    // ===== 功能开关（t_support_feature_toggle） =====

    public boolean toggle(String key) {
        Integer v = jdbc.query(
                "SELECT enabled FROM t_support_feature_toggle WHERE toggle_key = ?",
                rs -> rs.next() ? rs.getInt(1) : null, key);
        return v != null && v == 1;
    }

    public void setToggle(String key, boolean on, Long userId, HttpServletRequest request) {
        int updated = jdbc.update(
                "UPDATE t_support_feature_toggle SET enabled = ? WHERE toggle_key = ?",
                on ? 1 : 0, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO t_support_feature_toggle (toggle_key, enabled) VALUES (?, ?)",
                    key, on ? 1 : 0);
        }
        auditService.record(userId, "SUPPORT_FEATURE_TOGGLE",
                key + " → " + (on ? "开启" : "关闭"), request);
    }

    // ===== 密钥脱敏与加解密 =====

    /** 脱敏前缀：保留前 6 位 + ****（如 sk-abc****） */
    static String maskPrefix(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "***";
        String head = apiKey.length() <= 6 ? apiKey : apiKey.substring(0, 6);
        return head + "****";
    }

    /** AES-GCM 加密：IV(12B) + 密文 Base64 编码 */
    String encrypt(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    String decrypt(String cipherText) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(all, 0, iv, 0, iv.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, iv.length, all.length - iv.length);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密钥解密失败（请检查 OUTBOUND_KEY_SECRET 是否一致）", e);
        }
    }

    /** 从主密钥派生 AES-256 密钥（SHA-256 截取） */
    private SecretKeySpec deriveKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(keySecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(hash, "AES");
    }
}

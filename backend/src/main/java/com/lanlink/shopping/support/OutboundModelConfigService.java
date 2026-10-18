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
 * 出站模型渠道配置管理（DeepSeek / Mimosa 等外部模型的 API Key 与地址）。
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

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 密钥加密主密钥：环境变量优先，缺省退化为配置值（开发环境便利，生产必须走环境变量） */
    @Value("${OUTBOUND_KEY_SECRET:${support.outbound.key-secret:lanlink-outbound-key-dev-secret}}")
    private String keySecret;

    public OutboundModelConfigService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** 允许的渠道白名单（防任意渠道名注入） */
    private static final List<String> ALLOWED_CHANNELS = List.of(
            "deepseek", "mimosa", "openai", "qwen", "chatglm", "ollama", "custom");

    public static String defaultModelFor(String channel) {
        if (channel == null) return "deepseek-chat";
        return switch (channel.toLowerCase().trim()) {
            case "deepseek" -> "deepseek-chat";
            case "openai" -> "gpt-4o-mini";
            case "qwen" -> "qwen-plus";
            case "chatglm" -> "glm-4-flash";
            case "mimosa" -> "mimosa";
            case "ollama" -> "qwen2.5:7b";
            default -> "default";
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
        return jdbc.query(
                "SELECT channel, base_url, api_key_cipher, enabled FROM t_outbound_model_config WHERE channel = ?",
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    if (rs.getInt("enabled") != 1) return Optional.empty();
                    String apiKey = decrypt(rs.getString("api_key_cipher"));
                    String modelName = defaultModelFor(rs.getString("channel"));
                    try {
                        String m = rs.getString("model_name");
                        if (org.springframework.util.StringUtils.hasText(m)) modelName = m.trim();
                    } catch (Exception ignored) {}
                    return Optional.of(new ChannelConfig(rs.getString("channel"), rs.getString("base_url"), apiKey, modelName));
                }, ch);
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
            var existing = activeChannel(ch);
            if (existing.isPresent()) {
                targetKey = existing.get().apiKey();
                if (targetUrl.isBlank()) targetUrl = existing.get().baseUrl();
            } else {
                return Map.of("success", false, "error", "未提供 API Key，且该渠道尚未配置已保存的密钥");
            }
        }
        if (targetUrl.isBlank() || !targetUrl.startsWith("http")) {
            return Map.of("success", false, "error", "Base URL 必须是以 http(s) 开头的有效地址");
        }
        long start = System.currentTimeMillis();
        try {
            String endpoint = targetUrl.replaceAll("/+$", "") + "/v1/chat/completions";
            String reqBody = objectMapper.writeValueAsString(Map.of(
                    "model", targetModel,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 5,
                    "temperature", 0
            ));
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(4))
                    .build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + targetKey)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();
            var res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return Map.of("success", true, "latencyMs", latency, "model", targetModel, "channel", ch);
            } else {
                String snippet = res.body().length() > 200 ? res.body().substring(0, 200) : res.body();
                return Map.of("success", false, "statusCode", res.statusCode(), "latencyMs", latency,
                        "error", "上游模型返回 HTTP " + res.statusCode() + "：" + snippet);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return Map.of("success", false, "latencyMs", latency, "error", "请求异常：" + e.getMessage());
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
                    String endpoint = ch.baseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
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
                    var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
                    var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                            .timeout(java.time.Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + ch.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody))
                            .build();
                    var res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
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

package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 出站模型渠道统一调用器（out/ai-polish-design.md §3.5/§3.7 公共组件）：
 * mimosa（本地 AI 网关）稳定排首 → 最多 {@code maxAttempts} 个启用渠道降级尝试 →
 * 单请求 {@code timeoutSeconds} 超时。请求体 temperature/max_tokens 由调用方给出，
 * 恒带 {@code response_format={"type":"json_object"}}（OpenAI JSON 模式，不支持的网关忽略）
 * 与 {@code use_kb=false}（本地网关扩展字段：纯模型推理，不被客服问答 RAG 上下文带偏）。
 *
 * <p>输出解析只有一条路：stripFence + extractJson 严格 JSON 提取；非严格 JSON 一律视为
 * 该渠道失败——调用方不得拿模型原文当展示内容兜底（AI 标题润色场景原文不能直接当标题）。
 * 渠道/模型名只随结果返回给调用方进审计，不外传给终端用户。
 *
 * <p>PriceMonitorAiService 迁移到本组件为 P2（既有测试行为兜底，P0 不动稳定 AI 链路）。
 */
@Component
public class AiChannelInvoker {

    private static final Logger log = LoggerFactory.getLogger(AiChannelInvoker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 渠道尝试结果。outcome: ok | http-error | non-json | error。 */
    public record ChannelAttempt(String channel, String model, String outcome, JsonNode parsed) {
        public boolean ok() { return "ok".equals(outcome); }
    }

    /** HTTP 传输抽象：生产走 java.net.http.HttpClient，单测注入桩实现。 */
    @FunctionalInterface
    public interface Transport {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }

    private final OutboundModelConfigService configService;
    private final int maxAttempts;
    private final int timeoutSeconds;
    private final Transport transport;

    @Autowired
    public AiChannelInvoker(OutboundModelConfigService configService) {
        this(configService, DEFAULT_MAX_ATTEMPTS, DEFAULT_TIMEOUT_SECONDS, defaultTransport());
    }

    /** 测试/专用场景：自定义尝试数、超时与传输桩。 */
    public AiChannelInvoker(OutboundModelConfigService configService, int maxAttempts,
                            int timeoutSeconds, Transport transport) {
        this.configService = configService;
        this.maxAttempts = maxAttempts;
        this.timeoutSeconds = timeoutSeconds;
        this.transport = transport;
    }

    private static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (request) -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 单次多渠道调用：mimosa 优先，最多 maxAttempts 次；全部失败返回最后一个 attempt（parsed=null）。 */
    public ChannelAttempt call(double temperature, int maxTokens, String systemPrompt, String userPrompt) {
        int attempts = 0;
        ChannelAttempt last = null;
        for (OutboundModelConfigService.ChannelConfig ch : orderedChannels()) {
            if (attempts >= maxAttempts) break;
            attempts++;
            String model = ch.modelName() == null || ch.modelName().isBlank()
                    ? OutboundModelConfigService.defaultModelFor(ch.channel()) : ch.modelName();
            try {
                HttpRequest request = buildRequest(ch, model, temperature, maxTokens, systemPrompt, userPrompt);
                HttpResponse<String> resp = transport.send(request);
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.warn("[ai-channel] provider {} HTTP {}", ch.channel(), resp.statusCode());
                    last = new ChannelAttempt(ch.channel(), model, "http-error", null);
                    continue;
                }
                JsonNode root = JSON.readTree(resp.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                JsonNode parsed = extractJson(stripFence(content));
                if (parsed != null) {
                    return new ChannelAttempt(ch.channel(), model, "ok", parsed);
                }
                // 非严格 JSON 不做原文兜底：模型原文不能当结构化结果展示，视为该渠道失败
                log.warn("[ai-channel] provider {} returned non-strict JSON", ch.channel());
                last = new ChannelAttempt(ch.channel(), model, "non-json", null);
            } catch (Exception e) {
                log.warn("[ai-channel] provider {} failed: {}", ch.channel(), e.getMessage());
                last = new ChannelAttempt(ch.channel(), model, "error", null);
            }
        }
        return last == null ? new ChannelAttempt("none", "none", "error", null) : last;
    }

    /** 本地 AI 网关（mimosa）优先，其次其余启用渠道（白名单顺序即降级顺序）。 */
    public List<OutboundModelConfigService.ChannelConfig> orderedChannels() {
        List<OutboundModelConfigService.ChannelConfig> list = new ArrayList<>(configService.activeChannels());
        list.sort((a, b) -> Integer.compare(
                "mimosa".equalsIgnoreCase(a.channel()) ? 0 : 1,
                "mimosa".equalsIgnoreCase(b.channel()) ? 0 : 1));
        return list;
    }

    private HttpRequest buildRequest(OutboundModelConfigService.ChannelConfig ch, String model,
                                     double temperature, int maxTokens, String systemPrompt,
                                     String userPrompt) throws Exception {
        String url = OutboundModelConfigService.chatCompletionsEndpoint(ch.baseUrl());
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.putObject("response_format").put("type", "json_object");
        body.put("use_kb", false);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ch.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
    }

    /** 去掉 ```json / ``` 代码围栏与首尾空白。 */
    public static String stripFence(String content) {
        if (content == null) return "";
        String s = content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    /** 从文本中提取第一个完整 JSON 对象；不存在或非对象返回 null。 */
    public static JsonNode extractJson(String content) {
        if (content == null) return null;
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = JSON.readTree(s.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}

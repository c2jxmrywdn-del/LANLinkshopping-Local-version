package com.lanlink.shopping.support;

import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * OpenAI 兼容模型提供商网关边界（出站安全防护 + Grounding 规范支持）。
 * 实施 SSRF 阻断、协议校验与注入防线。
 */
@Service
public class AiProviderGateway {
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "169.254.169.254", "metadata.google.internal", "instance-data"
    );

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public boolean isConfigured(String baseUrl, String apiKey) {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    public static void validateBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) throw new IllegalArgumentException("baseUrl cannot be empty");
        try {
            URI u = URI.create(baseUrl.trim());
            String scheme = u.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new SecurityException("Invalid URL scheme (only http/https allowed): " + scheme);
            }
            String host = u.getHost();
            if (host != null && BLOCKED_HOSTS.contains(host.toLowerCase())) {
                throw new SecurityException("SSRF blocked host: " + host);
            }
        } catch (Exception e) {
            throw new SecurityException("Illegal AI provider base URL: " + e.getMessage(), e);
        }
    }

    public String chat(String baseUrl, String apiKey, String model, String prompt) {
        if (!isConfigured(baseUrl, apiKey)) throw new IllegalArgumentException("AI provider is not configured");
        validateBaseUrl(baseUrl);
        String body = "{\"model\":\"" + escape(model) + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(prompt) + "\"}]}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OutboundModelConfigService.chatCompletionsEndpoint(baseUrl)))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            String respBody = response.body();
            if (AnalysisSafetyFilter.containsInjectionEcho(respBody)) {
                throw new SecurityException("Model response dropped due to prompt injection echo");
            }
            return respBody;
        } catch (Exception e) { throw new IllegalStateException("AI provider request failed", e); }
    }

    /**
     * 带知识接地（Grounding）与安全约束的外部模型调用
     */
    public String chatWithGrounding(String baseUrl, String apiKey, String model, String systemPrompt, String grounding, String question) {
        if (!isConfigured(baseUrl, apiKey)) throw new IllegalArgumentException("AI provider is not configured");
        validateBaseUrl(baseUrl);
        String cleanSystem = StringUtils.hasText(systemPrompt) ? systemPrompt : "You are a helpful customer service assistant.";
        String cleanGrounding = StringUtils.hasText(grounding) ? AnalysisSafetyFilter.sanitize(grounding) : "(no facts available)";
        String cleanQuestion = AnalysisSafetyFilter.sanitize(question);

        String userContent = "【Platform Reference Data】\n" + cleanGrounding + "\n\n【Question】\n" + cleanQuestion;
        String body = "{\"model\":\"" + escape(model) + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escape(cleanSystem) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escape(userContent) + "\"}"
                + "]}";

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OutboundModelConfigService.chatCompletionsEndpoint(baseUrl)))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            String respBody = response.body();
            if (AnalysisSafetyFilter.containsInjectionEcho(respBody)) {
                throw new SecurityException("Model response dropped due to prompt injection echo");
            }
            return respBody;
        } catch (Exception e) {
            throw new IllegalStateException("AI provider request with grounding failed", e);
        }
    }

    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}

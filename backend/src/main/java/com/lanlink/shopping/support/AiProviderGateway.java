package com.lanlink.shopping.support;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** OpenAI-compatible provider boundary. It is opt-in and never used for local knowledge answers. */
@Service
public class AiProviderGateway {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public boolean isConfigured(String baseUrl, String apiKey) { return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey); }

    public String chat(String baseUrl, String apiKey, String model, String prompt) {
        if (!isConfigured(baseUrl, apiKey)) throw new IllegalArgumentException("AI provider is not configured");
        String body = "{\"model\":\"" + escape(model) + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(prompt) + "\"}]}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OutboundModelConfigService.chatCompletionsEndpoint(baseUrl)))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
            return response.body();
        } catch (Exception e) { throw new IllegalStateException("AI provider request failed", e); }
    }

    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}

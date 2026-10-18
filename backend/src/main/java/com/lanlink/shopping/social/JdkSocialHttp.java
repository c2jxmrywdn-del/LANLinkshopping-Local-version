package com.lanlink.shopping.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 JDK HttpClient 的实现（不引入任何新依赖）。
 * 超时收敛在 8s，避免第三方不可用拖垮登录线程。
 */
@Component
public class JdkSocialHttp implements SocialHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String postForm(String url, Map<String, String> form, Map<String, String> headers) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) headers.forEach(b::header);
        return send(b.build(), url);
    }

    @Override
    public String get(String url, Map<String, String> headers) {
        return get(url, headers, 8000L);
    }

    @Override
    public String get(String url, Map<String, String> headers, long timeoutMs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(200L, timeoutMs)))
                .header("Accept", "application/json")
                .GET();
        if (headers != null) headers.forEach(b::header);
        return send(b.build(), url);
    }

    private String send(HttpRequest req, String url) {
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new BusinessException("社交平台响应异常（HTTP " + res.statusCode() + "）");
            }
            return res.body();
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("社交平台请求被中断，请重试");
        } catch (Exception e) {
            throw new BusinessException("无法访问社交平台：" + host(url) + "（请检查网络与凭证配置）");
        }
    }

    /** 解析为 JSON（便于单测与各平台客户端复用） */
    public JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new BusinessException("社交平台返回内容无法解析");
        }
    }

    private static String host(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}

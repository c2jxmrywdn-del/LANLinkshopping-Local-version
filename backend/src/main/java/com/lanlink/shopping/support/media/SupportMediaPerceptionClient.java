package com.lanlink.shopping.support.media;

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
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 客服媒体感知客户端：OCR / 视觉 / ASR / 视频抽帧。
 *
 * <p>为什么全部返回 {@link Optional} 而不是抛异常：图片链路要 OCR 与视觉并发，一边失败
 * 不应把另一边已经拿到的结果一起丢掉；消费端只在这两条通道都空时才判整体失败并重试。
 *
 * <p>HttpClient 是进程级单例：每次请求都 new 一个客户端会重复建连接池与 TLS 会话，
 * 在高频消费下反而把延迟推高。连接 3s、普通请求 60s、视频抽帧 120s 的分档，
 * 是因为视频要解封装 + 逐帧解码，和一次 OCR 完全不是一个量级。
 *
 * <p>日志只打 URI 路径与状态码，绝不打 Base64 内容：媒体是访客隐私，落日志等于二次泄露。
 */
@Component
public class SupportMediaPerceptionClient {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaPerceptionClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration VIDEO_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration LOCAL_VISION_TIMEOUT = Duration.ofSeconds(150);
    private static final String ASR_LANGUAGE = "zh";
    /** /v1/vision 的 OpenAI 壳响应里，backend=none 表示本地视觉腿不可用（显存不足/排队超时/模型缺失） */
    private static final String LOCAL_BACKEND_NONE = "none";

    private final String ocrBaseUrl;
    private final String asrBaseUrl;
    private final String gatewayBaseUrl;
    private final String gatewayApiKey;
    /** 本地 qwen2.5-vl 腿开关：llama.cpp GPU 腿在单卡 5GB 显存口径不可载，默认关闭（严禁 GPU 依赖），
     *  仅多 GPU 部署显式开启后才作为 /v1/vision-mimo 之后的第二级降级 */
    private final boolean localVisionEnabled;

    public SupportMediaPerceptionClient(
            @Value("${OCR_SERVICE_BASE_URL:http://ocr-service:8091}") String ocrBaseUrl,
            @Value("${ASR_SERVICE_BASE_URL:http://asr-service:8092}") String asrBaseUrl,
            @Value("${AI_GATEWAY_BASE_URL:http://lanlink-ai-gateway:8090/v1}") String gatewayBaseUrl,
            @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String gatewayApiKey,
            @Value("${AI_VISION_LOCAL_ENABLED:false}") boolean localVisionEnabled) {
        this.ocrBaseUrl = trimSlash(ocrBaseUrl);
        this.asrBaseUrl = trimSlash(asrBaseUrl);
        this.gatewayBaseUrl = trimSlash(gatewayBaseUrl);
        this.gatewayApiKey = gatewayApiKey;
        this.localVisionEnabled = localVisionEnabled;
    }

    public record VisionResult(String text, String engine, String model) {
    }

    /** audioSeconds：云端取上游 usage.seconds；本地兜底取网关按分段/probe 求和的回填。计量落账用 */
    public record AsrResult(String text, String engine, String model, java.math.BigDecimal audioSeconds) {
    }

    public record Frame(int index, long timeMs, String imageBase64) {
    }

    public record VideoFrames(List<Frame> frames, byte[] audio, Long durationMs, boolean truncated) {
    }

    /** 本地 OCR：返回识别全文；优先直连 ocrBaseUrl，不可达或失败时由网关 /v1/ocr 兜底代理 */
    public Optional<String> ocr(byte[] image, String mime) {
        ObjectNode body = JSON.createObjectNode();
        body.put("imageBase64", Base64.getEncoder().encodeToString(image));
        body.put("lang", "ch");
        Optional<String> text = postJson(URI.create(ocrBaseUrl + "/ocr"), body, REQUEST_TIMEOUT, false)
                .map(root -> root.path("text").asText(""))
                .filter(t -> !t.isBlank());
        if (text.isPresent()) {
            return text;
        }
        return postJson(URI.create(gatewayBaseUrl + "/ocr"), body, REQUEST_TIMEOUT, true)
                .map(root -> root.path("text").asText(""))
                .filter(t -> !t.isBlank());
    }

    /** 网关视觉（MiMo）：prompt 由调用方按场景给，避免把业务措辞散落在客户端里 */
    public Optional<VisionResult> vision(byte[] image, String mime, String prompt) {
        ObjectNode body = JSON.createObjectNode();
        body.put("prompt", prompt);
        body.put("imageBase64", Base64.getEncoder().encodeToString(image));
        body.put("mime", mime == null || mime.isBlank() ? "image/jpeg" : mime);
        return postJson(URI.create(gatewayBaseUrl + "/vision-mimo"), body, REQUEST_TIMEOUT, true)
                .map(root -> {
                    String text = root.path("text").asText("");
                    if (text.isBlank()) {
                        return null;
                    }
                    return new VisionResult(text,
                            root.path("engine").asText("mimo"),
                            root.path("model").asText(""));
                });
    }

    /**
     * 视觉理解降级链（P2 扩容）：/v1/vision-mimo（MiMo 云视觉，主）→ /v1/vision（本地
     * qwen2.5-vl，仅 AI_VISION_LOCAL_ENABLED=true 时启用；llama.cpp GPU 腿在单卡 5GB
     * 显存口径不可载，默认关）→ RapidOCR 本地兜底（OCR_SERVICE_BASE_URL 直连优先、
     * 网关 /v1/ocr 代理兜底）——三段全空才返回 empty，交由调用方给出明确文案。
     *
     * <p>/v1/vision 是 OpenAI completion 壳：choices[0].message.content 为回答文本，
     * lanlink.backend=none 表示本地视觉腿不可用（显存不足/排队超时），此时也继续走 OCR。
     */
    public Optional<VisionResult> visionWithFallback(byte[] image, String mime, String prompt) {
        Optional<VisionResult> cloud = vision(image, mime, prompt);
        if (cloud.isPresent()) {
            return cloud;
        }
        if (localVisionEnabled) {
            Optional<VisionResult> local = localVision(image, mime, prompt);
            if (local.isPresent()) {
                return local;
            }
        }
        return ocr(image, mime)
                .filter(t -> !t.isBlank())
                .map(text -> new VisionResult(text, "rapidocr", "rapidocr-onnx"));
    }

    /** 本地 qwen2.5-vl 腿：/v1/vision 的 OpenAI 壳协议；backend=none 视为不可用 */
    private Optional<VisionResult> localVision(byte[] image, String mime, String prompt) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", "qwen2.5-vl");
        body.put("prompt", prompt);
        body.put("image_base64", Base64.getEncoder().encodeToString(image));
        body.put("mime", mime == null || mime.isBlank() ? "image/jpeg" : mime);
        return postJson(URI.create(gatewayBaseUrl + "/vision"), body, LOCAL_VISION_TIMEOUT, true)
                .map(root -> {
                    String text = root.path("choices").path(0).path("message").path("content").asText("");
                    if (text.isBlank()) {
                        return null;
                    }
                    String backend = root.path("lanlink").path("backend").asText("");
                    if (LOCAL_BACKEND_NONE.equalsIgnoreCase(backend)) {
                        return null;
                    }
                    return new VisionResult(text, "local-vl",
                            root.path("lanlink").path("vision_model").asText("qwen2.5-vl"));
                });
    }

    /** 网关 ASR（AI_ASR_* 云通道主、本地 faster-whisper CPU 兜底）：engine/usage 落库便于回溯通道与计量 */
    public Optional<AsrResult> asr(byte[] audio, String mime) {
        ObjectNode body = JSON.createObjectNode();
        body.put("audioBase64", Base64.getEncoder().encodeToString(audio));
        body.put("mime", mime == null || mime.isBlank() ? "audio/mpeg" : mime);
        body.put("language", ASR_LANGUAGE);
        return postJson(URI.create(gatewayBaseUrl + "/asr"), body, REQUEST_TIMEOUT, true)
                .map(root -> {
                    String text = root.path("text").asText("");
                    if (text.isBlank()) {
                        return null;
                    }
                    JsonNode seconds = root.path("usage").path("seconds");
                    java.math.BigDecimal audioSeconds = seconds.isNumber()
                            ? seconds.decimalValue() : null;
                    return new AsrResult(text,
                            root.path("engine").asText("mimo"),
                            root.path("model").asText(""),
                            audioSeconds);
                });
    }

    /** 视频抽帧 + 音轨：一次调用拿回关键帧与音频，避免视频被下载两遍 */
    public Optional<VideoFrames> videoFrames(byte[] video) {
        ObjectNode body = JSON.createObjectNode();
        body.put("videoBase64", Base64.getEncoder().encodeToString(video));
        body.put("fps", 1);
        body.put("maxFrames", 8);
        body.put("maxSeconds", 30);
        Optional<JsonNode> res = postJson(URI.create(asrBaseUrl + "/frames"), body, VIDEO_TIMEOUT, false);
        if (res.isEmpty()) {
            res = postJson(URI.create(gatewayBaseUrl + "/frames"), body, VIDEO_TIMEOUT, true);
        }
        return res.map(root -> {
                    List<Frame> frames = new ArrayList<>();
                    JsonNode array = root.path("frames");
                    if (array.isArray()) {
                        for (JsonNode node : array) {
                            String base64 = node.path("imageBase64").asText("");
                            if (!base64.isBlank()) {
                                frames.add(new Frame(node.path("index").asInt(frames.size()),
                                        node.path("timeMs").asLong(0L), base64));
                            }
                        }
                    }
                    byte[] audio = null;
                    String audioBase64 = root.path("audioBase64").asText("");
                    if (!audioBase64.isBlank()) {
                        try {
                            audio = Base64.getDecoder().decode(audioBase64);
                        } catch (IllegalArgumentException e) {
                            log.warn("[support-media] 抽帧服务返回的音轨 Base64 不合法，忽略音轨");
                        }
                    }
                    Long durationMs = root.hasNonNull("durationMs") ? root.path("durationMs").asLong() : null;
                    return new VideoFrames(frames, audio, durationMs, root.path("truncated").asBoolean(false));
                });
    }

    private Optional<JsonNode> postJson(URI uri, ObjectNode body, Duration timeout, boolean withGatewayAuth) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
            if (withGatewayAuth) {
                builder.header("Authorization", "Bearer " + gatewayApiKey);
            }
            HttpResponse<String> response = HTTP.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[support-media] 感知服务返回异常 path={} status={}", uri.getPath(), response.statusCode());
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[support-media] 感知服务调用被中断 path={}", uri.getPath());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[support-media] 感知服务调用失败 path={} err={}", uri.getPath(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

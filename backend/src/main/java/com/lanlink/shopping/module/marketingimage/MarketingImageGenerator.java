package com.lanlink.shopping.module.marketingimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.module.marketingimage.MarketingImageTaskRepository.TaskRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * 营销图异步生成执行器。
 *
 * <p>独立成 Bean 的关键原因：@Async 依赖 Spring 代理，若把 generate 写在
 * MarketingImageService 内部自调用会绕过代理变成同步执行（请求线程被 120s 外呼拖死）。
 */
@Component
public class MarketingImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(MarketingImageGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** 上游返回图片的体积上限：超过即拒绝，避免把对象存储和内存当成无上限缓冲 */
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final MarketingImageTaskRepository repository;
    private final StorageService storageService;
    private final MarketingImageProperties props;

    public MarketingImageGenerator(MarketingImageTaskRepository repository,
                                   StorageService storageService,
                                   MarketingImageProperties props) {
        this.repository = repository;
        this.storageService = storageService;
        this.props = props;
    }

    @Async(MarketingImageConfig.EXECUTOR)
    public void generate(String taskNo) {
        Optional<TaskRow> found = repository.findByTaskNo(taskNo);
        if (found.isEmpty()) {
            log.warn("[marketing-image] 任务 {} 不存在，跳过生成", taskNo);
            return;
        }
        TaskRow task = found.get();
        try {
            byte[] raw = requestImage(task);
            // 水印必须在落存储前绘制：对外 URL 一落地就应携带可识别标记
            byte[] png = MarketingImageWatermark.applyPng(raw);
            String objectKey = objectKeyOf(task);
            String imageUrl = storageService.put(objectKey, png, "image/png");
            repository.markSuccess(taskNo, objectKey, imageUrl, props.getUnitCostMicros());
            log.info("[marketing-image] 任务 {} 生成成功，{} bytes -> {}", taskNo, png.length, objectKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.markFailed(taskNo, "生成任务被中断，请重试");
        } catch (Exception e) {
            String message = friendlyError(e);
            log.warn("[marketing-image] 任务 {} 生成失败: {}", taskNo, message);
            repository.markFailed(taskNo, truncate(message, 500));
        }
    }

    /** 调用 OpenAI 兼容图像端点并取出图片字节（url 或 b64_json 二选一）。 */
    private byte[] requestImage(TaskRow task) throws IOException, InterruptedException {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new UpstreamException("未配置 AI_WANX_API_KEY，无法调用万相图像生成");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("model", task.model());
        body.put("prompt", task.prompt());
        body.put("n", 1);
        body.put("size", task.size());

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new UpstreamException(upstreamMessage(response.statusCode(), response.body()));
        }

        JsonNode data = JSON.readTree(response.body()).path("data");
        JsonNode first = data.isArray() && !data.isEmpty() ? data.get(0) : null;
        if (first == null) {
            throw new UpstreamException("上游未返回图片数据");
        }
        String url = first.path("url").asText("");
        if (!url.isBlank()) {
            return download(url);
        }
        String b64 = first.path("b64_json").asText("");
        if (!b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }
        throw new UpstreamException("上游返回中缺少 url/b64_json");
    }

    private String endpoint() {
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/images/generations";
    }

    /** 下载上游图片；只允许 http(s)，并按 Content-Length 与实收字节双重限制 20MB。 */
    private byte[] download(String url) throws IOException, InterruptedException {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new UpstreamException("上游返回了非法的图片地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new UpstreamException("图片下载失败（HTTP " + response.statusCode() + "）");
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > MAX_IMAGE_BYTES) {
            throw new UpstreamException("生成图片超过 20MB 上限");
        }
        byte[] bytes = response.body();
        if (bytes.length == 0) {
            throw new UpstreamException("下载到的图片为空");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new UpstreamException("生成图片超过 20MB 上限");
        }
        return bytes;
    }

    private String objectKeyOf(TaskRow task) {
        return "marketing/" + LocalDate.now().format(MONTH_FMT) + "/" + task.ownerType()
                + "/" + task.ownerId() + "/" + task.taskNo() + ".png";
    }

    /**
     * 把上游错误翻译成运营/商户能直接行动的中文：
     * 403 与 insufficient_quota 统一指向「余额/仅免费模式」，并保留上游原文便于排查。
     */
    private String upstreamMessage(int status, String responseBody) {
        String message = "";
        String code = "";
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode error = root.path("error");
            message = error.path("message").asText("");
            code = error.path("code").asText("");
            if (message.isBlank() && root.path("message").isTextual()) {
                message = root.path("message").asText("");
            }
        } catch (Exception ignored) {
            // 上游返回非 JSON（网关 HTML/纯文本）时只用状态码兜底
        }
        String base;
        if (status == 403 || code.toLowerCase(Locale.ROOT).contains("insufficient_quota")) {
            base = "额度不足，请在百炼/代理控制台充值或关闭仅免费模式";
        } else if (status == 404) {
            base = "该图像模型未在当前代理开通（HTTP 404），请更换模型或联系平台开通";
        } else if (status == 401) {
            base = "万相鉴权失败，请检查 AI_WANX_API_KEY";
        } else if (status == 429) {
            base = "上游限流，请稍后重试";
        } else {
            base = "上游图像生成失败（HTTP " + status + "）";
        }
        return message.isBlank() ? base : base + "：" + message;
    }

    private String friendlyError(Exception e) {
        if (e instanceof UpstreamException) {
            return e.getMessage();
        }
        if (e instanceof HttpTimeoutException) {
            return "上游生成超时（120 秒），请稍后重试";
        }
        if (e instanceof IOException) {
            return "图像下载或处理失败：" + e.getMessage();
        }
        return "生成失败：" + e.getMessage();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 上游错误（已翻译为可展示文案），与代码异常区分开，避免被包一层“生成失败：”。 */
    private static class UpstreamException extends RuntimeException {
        UpstreamException(String message) {
            super(message);
        }
    }
}

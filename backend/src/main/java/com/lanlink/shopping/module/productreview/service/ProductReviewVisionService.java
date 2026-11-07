package com.lanlink.shopping.module.productreview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.common.storage.StorageUrls;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.module.pricemonitor.service.AnalysisSafetyFilter;
import com.lanlink.shopping.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 商品主图 AI 复核：主图读取（对象存储 key 直读 / 本地上传 / 前端静态资源 / 公网图片）
 * + 本地 AI 网关 /v1/vision（qwen2.5-vl）。
 * 视觉模型不可用、显存不足、超时或输出非白名单时返回 empty，由编排器转人工，绝不影响商品发布主链路。
 */
@Service
public class ProductReviewVisionService {

    private static final Logger log = LoggerFactory.getLogger(ProductReviewVisionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final String VISION_PROMPT = """
            Do not describe the image. Reply with one line only: VERDICT|RISK|ISSUES
            VERDICT: MATCH or MISMATCH (is this a clear product image matching the title?)
            RISK: LOW, MEDIUM, or HIGH
            ISSUES: NONE or comma-separated short issues such as QR code, contact info, prohibited,
            vulgar, non-product image, text poster, title mismatch.
            Example: MATCH|LOW|NONE
            Product title: """;

    private final boolean gatewayEnabled;
    private final String gatewayBaseUrl;
    private final String gatewayApiKey;
    private final String vlModel;
    private final String frontendBase;
    private final StorageService storageService;
    private final AuditService auditService;

    public ProductReviewVisionService(
            @Value("${AI_GATEWAY_ENABLED:true}") boolean gatewayEnabled,
            @Value("${AI_GATEWAY_BASE_URL:http://lanlink-ai-gateway:8090/v1}") String gatewayBaseUrl,
            @Value("${AI_GATEWAY_API_KEY:lanlink-local-ai-gateway}") String gatewayApiKey,
            @Value("${AI_GATEWAY_VL_MODEL:qwen2.5-vl}") String vlModel,
            @Value("${PRODUCT_IMAGE_FRONTEND_BASE:http://frontend:80}") String frontendBase,
            StorageService storageService,
            AuditService auditService) {
        this.gatewayEnabled = gatewayEnabled;
        this.gatewayBaseUrl = trimSlash(gatewayBaseUrl);
        this.gatewayApiKey = gatewayApiKey;
        this.vlModel = vlModel;
        this.frontendBase = trimSlash(frontendBase);
        this.storageService = storageService;
        this.auditService = auditService;
    }

    public record VisionReview(boolean match, String risk, List<String> issues,
                               String model, String raw) {}

    public Optional<VisionReview> review(Product p) {
        if (!gatewayEnabled || p == null || !StringUtils.hasText(p.getCoverUrl())) return Optional.empty();
        try {
            byte[] bytes = loadImage(p.getCoverUrl().trim());
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) return Optional.empty();
            String mime = mimeOf(bytes);
            String b64 = Base64.getEncoder().encodeToString(bytes);

            ObjectNode body = JSON.createObjectNode();
            body.put("model", StringUtils.hasText(vlModel) ? vlModel : "qwen2.5-vl");
            body.put("prompt", VISION_PROMPT + AnalysisSafetyFilter.sanitize(p.getTitle()));
            body.put("image_base64", b64);
            body.put("mime", mime);
            body.put("temperature", 0);
            body.put("max_tokens", 150);

            HttpRequest req = HttpRequest.newBuilder(URI.create(gatewayBaseUrl + "/vision"))
                    .timeout(Duration.ofSeconds(200))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + gatewayApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[product-review-vision] gateway HTTP {}: {}", resp.statusCode(), abbreviate(resp.body()));
                return Optional.empty();
            }
            JsonNode root = JSON.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) return Optional.empty();
            if (content.contains("显存不足") || content.contains("视觉模型不可用") || content.contains("视觉模型暂不可用")) {
                log.warn("[product-review-vision] vision unavailable: {}", abbreviate(content));
                return Optional.empty();
            }
            if (AnalysisSafetyFilter.containsInjectionEcho(content)) {
                log.warn("[product-review-vision] output dropped: injection echo");
                return Optional.empty();
            }
            Optional<VisionReview> parsed = parseVision(content, vlModel);
            parsed.ifPresent(v -> audit(p.getProdId(), "AI 图像复核完成：match=" + v.match()
                    + ", risk=" + v.risk() + ", issues=" + v.issues().size()));
            return parsed;
        } catch (Exception e) {
            log.warn("[product-review-vision] prod {} failed: {}", p.getProdId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 解析视觉模型输出：兼容严格 JSON 与更稳定的 MATCH|LOW|NONE 单行格式。 */
    public static Optional<VisionReview> parseVision(String content, String model) {
        if (!StringUtils.hasText(content)) return Optional.empty();
        String raw = content.trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = raw.substring(start, end + 1);
            try {
                JsonNode root = JSON.readTree(json);
                if (root.path("match").isBoolean()) {
                    boolean match = root.path("match").asBoolean();
                    String risk = root.path("risk").asText("").trim().toUpperCase();
                    if (RISKS.contains(risk)) {
                        return Optional.of(new VisionReview(match, risk,
                                parseIssueArray(root.path("issues")), model, json));
                    }
                }
            } catch (Exception ignore) {
                // JSON 解析失败继续走紧凑格式解析
            }
        }
        java.util.regex.Matcher compact = java.util.regex.Pattern
                .compile("(MATCH|MISMATCH)\\s*\\|\\s*(LOW|MEDIUM|HIGH)\\s*\\|\\s*([^\\r\\n]*)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        if (compact.find()) {
            boolean match = "MATCH".equalsIgnoreCase(compact.group(1));
            String risk = compact.group(2).toUpperCase();
            List<String> issues = new ArrayList<>();
            for (String s : compact.group(3).split("[,，;；、]")) {
                String t = s.trim();
                if (t.isEmpty() || "NONE".equalsIgnoreCase(t) || "MISCELLANEOUS".equalsIgnoreCase(t)
                        || "无".equals(t) || "-".equals(t)) continue;
                issues.add(t.length() > 120 ? t.substring(0, 120) : t);
            }
            return Optional.of(new VisionReview(match, risk, List.copyOf(issues), model, raw));
        }
        // 兼容模型输出带标签散文：Verdict: MATCH / Risk: HIGH / Issues: 二维码
        java.util.regex.Matcher verdict = java.util.regex.Pattern
                .compile("(?i)(?:verdict|match|结论|是否匹配)[*_\\s]*[:：][*_\\s]*(MATCH|MISMATCH)")
                .matcher(raw);
        java.util.regex.Matcher riskM = java.util.regex.Pattern
                .compile("(?i)(?:risk|风险)[*_\\s]*[:：][*_\\s]*(LOW|MEDIUM|HIGH)")
                .matcher(raw);
        if (verdict.find() && riskM.find()) {
            return Optional.of(new VisionReview(
                    "MATCH".equalsIgnoreCase(verdict.group(1)),
                    riskM.group(1).toUpperCase(), List.of(), model, raw));
        }
        return Optional.empty();
    }

    private static List<String> parseIssueArray(JsonNode arr) {
        List<String> issues = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.isTextual() ? n.asText() : n.path("message").asText("");
                s = s == null ? "" : s.trim();
                if (!s.isEmpty()) issues.add(s.length() > 120 ? s.substring(0, 120) : s);
            }
        }
        return issues;
    }

    private byte[] loadImage(String coverUrl) {
        try {
            // 对象存储回源（out/storage-mq-plan.md 任务 A6）：库存 key（如 1001.webp、
            // products/prod_ts.webp）与本存储域绝对 URL 走 S3 GetObject 直读字节流，
            // 不经 HTTP 域名（绕开 SSRF 白名单）；缺失此步迁移后视觉审核会静默降级。
            // 仅 storage 启用时生效，未启用（回退现状）走原有分支。
            if (storageService.s3Enabled()) {
                String key = StorageUrls.storageKeyOrNull(coverUrl);
                if (key == null && coverUrl != null && coverUrl.startsWith("/products/")) {
                    key = StorageUrls.toKey(coverUrl);
                }
                if (key != null) {
                    try {
                        byte[] bytes = storageService.getObject(key);
                        if (bytes != null && bytes.length > 0) {
                            return bytes;
                        }
                    } catch (Exception e) {
                        log.debug("[product-review-vision] S3 回源失败 key={}, 降级后续分支: {}", key, e.getMessage());
                    }
                }
            }
            if (coverUrl.startsWith("/api/uploads/") || coverUrl.startsWith("/uploads/")) {
                String rel = coverUrl.startsWith("/api/") ? coverUrl.substring(5) : coverUrl;
                File f = new File(System.getProperty("user.dir") + File.separator + rel.replace("/", File.separator));
                if (!f.isFile() || f.length() > MAX_IMAGE_BYTES) return null;
                return Files.readAllBytes(f.toPath());
            }
            String absolute = coverUrl;
            boolean relative = coverUrl.startsWith("/");
            if (relative) {
                absolute = frontendBase + coverUrl;
            }
            URI uri = URI.create(absolute);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
            // 相对路径由平台自己的前端/静态资源域提供，走内网地址；仅对外部绝对 URL 做 SSRF 校验
            if (!relative && !publicHost(uri.getHost())) return null;
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            try (InputStream in = resp.body()) {
                return in.readNBytes((int) MAX_IMAGE_BYTES + 1);
            }
        } catch (Exception e) {
            log.debug("[product-review-vision] image fetch failed {}: {}", coverUrl, e.getMessage());
            return null;
        }
    }

    /** 外部图片 SSRF 守卫：仅公网 http(s)，拒绝回环/内网/链路本地/元数据主机。 */
    private static boolean publicHost(String host) {
        if (!StringUtils.hasText(host)) return false;
        try {
            if ("localhost".equalsIgnoreCase(host) || host.startsWith("127.") || host.startsWith("0.")) return false;
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) return false;
                if ("169.254.169.254".equals(addr.getHostAddress())) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String mimeOf(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image/png";
        if (h.length >= 12 && h[0] == 'R' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "image/webp";
        return "image/jpeg";
    }

    private static String trimSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    private void audit(Long prodId, String detail) {
        try {
            auditService.record(0L, "PRODUCT_AUTO_REVIEW_VISION", "商品 #" + prodId + " " + detail, null);
        } catch (Exception ignore) {
            // 审计旁路，不阻断审核
        }
    }
}

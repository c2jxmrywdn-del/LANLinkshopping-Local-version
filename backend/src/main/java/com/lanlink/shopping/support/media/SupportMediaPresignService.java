package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 浏览器直传 MinIO 的 AWS SigV4 POST Policy 生成。
 *
 * <p>AWS SDK 2.57.8 没有 presigned POST 类（只有 URL 预签名），引入新版 SDK 不在本期范围，
 * 因此按官方算法手写：policy(base64) → 派生签名密钥 → HMAC → 十六进制签名。
 * 与 URL 预签名相比，POST Policy 能在策略里用 content-length-range 限定大小、
 * 用 starts-with 限定 key 前缀与 Content-Type，正好对上「服务端生成 key + 限制类型」的要求。
 *
 * <p>安全边界：
 * <ul>
 *   <li>Origin 必须命中 lanlink.storage.presign-origins 白名单——action 由它拼出，
 *       不校验等于允许任意站点直接向私有桶上传；</li>
 *   <li>只返回政策与签名，AK/SK 永远不出后端；</li>
 *   <li>key 由服务端生成且带随机单号，客户端无法指定任意对象路径。</li>
 * </ul>
 */
@Service
public class SupportMediaPresignService {

    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    /** 浏览器经同源反代访问桶的路径前缀（nginx /support-media → MinIO 私有桶根） */
    public static final String ACTION_PATH = "/support-media";

    private final StorageProperties props;

    public SupportMediaPresignService(StorageProperties props) {
        this.props = props;
    }

    /** 只返回前端直传必需的字段；medaNo/expiresAt 供前端轮询确认 */
    public record PresignResponse(String action, Map<String, String> fields, String mediaNo,
                                  String expiresAt, long maxBytes) {
    }

    /** 服务端生成的对象键：按月分桶 + 访客摘要前缀，便于运维按人/按月定位与清理 */
    public String objectKey(String visitorToken, String mediaNo, String extension, Instant now) {
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return "support/" + date.format(DateTimeFormatter.ofPattern("yyyyMM"))
                + "/" + visitorHash16(visitorToken) + "/" + mediaNo + "." + extension;
    }

    public PresignResponse presign(String origin, String mediaNo, SupportMediaKind kind, String extension,
                                   String contentType, long maxBytes, String visitorToken) {
        return presign(origin, mediaNo, kind, extension, contentType, maxBytes, visitorToken, Instant.now());
    }

    PresignResponse presign(String origin, String mediaNo, SupportMediaKind kind, String extension,
                            String contentType, long maxBytes, String visitorToken, Instant now) {
        requireStorageReady();
        String action = requireAllowedOrigin(origin) + ACTION_PATH;
        String bucket = props.getSupportBucket();
        String key = objectKey(visitorToken, mediaNo, extension, now);
        String prefix = key.substring(0, key.lastIndexOf('/') + 1);

        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        String dateStamp = date.format(DATE_STAMP);
        String amzDate = AMZ_DATE.format(now.atZone(ZoneOffset.UTC));
        long ttl = Math.max(props.getPresignTtlSeconds(), 60L);
        String expiration = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(ttl));

        String mimeCondition = kind.mimeConditionPrefix();
        String credential = props.getAccessKey() + "/" + dateStamp + "/" + props.getRegion() + "/" + SERVICE
                + "/aws4_request";
        String policyJson = buildPolicy(expiration, bucket, prefix, maxBytes, mimeCondition, credential, amzDate);
        String base64Policy = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = signature(base64Policy, dateStamp);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        // 文档族不设 Content-Type 条件，也就不提交该字段，避免策略校验失败
        if (mimeCondition != null) {
            fields.put("Content-Type", contentType);
        }
        fields.put("policy", base64Policy);
        fields.put("x-amz-algorithm", ALGORITHM);
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        fields.put("x-amz-signature", signature);

        return new PresignResponse(action, fields, mediaNo, expiration, maxBytes);
    }

    /**
     * 白名单校验并归一化 Origin：去尾部斜杠、统一小写；空白名单一律拒绝。
     * 返回的字符串直接用于拼 action，不做任何回显之外的用途。
     */
    public String requireAllowedOrigin(String origin) {
        String normalized = normalizeOrigin(origin);
        if (normalized == null) {
            throw new BusinessException(400, "缺少合法的请求来源");
        }
        List<String> allowed = props.getPresignOrigins();
        if (allowed == null || allowed.isEmpty()) {
            throw new BusinessException(400, "未配置允许的上传来源");
        }
        for (String candidate : allowed) {
            if (candidate != null && normalized.equals(normalizeOrigin(candidate))) {
                return normalized;
            }
        }
        throw new BusinessException(400, "请求来源不在允许的上传白名单内");
    }

    /** 归一化失败返回 null（非法 URL / 非 http(s) / 带路径的 Origin 都拒绝） */
    static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        String value = origin.trim();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return null;
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                return null;
            }
            int port = uri.getPort();
            return lowerScheme + "://" + host.toLowerCase(Locale.ROOT) + (port > 0 ? ":" + port : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 策略条件顺序固定（bucket → key 前缀 → 大小 → Content-Type），便于测试断言与排障比对。
     * JSON 全部由固定字符集拼出（桶名/前缀/前缀类型均受控），无需通用序列化器。
     */
    static String buildPolicy(String expiration, String bucket, String keyPrefix, long maxBytes, String mimePrefix) {
        return buildPolicy(expiration, bucket, keyPrefix, maxBytes, mimePrefix,
                "test-credential", "19700101T000000Z");
    }

    /**
     * 策略条件：bucket → key 前缀 → 大小 → Content-Type → x-amz-* 三件套。
     * 最后三项是 MinIO/S3 的硬要求：请求里出现的每个表单字段都必须能在策略条件中找到，
     * 否则 POST 直接 403（AccessDenied: Each form field ... must appear in policy conditions）。
     */
    static String buildPolicy(String expiration, String bucket, String keyPrefix, long maxBytes,
                              String mimePrefix, String credential, String amzDate) {
        String base = "{\"expiration\":\"" + escape(expiration) + "\",\"conditions\":["
                + "{\"bucket\":\"" + escape(bucket) + "\"},"
                + "[\"starts-with\",\"$key\",\"" + escape(keyPrefix) + "\"],"
                + "[\"content-length-range\",1," + maxBytes + "],"
                + "{\"x-amz-algorithm\":\"" + ALGORITHM + "\"},"
                + "{\"x-amz-credential\":\"" + escape(credential) + "\"},"
                + "{\"x-amz-date\":\"" + escape(amzDate) + "\"}";
        if (mimePrefix != null) {
            base += ",[\"starts-with\",\"$Content-Type\",\"" + escape(mimePrefix) + "\"]";
        }
        return base + "]}";
    }

    private String signature(String base64Policy, String dateStamp) {
        byte[] signingKey = hmacSha256(
                hmacSha256(
                        hmacSha256(
                                hmacSha256(("AWS4" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8), dateStamp),
                                props.getRegion()),
                        SERVICE),
                "aws4_request");
        return HexFormat.of().formatHex(hmacSha256(signingKey, base64Policy));
    }

    private void requireStorageReady() {
        if (!props.isEnabled()) {
            throw new BusinessException(503, "对象存储未启用，暂不支持媒体上传");
        }
        if (props.getAccessKey() == null || props.getAccessKey().isBlank()
                || props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            throw new BusinessException(503, "对象存储凭据未配置，暂不支持媒体上传");
        }
        if (props.getSupportBucket() == null || props.getSupportBucket().isBlank()) {
            throw new BusinessException(503, "客服媒体桶未配置");
        }
    }

    static String visitorHash16(String visitorToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((visitorToken == null ? "" : visitorToken).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 策略里的值均来自配置或受控格式，仍做最小转义，避免桶名被误配成带引号的字符串 */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

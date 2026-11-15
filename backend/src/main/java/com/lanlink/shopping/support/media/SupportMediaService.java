package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 客服媒体主流程：预签名 → 确认回源核验 → 状态推进 → 中转读取。
 *
 * <p>核验口径（不信任客户端声明）：
 * <ol>
 *   <li>HEAD 校验对象存在、大小与预签名声明一致、S3 侧 Content-Type 落在类型前缀内；</li>
 *   <li>GET 流式读取：首 16 字节做魔数嗅探，同时全量算 SHA-256 落库（供去重与审计）；</li>
 *   <li>魔数判出的类型/扩展名必须与声明一致，否则拒绝且不推进状态。</li>
 * </ol>
 *
 * <p>状态推进与 outbox 落库由 {@link SupportMediaOutboxPublisher} 在同一事务内完成，
 * 本类只负责「校验通过之后调用一次」。
 */
@Service
public class SupportMediaService {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaService.class);

    private static final Pattern UUID_TOKEN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_CONVERSATION_LENGTH = 64;
    private static final int MAX_MESSAGE_KEY_LENGTH = 128;
    private static final int SNIFF_BYTES = 16;

    private final SupportMediaValidator validator;
    private final SupportMediaRepository repository;
    private final SupportMediaQuotaService quota;
    private final SupportMediaPresignService presignService;
    private final SupportMediaOutboxPublisher outbox;
    private final SupportMediaProperties props;
    private final StorageProperties storageProps;
    private final S3Client s3;

    public SupportMediaService(SupportMediaValidator validator, SupportMediaRepository repository,
                               SupportMediaQuotaService quota, SupportMediaPresignService presignService,
                               SupportMediaOutboxPublisher outbox, SupportMediaProperties props,
                               StorageProperties storageProps, @Nullable S3Client s3) {
        this.validator = validator;
        this.repository = repository;
        this.quota = quota;
        this.presignService = presignService;
        this.outbox = outbox;
        this.props = props;
        this.storageProps = storageProps;
        this.s3 = s3;
    }

    public record PresignCommand(String visitorToken, String conversationNo, String messageKey,
                                 String kind, String fileName, String contentType, Long size) {
    }

    /** 查询视图：只暴露解析结果与状态，不含 objectKey/摘要等内部字段。
     *  partial/partialReason：P2 超限显式化——文档/压缩包仅解析了部分内容时给出原因 */
    public record Detail(String mediaNo, String kind, String status, String fileName, String contentType,
                         long fileSize, Integer width, Integer height, Integer durationMs, Integer pageCount,
                         String engine, String ocrText, String asrText, String summary, String entitiesJson,
                         Boolean partial, String partialReason,
                         BigDecimal confidence, String errorMessage, LocalDateTime expiresAt) {
    }

    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    /**
     * 预签名：先做白名单与类型粗筛，再预占配额，最后落 INIT 行。
     * 落库失败会释放刚预占的配额，避免「上传失败但仍占用额度」。
     */
    public SupportMediaPresignService.PresignResponse presign(PresignCommand cmd, String origin, String clientIp) {
        SupportMediaKind kind = SupportMediaKind.fromWire(cmd.kind());
        SupportMediaValidator.ValidatedRequest validated =
                validator.validateRequest(kind, cmd.fileName(), cmd.contentType(), cmd.size());
        String visitor = requireVisitorToken(cmd.visitorToken());
        String conversation = normalizeOptional(cmd.conversationNo(), MAX_CONVERSATION_LENGTH, "会话标识");
        String messageKey = normalizeOptional(cmd.messageKey(), MAX_MESSAGE_KEY_LENGTH, "消息标识");
        // 白名单先于任何重活：不允许的来源连配额都不该被消耗
        String allowedOrigin = presignService.requireAllowedOrigin(origin);

        String mediaNo = nextMediaNo();
        Instant now = Instant.now();
        String objectKey = presignService.objectKey(visitor, mediaNo, validated.extension(), now);
        long maxBytes = kind.maxBytes(props);
        SupportMediaPresignService.PresignResponse presigned = presignService.presign(
                allowedOrigin, mediaNo, kind, validated.extension(), validated.contentType(), maxBytes, visitor);

        if (!quota.reserve(mediaNo, visitor, clientIp, conversation, messageKey)) {
            throw new BusinessException(429, "媒体上传过于频繁，请稍后再试");
        }
        try {
            repository.insertInit(mediaNo, null, visitor, kind.name(), objectKey, validated.fileName(),
                    validated.contentType(), validated.size(), LocalDateTime.now().plusDays(props.getRetentionDays()));
        } catch (RuntimeException e) {
            quota.releaseByMediaNo(mediaNo);
            throw e;
        }
        return presigned;
    }

    /** 确认上传：HEAD + 流式摘要 + 魔数复核，通过后才推进状态并写 outbox */
    public Detail confirm(String mediaNo, String visitorToken) {
        SupportMediaRepository.Row row = requireOwned(mediaNo, visitorToken);
        if (SupportMediaRepository.STATUS_EXPIRED.equals(row.status())) {
            throw new BusinessException(400, "媒体已失效，请重新上传");
        }
        // 幂等：已确认过的直接回当前状态，避免前端重试把已解析的行打回 INIT
        if (!SupportMediaRepository.STATUS_INIT.equals(row.status())) {
            return toDetail(row);
        }

        try {
            SupportMediaKind kind = SupportMediaKind.fromWire(row.kind());
            S3Client client = requireS3();
            HeadObjectResponse head = headObject(client, row.objectKey());
            long actualSize = head.contentLength() == null ? 0L : head.contentLength();
            if (actualSize <= 0) {
                throw new BusinessException(400, "文件为空");
            }
            if (actualSize > kind.maxBytes(props)) {
                throw new BusinessException(400, "文件超出大小上限");
            }
            if (actualSize != row.fileSize()) {
                throw new BusinessException(400, "文件大小与声明不一致");
            }
            String headType = head.contentType();
            if (headType != null && !headType.isBlank()
                    && !kind.allowsContentType(headType.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(400, "文件类型与声明不一致");
            }

            Digest digest = readHeadAndDigest(client, row.objectKey());
            SupportMediaValidator.Detected detected =
                    validator.validateUploaded(kind, extensionOf(row.fileName()), digest.head());

            outbox.enqueueUploaded(row.id(), mediaNo, actualSize, digest.sha256(), detected.contentType());
            SupportMediaRepository.Row latest = repository.findByMediaNo(mediaNo).orElse(row);
            return toDetail(latest);
        } catch (BusinessException e) {
            repository.updateStatus(row.id(), SupportMediaRepository.STATUS_INIT,
                    SupportMediaRepository.STATUS_FAILED, e.getMessage());
            quota.releaseByMediaNo(mediaNo);
            throw e;
        } catch (Exception e) {
            String msg = "上传确认异常：" + e.getMessage();
            repository.updateStatus(row.id(), SupportMediaRepository.STATUS_INIT,
                    SupportMediaRepository.STATUS_FAILED, msg);
            quota.releaseByMediaNo(mediaNo);
            throw new BusinessException(500, msg);
        }
    }

    public Detail detail(String mediaNo, String visitorToken) {
        return toDetail(requireOwned(mediaNo, visitorToken));
    }

    /** 移除/放弃媒体：若为未确认或失败行则清理记录并即刻释放配额 */
    public void discard(String mediaNo, String visitorToken) {
        if (mediaNo == null || mediaNo.isBlank()) {
            return;
        }
        try {
            SupportMediaRepository.Row row = requireOwned(mediaNo, visitorToken);
            quota.releaseByMediaNo(mediaNo);
            if (SupportMediaRepository.STATUS_INIT.equals(row.status())
                    || SupportMediaRepository.STATUS_FAILED.equals(row.status())) {
                repository.deleteMedia(row.id());
            }
        } catch (BusinessException e) {
            // 已删除或不属于当前访客则忽略
            quota.releaseByMediaNo(mediaNo);
        }
    }

    /** 中转读取：字节始终经后端，浏览器拿不到桶地址 */
    public Content content(String mediaNo, String visitorToken) {
        SupportMediaRepository.Row row = requireOwned(mediaNo, visitorToken);
        if (SupportMediaRepository.STATUS_EXPIRED.equals(row.status())) {
            throw new BusinessException(404, "媒体不存在");
        }
        S3Client client = requireS3();
        try {
            byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(storageProps.getSupportBucket())
                    .key(row.objectKey())
                    .build()).asByteArray();
            return new Content(row.fileName(), row.contentType(), bytes);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(404, "媒体不存在");
            }
            log.error("[support-media] 中转读取失败 mediaNo={} status={}", mediaNo, e.statusCode());
            throw new BusinessException(503, "媒体暂不可用");
        }
    }

    private SupportMediaRepository.Row requireOwned(String mediaNo, String visitorToken) {
        String visitor = requireVisitorToken(visitorToken);
        if (mediaNo == null || mediaNo.isBlank() || mediaNo.length() > 40) {
            throw new BusinessException(404, "媒体不存在");
        }
        // 归属不匹配与不存在统一 404：单号虽随机，也没必要给出「存在但不是你的」这个额外信息
        return repository.findByMediaNoAndVisitor(mediaNo.trim(), visitor)
                .orElseThrow(() -> new BusinessException(404, "媒体不存在"));
    }

    private HeadObjectResponse headObject(S3Client client, String objectKey) {
        try {
            return client.headObject(HeadObjectRequest.builder()
                    .bucket(storageProps.getSupportBucket())
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(400, "文件尚未上传完成");
            }
            log.warn("[support-media] HEAD 校验失败 key={} status={}", objectKey, e.statusCode());
            throw new BusinessException(503, "存储暂不可用，请稍后重试");
        }
    }

    /** 一次流式读取同时拿到头部字节与全量摘要，避免为算哈希再回源一次 */
    private Digest readHeadAndDigest(S3Client client, String objectKey) {
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(GetObjectRequest.builder()
                .bucket(storageProps.getSupportBucket())
                .key(objectKey)
                .build())) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] head = in.readNBytes(SNIFF_BYTES);
            sha.update(head);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                sha.update(buffer, 0, read);
            }
            return new Digest(head, HexFormat.of().formatHex(sha.digest()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(400, "文件尚未上传完成");
            }
            throw new BusinessException(503, "存储暂不可用，请稍后重试");
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("[support-media] 读取对象失败 key={}", objectKey, e);
            throw new BusinessException(503, "读取上传文件失败");
        }
    }

    private record Digest(byte[] head, String sha256) {
    }

    private S3Client requireS3() {
        if (s3 == null || !storageProps.isEnabled()) {
            throw new BusinessException(503, "对象存储未启用，暂不支持媒体上传");
        }
        return s3;
    }

    private static String requireVisitorToken(String visitorToken) {
        if (visitorToken == null || !UUID_TOKEN.matcher(visitorToken.trim()).matches()) {
            throw new BusinessException(400, "访客标识不合法");
        }
        return visitorToken.trim();
    }

    private static String normalizeOptional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException(400, label + "超出长度限制");
        }
        return trimmed;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? SupportMediaValidator.canonicalExtension(fileName.substring(dot + 1)) : "";
    }

    private static String nextMediaNo() {
        return "SM" + NO_STAMP.format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private static Detail toDetail(SupportMediaRepository.Row row) {
        Boolean partial = null;
        String partialReason = null;
        if (row.entitiesJson() != null && !row.entitiesJson().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.entitiesJson());
                if (node.path("partial").asBoolean(false)) {
                    partial = true;
                    partialReason = node.path("partialReason").asText(null);
                }
            } catch (Exception ignored) {
                // entities_json 非 partial 结构（历史行）：按无截断返回
            }
        }
        return new Detail(row.mediaNo(), row.kind(), row.status(), row.fileName(), row.contentType(),
                row.fileSize(), row.width(), row.height(), row.durationMs(), row.pageCount(), row.engine(),
                row.ocrText(), row.asrText(), row.summary(), row.entitiesJson(), partial, partialReason,
                row.confidence(), row.errorMessage(), row.expiresAt());
    }
}

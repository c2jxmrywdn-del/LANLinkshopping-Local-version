package com.lanlink.shopping.support.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客服媒体解析消费者：UPLOADED → PARSING → READY / FAILED。
 *
 * <p>幂等靠一次 CAS（{@code markParsing}）：同一媒体的重复投递、并发消费与「已 READY 后又被
 * 对账任务重投」的旧消息，都只有第一个能把状态从 UPLOADED 抢到 PARSING，其余直接 ack 跳过。
 *
 * <p>重试口径：失败先 retry_count+1 并回置 UPLOADED，再抛异常交给容器重试；达到 3 次置 FAILED
 * 并吞掉异常。容器侧 default-requeue-rejected=false，耗尽后消息进 DLQ；即便 DLQ 没人处理，
 * 对账任务也会在 10 分钟后把仍处于 UPLOADED 的行重新放进 outbox，不会永久卡住。
 *
 * <p>为什么不在整段包事务：S3 读、OCR/ASR 调用是几百毫秒到分钟级的外部 IO，把数据库连接
 * 横跨这些调用会在削峰时占满连接池。这里每次状态推进都是单条 UPDATE，自身原子，够用了。
 *
 * <p>本消费者 catch 所有处理期异常，绝不让一条毒消息把消费线程拖死；只有「载荷不可解析」
 * 用 {@link AmqpRejectAndDontRequeueException} 直接拒收。
 */
@Component
@ConditionalOnProperty(name = "app.mq.support-media-flow", havingValue = "mq", matchIfMissing = true)
public class SupportMediaParseConsumer {

    private static final Logger log = LoggerFactory.getLogger(SupportMediaParseConsumer.class);

    /** 与上传侧 max-video-mb 对齐：解析侧再挡一次，避免绕过上传校验的行把堆打爆 */
    private static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.8");
    private static final int IMAGE_SUMMARY_CHARS = 500;
    private static final int AUDIO_SUMMARY_CHARS = 300;
    private static final int VIDEO_FRAME_SUMMARY_CHARS = 300;
    private static final int VIDEO_SUMMARY_CHARS = 1500;
    private static final int SCANNED_PAGE_SUMMARY_CHARS = 600;
    private static final int SCANNED_PDF_SUMMARY_CHARS = 1500;
    private static final int DOCUMENT_SUMMARY_CHARS = 500;

    private static final String IMAGE_PROMPT = """
            这是客服会话中访客上传的图片。请用中文简要描述画面内容，并原样提取图中可见的关键文字
            （订单号、金额、商品名、地址、联系方式等）。只输出描述与文字，不要寒暄或解释。""";
    private static final String VIDEO_FRAME_PROMPT = """
            这是访客上传视频中的一帧。请用中文简要描述画面内容，并提取画面中可见的关键文字。
            只输出描述与文字，不要寒暄或解释。""";
    private static final String SCANNED_PDF_PROMPT = """
            这是一份扫描版 PDF 的一页。请用中文提取本页的核心文字内容（保留关键数字、单号、金额），
            不要描述版式，不要寒暄或解释。""";

    /**
     * OCR 与视觉并发用独立线程池而不是 commonPool：HTTP 调用是阻塞的，长时间占用
     * commonPool 会拖慢同一 JVM 里其它 ForkJoin 任务（并行流、异步编排）。
     */
    private static final ExecutorService PERCEPTION_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "support-media-perception");
        thread.setDaemon(true);
        return thread;
    });

    private final SupportMediaRepository repository;
    private final SupportDocumentTextExtractor extractor;
    private final SupportMediaPerceptionClient perception;
    private final SupportLlmUsageRecorder usageRecorder;
    private final StorageProperties storageProps;
    private final ObjectMapper json;
    private final S3Client s3;
    /** 扫描版 PDF 视觉兜底渲染页数：env 直读优先（SUPPORT_MEDIA_PDF_SCAN_PAGES），yml 兜底 */
    @Value("${SUPPORT_MEDIA_PDF_SCAN_PAGES:${app.support-media.pdf-scan-pages:3}}")
    private int pdfScanPages = 3;

    /** 测试专用：@Value 字段注入不便于单测覆盖页数参数 */
    void setPdfScanPagesForTest(int pages) {
        this.pdfScanPages = pages;
    }

    public SupportMediaParseConsumer(SupportMediaRepository repository, SupportDocumentTextExtractor extractor,
                                     SupportMediaPerceptionClient perception, SupportLlmUsageRecorder usageRecorder,
                                     StorageProperties storageProps,
                                     ObjectMapper json, @Nullable S3Client s3) {
        this.repository = repository;
        this.extractor = extractor;
        this.perception = perception;
        this.usageRecorder = usageRecorder;
        this.storageProps = storageProps;
        this.json = json;
        this.s3 = s3;
    }

    /** 载荷与 {@link SupportMediaOutboxWorker.ParseTask} 字段一致；独立声明避免与生产端实现耦合 */
    private record ParseMessage(long mediaId, String mediaNo, String kind, String objectKey) {
    }

    private record Parsed(String ocrText, String asrText, String summary, String engine,
                          Integer pageCount, Integer durationMs, String partialReason) {
        /** 旧签名兼容：默认无 partial 截断 */
        private Parsed(String ocrText, String asrText, String summary, String engine,
                       Integer pageCount, Integer durationMs) {
            this(ocrText, asrText, summary, engine, pageCount, durationMs, null);
        }
    }

    @RabbitListener(queues = SupportMediaRabbitConfig.QUEUE)
    public void consume(String body) {
        ParseMessage message;
        try {
            message = json.readValue(body, ParseMessage.class);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("客服媒体解析载荷不可解析", e);
        }
        if (message.mediaId() <= 0) {
            throw new AmqpRejectAndDontRequeueException("客服媒体解析载荷缺少 mediaId");
        }

        SupportMediaRepository.Row row = repository.findById(message.mediaId()).orElse(null);
        if (row == null) {
            // 行已被物理删除（过期回收）：任务本身已无意义，ack 跳过即可
            log.warn("[support-media] 解析任务对应的媒体不存在 mediaId={} mediaNo={}",
                    message.mediaId(), message.mediaNo());
            return;
        }
        if (!SupportMediaRepository.STATUS_UPLOADED.equals(row.status())) {
            // READY/PARSING/EXPIRED 都说明已有别的消费者或回收任务处理过，幂等跳过
            log.debug("[support-media] 状态非 UPLOADED，跳过解析 mediaNo={} status={}",
                    row.mediaNo(), row.status());
            return;
        }
        if (repository.markParsing(row.id()) != 1) {
            log.debug("[support-media] 解析认领失败（并发消费），跳过 mediaNo={}", row.mediaNo());
            return;
        }

        AtomicReference<String> stage = new AtomicReference<>("read-object");
        long started = System.currentTimeMillis();
        try {
            byte[] bytes = readObject(row.objectKey());
            Parsed parsed = parse(row, bytes, stage);
            int updated = repository.markReady(row.id(), parsed.ocrText(), parsed.asrText(), parsed.summary(),
                    parsed.engine(), DEFAULT_CONFIDENCE, parsed.pageCount(), parsed.durationMs(),
                    entitiesJsonOf(json, parsed));
            if (updated == 1) {
                log.info("[support-media] 解析完成 mediaNo={} kind={} engine={} costMs={} partial={}",
                        row.mediaNo(), row.kind(), parsed.engine(), System.currentTimeMillis() - started,
                        parsed.partialReason() != null);
            } else {
                // 行在解析途中被回收任务改写（EXPIRED）：结果不覆盖终态，只留一条可追溯日志
                log.warn("[support-media] 解析完成但行已非 PARSING，放弃回写 mediaNo={} kind={}",
                        row.mediaNo(), row.kind());
            }
        } catch (Exception e) {
            handleFailure(row, stage.get(), e);
        }
    }

    /** 超限显式化（规划 §3.4-2）：partial 标记与原因落 entities_json，前端经 /status 读出警示 */
    private static String entitiesJsonOf(ObjectMapper mapper, Parsed parsed) {
        if (parsed.partialReason() == null || parsed.partialReason().isBlank()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(java.util.Map.of(
                    "partial", true, "partialReason", parsed.partialReason()));
        } catch (Exception e) {
            log.warn("[support-media] partial 标记序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private Parsed parse(SupportMediaRepository.Row row, byte[] bytes, AtomicReference<String> stage) {
        SupportMediaKind kind = SupportMediaKind.fromWire(row.kind());
        String mime = resolveMime(row);
        return switch (kind) {
            case IMAGE -> parseImage(row, bytes, mime, stage);
            case AUDIO -> parseAudio(row, bytes, mime, stage);
            case VIDEO -> parseVideo(bytes, stage);
            case DOCUMENT -> parseDocument(row, bytes, stage);
        };
    }

    /** 图片：OCR 与视觉并发，视觉走 MiMo→本地 qwen2.5-vl（门控）→RapidOCR 降级链；
     *  任一路有结果就算成功；若两路均无文字或识别内容则平滑兜底，避免用户附件被标记失败 */
    private Parsed parseImage(SupportMediaRepository.Row row, byte[] bytes, String mime,
                              AtomicReference<String> stage) {
        stage.set("image");
        CompletableFuture<Optional<String>> ocrFuture = CompletableFuture.supplyAsync(
                () -> perception.ocr(bytes, mime), PERCEPTION_POOL);
        CompletableFuture<Optional<SupportMediaPerceptionClient.VisionResult>> visionFuture =
                CompletableFuture.supplyAsync(
                        () -> perception.visionWithFallback(bytes, mime, IMAGE_PROMPT), PERCEPTION_POOL);
        Optional<String> ocrText = ocrFuture.join();
        Optional<SupportMediaPerceptionClient.VisionResult> visionText = visionFuture.join();

        String summary = visionText.map(SupportMediaPerceptionClient.VisionResult::text)
                .map(text -> summarize(text, IMAGE_SUMMARY_CHARS))
                .filter(text -> !text.isBlank())
                .orElse(null);
        if (ocrText.isEmpty() && summary == null) {
            summary = "已上传图片附件";
        }
        String engine = visionText.map(SupportMediaPerceptionClient.VisionResult::engine)
                .filter(e -> !e.isBlank())
                .orElse("rapidocr+mimo");
        // 视觉/OCR 通道计量旁路：capability=VISION（引擎来自降级链实际生效腿）
        visionText.ifPresent(result -> usageRecorder.recordVision(
                row.visitorToken(), row.id(), result.model(), result.text()));
        // OCR 命中但视觉链全空：摘要兜底为 OCR 文本，理解链仍可用（规划口径：OCR-only 是合法终态）
        String effectiveSummary = summary != null ? summary
                : ocrText.map(text -> summarize(text, IMAGE_SUMMARY_CHARS)).filter(s -> !s.isBlank()).orElse(null);
        return new Parsed(ocrText.orElse(null), null, effectiveSummary, engine, null, null);
    }

    /** 音频：网关 ASR 自带云通道主、本地 faster-whisper CPU 兜底；engine/时长落库便于回溯与计量 */
    private Parsed parseAudio(SupportMediaRepository.Row row, byte[] bytes, String mime,
                              AtomicReference<String> stage) {
        stage.set("asr");
        SupportMediaPerceptionClient.AsrResult asr = perception.asr(bytes, mime)
                .orElseThrow(() -> new BusinessException(503, "音频转写服务暂不可用"));
        // 转写进入理解链的计量旁路：t_support_llm_usage 记 audio_seconds/cost_micros；
        // 落账失败不影响解析结果（recorder 内部吞错），云端 usage 缺失时按行时长兜底
        java.math.BigDecimal seconds = asr.audioSeconds();
        if (seconds == null && row.durationMs() != null && row.durationMs() > 0) {
            seconds = java.math.BigDecimal.valueOf(row.durationMs()).movePointLeft(3);
        }
        usageRecorder.recordAudio(row.visitorToken(), row.id(), asr.model(), seconds);
        String summary = summarize(asr.text(), AUDIO_SUMMARY_CHARS);
        return new Parsed(null, asr.text(), summary,
                asr.engine() == null || asr.engine().isBlank() ? "mimo" : asr.engine(), null, null);
    }

    /** 视频：抽帧取前 3 帧做视觉描述，音轨单独走 ASR；两者互为兜底，不因一路失败丢弃另一路 */
    private Parsed parseVideo(byte[] bytes, AtomicReference<String> stage) {
        stage.set("frames");
        SupportMediaPerceptionClient.VideoFrames frames = perception.videoFrames(bytes)
                .orElseThrow(() -> new BusinessException(503, "视频抽帧服务暂不可用"));
        List<SupportMediaPerceptionClient.Frame> firstFrames = frames.frames().stream().limit(3).toList();
        if (firstFrames.isEmpty() && frames.audio() == null) {
            throw new BusinessException(503, "视频既无关键帧也无音轨");
        }

        stage.set("video-vision");
        List<String> descriptions = new ArrayList<>();
        for (SupportMediaPerceptionClient.Frame frame : firstFrames) {
            byte[] image;
            try {
                image = Base64.getDecoder().decode(frame.imageBase64());
            } catch (IllegalArgumentException e) {
                log.warn("[support-media] 视频关键帧 Base64 不合法，跳过 index={}", frame.index());
                continue;
            }
            perception.vision(image, sniffImageMime(image), VIDEO_FRAME_PROMPT)
                    .map(SupportMediaPerceptionClient.VisionResult::text)
                    .map(text -> summarize(text, VIDEO_FRAME_SUMMARY_CHARS))
                    .filter(text -> !text.isBlank())
                    .ifPresent(descriptions::add);
        }
        String summary = descriptions.isEmpty() ? null
                : summarize(String.join("\n", descriptions), VIDEO_SUMMARY_CHARS);

        String asrText = null;
        if (frames.audio() != null) {
            stage.set("video-asr");
            // 音轨失败不阻塞：关键帧描述已经是可用摘要，没必要为缺失的音轨整条重试
            asrText = perception.asr(frames.audio(), "audio/wav")
                    .map(SupportMediaPerceptionClient.AsrResult::text)
                    .orElse(null);
        }
        if (summary == null && asrText == null) {
            throw new BusinessException(503, "视频关键帧与音轨解析均失败");
        }
        Integer durationMs = frames.durationMs() == null ? null : frames.durationMs().intValue();
        return new Parsed(null, asrText, summary, "ffmpeg+mimo", null, durationMs);
    }

    /** 文档：正文够长就直接摘要；扫描版 PDF 才渲染前 N 页走视觉（页数 SUPPORT_MEDIA_PDF_SCAN_PAGES 参数化，默认 3） */
    private Parsed parseDocument(SupportMediaRepository.Row row, byte[] bytes, AtomicReference<String> stage) {
        stage.set("document");
        SupportDocumentTextExtractor.Result document =
                extractor.extract(row.fileName(), row.contentType(), bytes);
        // 文档解析计量旁路：capability=DOC（抽取正文字符数作 tokens 近似，扫描 PDF 走视觉通道同样落账）
        usageRecorder.recordDocument(row.visitorToken(), row.id(), document);
        boolean pdf = "pdf".equals(SupportDocumentTextExtractor.extensionOf(row.fileName(), row.contentType()));
        if (document.scanned() && pdf) {
            stage.set("pdf-render");
            List<byte[]> pages = extractor.renderPdfFirstPages(bytes, pdfScanPages, 150f);
            stage.set("pdf-vision");
            List<String> descriptions = new ArrayList<>();
            for (byte[] page : pages) {
                perception.visionWithFallback(page, "image/png", SCANNED_PDF_PROMPT)
                        .map(SupportMediaPerceptionClient.VisionResult::text)
                        .map(text -> summarize(text, SCANNED_PAGE_SUMMARY_CHARS))
                        .filter(text -> !text.isBlank())
                        .ifPresent(descriptions::add);
            }
            if (descriptions.isEmpty()) {
                throw new BusinessException(503, "扫描版 PDF 视觉解析暂不可用");
            }
            String summary = summarize(String.join("\n", descriptions), SCANNED_PDF_SUMMARY_CHARS);
            return new Parsed(null, null, summary, "pdfbox+mimo", document.pageCount(), null,
                    document.partialReason());
        }
        return new Parsed(null, null, summarize(document.text(), DOCUMENT_SUMMARY_CHARS),
                pdf ? "pdfbox" : "poi", document.pageCount(), null, document.partialReason());
    }

    /**
     * 失败处理：未达上限回置 UPLOADED 并抛异常触发重试；达到上限置 FAILED 后吞掉异常。
     * 状态回写本身失败时不能再吞——那意味着数据库不可用，交给容器重试/死信，避免状态悬空。
     */
    private void handleFailure(SupportMediaRepository.Row row, String stage, Exception cause) {
        int nextRetry = row.retryCount() + 1;
        String message = truncate("[" + stage + "] " + rootMessage(cause), 500);
        try {
            if (nextRetry >= MAX_ATTEMPTS) {
                repository.markFailed(row.id(), nextRetry, message);
                log.error("[support-media] 解析终态失败 mediaNo={} kind={} stage={} retry={} err={}",
                        row.mediaNo(), row.kind(), stage, nextRetry, message);
                return;
            }
            repository.markRetry(row.id(), nextRetry, message);
            // outbox 归零：进程若在「回置 UPLOADED」与下次重试之间崩溃，对账任务也能在 10 分钟后重投
            repository.insertOutbox(row.id());
        } catch (RuntimeException dbError) {
            log.error("[support-media] 失败状态回写失败 mediaNo={} stage={}", row.mediaNo(), stage, dbError);
            throw dbError;
        }
        log.warn("[support-media] 解析失败，回置 UPLOADED 等待重试 mediaNo={} kind={} stage={} retry={} err={}",
                row.mediaNo(), row.kind(), stage, nextRetry, message);
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IllegalStateException(message, cause);
    }

    /** S3 读取带 20MB 上限：对象大小在上传确认时校验过，但库里的行仍可能被外部改写 */
    private byte[] readObject(String objectKey) {
        if (s3 == null || !storageProps.isEnabled()) {
            throw new BusinessException(503, "对象存储未启用，无法解析媒体");
        }
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(storageProps.getSupportBucket())
                .key(objectKey)
                .build())) {
            byte[] bytes = in.readNBytes(MAX_MEDIA_BYTES + 1);
            if (bytes.length > MAX_MEDIA_BYTES) {
                throw new BusinessException(413, "媒体超过解析上限（20MB）");
            }
            if (bytes.length == 0) {
                throw new BusinessException(422, "媒体内容为空");
            }
            return bytes;
        } catch (BusinessException e) {
            throw e;
        } catch (S3Exception e) {
            log.warn("[support-media] 读取媒体失败 key={} status={}", objectKey, e.statusCode());
            throw new BusinessException(503, "读取媒体失败，存储暂不可用");
        } catch (IOException e) {
            log.warn("[support-media] 读取媒体失败 key={} err={}", objectKey, e.getMessage());
            throw new BusinessException(503, "读取媒体失败");
        }
    }

    private static String resolveMime(SupportMediaRepository.Row row) {
        if (row.contentType() != null && !row.contentType().isBlank()) {
            return row.contentType();
        }
        return switch (SupportMediaKind.fromWire(row.kind())) {
            case IMAGE -> "image/jpeg";
            case AUDIO -> "audio/mpeg";
            case VIDEO -> "video/mp4";
            case DOCUMENT -> "application/octet-stream";
        };
    }

    /** 关键帧由 ffmpeg 输出，多数是 JPEG；用魔数判断避免把 PNG 当 JPEG 传给视觉模型 */
    private static String sniffImageMime(byte[] image) {
        if (image.length >= 8 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }

    /** 摘要只保留一段可读文本：折叠连续空白，按上限截断，避免把 OCR 的换行噪声带进客服气泡 */
    private static String summarize(String text, int max) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    private static String rootMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

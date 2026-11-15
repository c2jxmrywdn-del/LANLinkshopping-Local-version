package com.lanlink.shopping.support.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客服模型用量账本写入器（t_support_llm_usage 的唯一写入路径）。
 *
 * <p>该表此前只有 DDL 没有任何 insert，计量列（audio_seconds/cost_micros）长期空转；
 * 本组件即规划 §3.5-2 的统一入口：感知四条解析链与后续外部解答链都经它落账，
 * 单条 insert 失败绝不反噬解析主干——计量是旁路，丢了记日志而不是把 READY 行打回。
 *
 * <p>单价配置化：AI_ASR_MICROS_PER_SECOND 未配置时成本记 0，且每个进程只告警一次
 * （避免每条语音刷一条 warn）。P2 扩容出 VISION/DOC 两条能力：视觉按次、文档按解析
 * 字符数近似 tokens；两者都没有时长维度，audio_seconds 恒空。
 */
@Component
public class SupportLlmUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SupportLlmUsageRecorder.class);

    private final SupportMediaRepository repository;
    private final TransactionTemplate transactionTemplate;
    /** ASR 单价（微元/秒），未配置记 0；pricing 缺失不应让账本停在零行 */
    @Value("${AI_ASR_MICROS_PER_SECOND:0}")
    private BigDecimal asrMicrosPerSecond;
    /** 视觉单价（微元/次），未配置记 0 */
    @Value("${AI_VISION_MICROS_PER_CALL:0}")
    private BigDecimal visionMicrosPerCall;
    /** 文档解析单价（微元/千字符），未配置记 0 */
    @Value("${AI_DOC_MICROS_PER_KCHAR:0}")
    private BigDecimal docMicrosPerKchar;
    private final AtomicBoolean priceWarned = new AtomicBoolean(false);

    public SupportLlmUsageRecorder(SupportMediaRepository repository,
                                   TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    /** 测试专用：@Value 字段注入不便于单测覆盖单价矩阵 */
    void setAsrMicrosPerSecondsForTest(BigDecimal microsPerSecond) {
        this.asrMicrosPerSecond = microsPerSecond;
    }

    void setVisionMicrosPerCallForTest(BigDecimal microsPerCall) {
        this.visionMicrosPerCall = microsPerCall;
    }

    void setDocMicrosPerKcharForTest(BigDecimal microsPerKchar) {
        this.docMicrosPerKchar = microsPerKchar;
    }

    /**
     * 记一条音频转写用量。
     *
     * @param visitorToken 访客标识（可为空：解析链行若缺 token 就匿名记账）
     * @param mediaId      媒体行主键，账本到媒体的关联键
     * @param model        实际生效的模型（云端模型名或 faster-whisper 型号）
     * @param audioSeconds 音频时长（秒），来自上游 usage.seconds 或本地 probe 时长
     */
    public void recordAudio(String visitorToken, long mediaId, String model, BigDecimal audioSeconds) {
        if (audioSeconds == null || audioSeconds.signum() <= 0) {
            return;
        }
        if (asrMicrosPerSecond == null || asrMicrosPerSecond.signum() <= 0) {
            if (priceWarned.compareAndSet(false, true)) {
                log.warn("[support-usage] AI_ASR_MICROS_PER_SECOND 未配置，audio_seconds 正常落账、成本记 0");
            }
        }
        long costMicros = asrMicrosPerSecond == null ? 0L
                : audioSeconds.multiply(asrMicrosPerSecond).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        insertQuietly(visitorToken, "AUDIO", model, audioSeconds, costMicros, mediaId);
    }

    /**
     * 记一条图片理解用量（capability=VISION）：visionWithFallback 降级链实际生效的
     * 一次成功识别记一次账（mimo 云视觉 / local-vl / rapidocr 兜底都算真实成本或资源占用）。
     *
     * @param recognizedText 识别结果文本；空结果不入账（失败重试由解析链负责，不重复计量）
     */
    public void recordVision(String visitorToken, long mediaId, String model, String recognizedText) {
        if (recognizedText == null || recognizedText.isBlank()) {
            return;
        }
        long costMicros = visionMicrosPerCall == null ? 0L
                : visionMicrosPerCall.setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        insertQuietly(visitorToken, "VISION", model, null, costMicros, mediaId);
    }

    /**
     * 记一条文档解析用量（capability=DOC）：按抽取正文字符数近似 tokens 计量，
     * 扫描 PDF 走视觉腿时同样落账（engine 在媒体行上回溯）。
     */
    public void recordDocument(String visitorToken, long mediaId,
                               SupportDocumentTextExtractor.Result document) {
        int chars = document == null || document.text() == null ? 0 : document.text().length();
        if (chars <= 0) {
            return;
        }
        BigDecimal kchars = BigDecimal.valueOf(chars).divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
        long costMicros = docMicrosPerKchar == null ? 0L
                : kchars.multiply(docMicrosPerKchar).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        insertQuietly(visitorToken, "DOC", "pdfbox+poi", BigDecimal.valueOf(chars), costMicros, mediaId);
    }

    /** 计量旁路：insert 失败只记日志，绝不把异常抛回解析链 */
    private void insertQuietly(String visitorToken, String capability, String model,
                               BigDecimal measure, long costMicros, long mediaId) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    repository.insertLlmUsage(visitorToken, capability, model, measure, costMicros, mediaId));
        } catch (Exception e) {
            log.warn("[support-usage] 用量落账失败 mediaId={} capability={} err={}",
                    mediaId, capability, e.getMessage());
        }
    }
}

package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 客服可接收的媒体类型。
 *
 * <p>扩展名是「请求侧」的粗筛：真正的判定在 {@link SupportMediaValidator} 的魔数嗅探，
 * 这里只把允许集与 MIME 前缀集中一处，避免校验器、预签名策略、消费端各写一份而漂移。
 */
public enum SupportMediaKind {

    IMAGE("image/", Set.of("jpg", "jpeg", "png", "webp", "gif")),
    // webm 既可能是 audio/webm（MediaRecorder 录音）也可能是 video/webm，按扩展名 + MIME 双重判定
    AUDIO("audio/", Set.of("mp3", "wav", "m4a", "ogg", "webm")),
    VIDEO("video/", Set.of("mp4", "mov", "webm")),
    // 文档族的 MIME 覆盖 application/* 与 text/*，无法用单一前缀约束，故策略里不设 Content-Type 条件，
    // 由确认阶段的魔数（PDF/ZIP/OLE2/纯文本）兜底。
    DOCUMENT(null, Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md", "zip"));

    private final String mimePrefix;
    private final Set<String> allowedExtensions;

    SupportMediaKind(String mimePrefix, Set<String> allowedExtensions) {
        this.mimePrefix = mimePrefix;
        this.allowedExtensions = allowedExtensions;
    }

    public String mimePrefix() {
        return mimePrefix;
    }

    /**
     * 预签名策略里的 Content-Type 条件前缀：文档族返回 null（不设条件），
     * 图片/音频/视频返回各自前缀。
     */
    public String mimeConditionPrefix() {
        return this == DOCUMENT ? null : mimePrefix;
    }

    /** 预签名粗筛：文档接受 application/* 与 text/*，其余按前缀匹配 */
    public boolean allowsContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return this == DOCUMENT;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (this == DOCUMENT) {
            // binary/octet-stream 是 MinIO 在未提交 Content-Type 字段时的默认值：
            // 文档族预签名不设 Content-Type 条件，必须在确认阶段放行它，否则所有文档都会被误杀。
            return type.startsWith("application/") || type.startsWith("text/") || type.startsWith("binary/");
        }
        return mimePrefix != null && type.startsWith(mimePrefix);
    }

    public Set<String> allowedExtensions() {
        return allowedExtensions;
    }

    /** 单文件上限：按类型从配置取，而不是在枚举里写死 MB 数 */
    public long maxBytes(SupportMediaProperties props) {
        int mb = switch (this) {
            case IMAGE -> props.getMaxImageMb();
            case AUDIO -> props.getMaxAudioMb();
            case VIDEO -> props.getMaxVideoMb();
            case DOCUMENT -> props.getMaxDocMb();
        };
        return Math.max(mb, 1) * 1024L * 1024L;
    }

    /** 线上值历史上有过 kind 大小写混用（image/IMAGE），统一小写解析 */
    public static SupportMediaKind fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            throw new BusinessException(400, "缺少媒体类型");
        }
        try {
            return SupportMediaKind.valueOf(wire.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "不支持的媒体类型");
        }
    }
}

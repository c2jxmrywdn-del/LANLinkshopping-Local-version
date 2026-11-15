package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 客服媒体准入校验：扩展名白名单 + 单文件上限 + 空文件拒绝 + 魔数嗅探。
 *
 * <p>"不信任 Content-Type 与扩展名"的落点：
 * <ul>
 *   <li>预签名阶段没有字节，只能做扩展名/声明类型/大小的粗筛；Content-Type 必须落在
 *       该类型的前缀内（否则直传策略里的 starts-with 条件形同虚设）；</li>
 *   <li>确认阶段以 S3 回源的首 16 字节魔数为准，魔数判出的类型与扩展名都必须与声明一致，
 *       浏览器声明成 image/png 而内容是 HTML 的请求会在这里被拒。</li>
 * </ul>
 */
@Component
public class SupportMediaValidator {

    private static final int MAX_FILE_NAME_CODE_POINTS = 200;
    /** 声明的 Content-Type 只接受规范 token/token 形态，挡掉注入换行之类的内容 */
    private static final Set<String> FORBIDDEN_IN_HEADER = Set.of("\r", "\n", "\"", ";", " ");

    private final SupportMediaProperties props;

    public SupportMediaValidator(SupportMediaProperties props) {
        this.props = props;
    }

    /** 预签名阶段的粗筛结果 */
    public record ValidatedRequest(SupportMediaKind kind, String extension, String contentType,
                                   long size, String fileName) {
    }

    /** 魔数嗅探结果（扩展名统一为规范写法，如 jpeg→jpg） */
    public record Detected(SupportMediaKind kind, String extension, String contentType) {
    }

    /**
     * 预签名粗筛：不读字节，因此只拒绝「明显不可能」的请求，真正的类型判定留到确认阶段。
     */
    public ValidatedRequest validateRequest(SupportMediaKind kind, String fileName, String contentType, Long size) {
        if (kind == null) {
            throw new BusinessException(400, "缺少媒体类型");
        }
        if (size == null || size <= 0) {
            throw new BusinessException(400, "文件为空");
        }
        long maxBytes = kind.maxBytes(props);
        if (size > maxBytes) {
            throw new BusinessException(400, "文件超出大小上限（" + (maxBytes / 1024 / 1024) + "MB）");
        }
        String extension = canonicalExtension(extensionOf(fileName));
        if (extension.isEmpty() || !kind.allowedExtensions().contains(extension)) {
            throw new BusinessException(400, "不支持的文件扩展名");
        }
        String normalizedType = normalizeContentType(contentType);
        if (!kind.allowsContentType(normalizedType)) {
            throw new BusinessException(400, "文件类型与内容不匹配");
        }
        return new ValidatedRequest(kind, extension, normalizedType, size, sanitizeFileName(fileName, extension));
    }

    /** 确认阶段：魔数必须落在声明类型内，且规范扩展名与预签名时一致 */
    public Detected validateUploaded(SupportMediaKind declared, String declaredExtension, byte[] head) {
        String ext = canonicalExtension(declaredExtension);
        Detected detected;
        try {
            detected = sniff(head);
        } catch (BusinessException e) {
            // 纯文本没有魔数：无 NUL 且不像 HTML 时按文本放行，扩展名仍受白名单约束
            if (declared == SupportMediaKind.DOCUMENT && isPlainText(ext) && looksLikeText(head)) {
                return new Detected(SupportMediaKind.DOCUMENT, ext, "text/plain");
            }
            throw e;
        }
        if (detected.kind() != declared) {
            // WebM 既作为视频容器也作为 MediaRecorder 的音频录制容器（audio/webm）：
            // 当声明为 AUDIO 且魔数确认为 webm 容器时，允许按音频放行。
            if (declared == SupportMediaKind.AUDIO && "webm".equals(detected.extension())) {
                detected = new Detected(SupportMediaKind.AUDIO, "webm", "audio/webm");
            } else if (declared == SupportMediaKind.AUDIO && ("m4a".equals(detected.extension()) || "mp4".equals(detected.extension()))) {
                detected = new Detected(SupportMediaKind.AUDIO, "m4a", "audio/mp4");
            } else {
                throw new BusinessException(400, "文件内容与声明的类型不一致");
            }
        }
        // OOXML（docx/xlsx/pptx）与 zip 同魔数；OLE2（doc/xls/ppt）单独识别。
        // 这两类不能按「魔数扩展名 == 声明扩展名」硬比，否则 office 文档会被全部误杀。
        if (declared == SupportMediaKind.DOCUMENT) {
            if (isOoxml(ext) && "zip".equals(detected.extension())) {
                return new Detected(SupportMediaKind.DOCUMENT, ext, ooxmlContentType(ext));
            }
            if (isLegacyOffice(ext) && "ole2".equals(detected.extension())) {
                return new Detected(SupportMediaKind.DOCUMENT, ext, "application/octet-stream");
            }
        }
        if (!ext.isBlank() && !ext.equals(detected.extension())) {
            throw new BusinessException(400, "文件内容与扩展名不一致");
        }
        return detected;
    }

    private static boolean isOoxml(String ext) {
        return Set.of("docx", "xlsx", "pptx").contains(ext);
    }

    private static boolean isLegacyOffice(String ext) {
        return Set.of("doc", "xls", "ppt").contains(ext);
    }

    private static boolean isPlainText(String ext) {
        return Set.of("txt", "csv", "md").contains(ext);
    }

    /** 纯文本启发式：前 64 字节无 NUL，且不是 HTML（避免把可执行/钓鱼页面当文档存下来） */
    private static boolean looksLikeText(byte[] head) {
        if (head == null || head.length == 0) {
            return false;
        }
        int limit = Math.min(head.length, 64);
        for (int i = 0; i < limit; i++) {
            if (head[i] == 0) {
                return false;
            }
        }
        String prefix = new String(head, 0, limit, StandardCharsets.ISO_8859_1)
                .trim().toLowerCase(Locale.ROOT);
        return !prefix.startsWith("<!doctype") && !prefix.startsWith("<html");
    }

    private static String ooxmlContentType(String ext) {
        return switch (ext) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }

    /**
     * 魔数嗅探。只认「文件头足够定位」的格式；无法识别一律拒绝——
     * 客服媒体的下游是 OCR/ASR，把未知字节喂进去只会换来更隐蔽的失败。
     */
    public Detected sniff(byte[] head) {
        if (head == null || head.length < 4) {
            throw new BusinessException(400, "无法识别的文件内容");
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return new Detected(SupportMediaKind.IMAGE, "jpg", "image/jpeg");
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Detected(SupportMediaKind.IMAGE, "png", "image/png");
        }
        if (startsWithAscii(head, 0, "GIF87a") || startsWithAscii(head, 0, "GIF89a")) {
            return new Detected(SupportMediaKind.IMAGE, "gif", "image/gif");
        }
        if (startsWithAscii(head, 0, "RIFF") && startsWithAscii(head, 8, "WEBP")) {
            return new Detected(SupportMediaKind.IMAGE, "webp", "image/webp");
        }
        if (startsWithAscii(head, 0, "RIFF") && startsWithAscii(head, 8, "WAVE")) {
            return new Detected(SupportMediaKind.AUDIO, "wav", "audio/wav");
        }
        if (startsWithAscii(head, 0, "%PDF")) {
            return new Detected(SupportMediaKind.DOCUMENT, "pdf", "application/pdf");
        }
        if (startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            return new Detected(SupportMediaKind.DOCUMENT, "ole2", "application/x-ole-storage");
        }
        if (startsWithAscii(head, 0, "PK\u0003\u0004")
                || startsWithAscii(head, 0, "PK\u0005\u0006")
                || startsWithAscii(head, 0, "PK\u0007\u0008")) {
            return new Detected(SupportMediaKind.DOCUMENT, "zip", "application/zip");
        }
        if (startsWithAscii(head, 4, "ftyp")) {
            String brand = asciiAt(head, 8, 4);
            if ("qt  ".equals(brand)) {
                return new Detected(SupportMediaKind.VIDEO, "mov", "video/quicktime");
            }
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) {
                return new Detected(SupportMediaKind.AUDIO, "m4a", "audio/mp4");
            }
            return new Detected(SupportMediaKind.VIDEO, "mp4", "video/mp4");
        }
        if (startsWithAscii(head, 0, "OggS")) {
            return new Detected(SupportMediaKind.AUDIO, "ogg", "audio/ogg");
        }
        if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) {
            return new Detected(SupportMediaKind.VIDEO, "webm", "video/webm");
        }
        if (startsWithAscii(head, 0, "ID3")) {
            return new Detected(SupportMediaKind.AUDIO, "mp3", "audio/mpeg");
        }
        // MPEG 音频帧同步字：11 位全 1
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            return new Detected(SupportMediaKind.AUDIO, "mp3", "audio/mpeg");
        }
        throw new BusinessException(400, "无法识别的文件内容");
    }

    /** 扩展名规范化：jpeg 统一为 jpg，其余小写；无扩展名返回空串 */
    public static String canonicalExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String ext = extension.trim().toLowerCase(Locale.ROOT);
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    /** 只取最后一段扩展名；文件名本身允许含点 */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }

    /**
     * 展示名只用于回显与下载：剥路径段、限长（按码点，避免切断代理对），
     * 与附件模块的清洗口径一致。
     */
    static String sanitizeFileName(String fileName, String extension) {
        String name = fileName == null ? "" : fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1).trim();
        }
        if (name.isEmpty()) {
            return "media." + extension;
        }
        if (name.codePointCount(0, name.length()) <= MAX_FILE_NAME_CODE_POINTS) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, MAX_FILE_NAME_CODE_POINTS));
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(400, "缺少 Content-Type");
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 80) {
            throw new BusinessException(400, "Content-Type 不合法");
        }
        for (String bad : FORBIDDEN_IN_HEADER) {
            if (value.contains(bad)) {
                throw new BusinessException(400, "Content-Type 不合法");
            }
        }
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            throw new BusinessException(400, "Content-Type 不合法");
        }
        return value;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] data, int offset, String ascii) {
        byte[] expected = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static String asciiAt(byte[] data, int offset, int length) {
        if (offset < 0 || data.length < offset + length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }
}

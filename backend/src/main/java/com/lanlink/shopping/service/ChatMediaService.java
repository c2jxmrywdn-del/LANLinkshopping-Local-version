package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天媒体上传服务（买家/商户双端共用）：kind 白名单 + 魔数嗅探 + 服务器生成文件名落盘。
 *
 * 安全口径（与 MerchantController 上传一致）：
 *  - 丢弃原始文件名，绝不把用户文件名拼进 URL/路径（防路径穿越与非法扩展名）；
 *    落盘名固定 {userId}_{毫秒时间戳}.{嗅探出的扩展名}，目录按月 {yyyyMM} 分桶；
 *  - 魔数嗅探不出的类型一律拒绝，防伪造扩展名绕过白名单；
 *  - 原始文件名仅在响应 JSON 中回显（存库 media_name，≤200 字符），不参与任何路径拼接。
 * 返回 { url, name, size }：url 形态 /api/uploads/chat/{yyyyMM}/{userId}_{ts}.{ext}。
 */
@Service
public class ChatMediaService {

    /** 聊天媒体上传目录：{user.dir}/uploads/chat/{yyyyMM}（WebConfig 已映射 /uploads/** → /api/uploads/**） */
    private static final String CHAT_DIR = System.getProperty("user.dir")
            + File.separator + "uploads" + File.separator + "chat";
    /** 图片上限 8MB：jpg/png/webp/gif */
    private static final long MAX_IMAGE = 8L * 1024 * 1024;
    /** 文件上限 20MB：pdf/docx/xlsx/pptx/zip/txt/csv（txt/csv 另限 2MB） */
    private static final long MAX_FILE = 20L * 1024 * 1024;
    /** 纯文本上限 2MB：txt/csv 无魔数可校验，直接放行但收紧体积 */
    private static final long MAX_TEXT = 2L * 1024 * 1024;
    /** 语音上限 10MB：webm/wav/mp3/m4a/ogg */
    private static final long MAX_VOICE = 10L * 1024 * 1024;
    /** media_name 列宽（t_merchant_chat_msg.media_name varchar(200)） */
    private static final int MAX_NAME = 200;

    /**
     * 上传聊天媒体（仅需登录，与聊天收发端点一致；商户侧沿用类级 merchant:manage 权限）。
     * 校验顺序：登录 → 非空 → kind 白名单 → 魔数 → 体积 → 落盘。
     */
    public Map<String, Object> upload(Long userId, MultipartFile file, String kind) throws IOException {
        if (userId == null) throw new BusinessException("请先登录");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择文件");
        String k = kind == null ? "" : kind.trim();
        if (!"image".equals(k) && !"file".equals(k) && !"voice".equals(k)) {
            throw new IllegalArgumentException("不支持的上传类型");
        }
        String detected = sniffMagic(file);
        long max;
        String ext;
        switch (k) {
            case "image" -> { ext = imageExt(detected); max = MAX_IMAGE; }
            case "voice" -> { ext = voiceExt(detected); max = MAX_VOICE; }
            default -> {
                ext = fileExt(detected, originalName(file));
                // txt/csv 无魔数可校验，直接放行但收紧体积（≤2MB）
                max = ("txt".equals(ext) || "csv".equals(ext)) ? MAX_TEXT : MAX_FILE;
            }
        }
        if (file.getSize() > max) {
            throw new IllegalArgumentException("文件大小不能超过 " + (max / 1024 / 1024) + "MB");
        }

        // 落盘：uploads/chat/{yyyyMM}/{userId}_{ts}.{ext}（mkdirs 兜底建目录，Dockerfile 预建目录为第一道保险）
        String month = new SimpleDateFormat("yyyyMM").format(new Date());
        File dir = new File(CHAT_DIR, month);
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("上传目录创建失败");
        String name = userId + "_" + System.currentTimeMillis() + "." + ext;
        File dest = new File(dir, name);
        file.transferTo(dest.getAbsoluteFile());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", "/api/uploads/chat/" + month + "/" + name);
        out.put("name", originalName(file));
        out.put("size", file.getSize());
        return out;
    }

    // ==================== kind 白名单 → 落盘扩展名 ====================

    /** image：jpg/png/webp/gif */
    private static String imageExt(String detected) {
        return switch (detected) {
            case "jpg", "png", "webp", "gif" -> detected;
            default -> throw new IllegalArgumentException("图片仅支持 JPG/PNG/WEBP/GIF 格式");
        };
    }

    /**
     * file：pdf/docx/xlsx/pptx/zip/txt/csv。docx/xlsx/pptx/zip 同为 zip 容器（PK 头），
     * 魔数无法细分，统一落为 bin；txt/csv 无魔数（sniffMagic 返回 unknown），
     * 按原始扩展名放行且仅限 .txt/.csv（另限 2MB，见 upload 内 max 选择）。
     */
    private static String fileExt(String detected, String originalName) {
        if ("pdf".equals(detected) || "bin".equals(detected)) return detected;
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".csv")) return "csv";
        throw new IllegalArgumentException("文件仅支持 PDF/DOC/XLS/PPT/ZIP/TXT/CSV 格式");
    }

    /** voice：webm/wav/mp3/m4a/ogg */
    private static String voiceExt(String detected) {
        return switch (detected) {
            case "webm", "wav", "mp3", "m4a", "ogg" -> detected;
            default -> throw new IllegalArgumentException("语音仅支持 WEBM/WAV/MP3/M4A/OGG 格式");
        };
    }

    // ==================== 魔数嗅探 ====================

    /** 读取文件头魔数识别真实类型；识别不出返回 unknown（由各 kind 白名单拒绝） */
    private static String sniffMagic(MultipartFile file) throws IOException {
        byte[] h;
        try (InputStream in = file.getInputStream()) {
            h = in.readNBytes(12);
        }
        int n = h.length;
        // JPEG：FF D8 FF
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "jpg";
        // PNG：89 50 4E 47
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) return "png";
        // PDF：%PDF
        if (n >= 4 && h[0] == 0x25 && h[1] == 0x50 && h[2] == 0x44 && h[3] == 0x46) return "pdf";
        // GIF：GIF87a/GIF89a
        if (n >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return "gif";
        // RIFF 容器：WEBP / WAVE
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            if (h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
            if (h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return "wav";
        }
        // EBML 容器（webm/ogg 系浏览器录音常见封装）：1A 45 DF A3
        if (n >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) return "webm";
        // Ogg 容器：OggS
        if (n >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        // MP3：ID3 标签 或 MPEG 帧同步 FF FB/FF F3/FF F2
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
        if (n >= 2 && (h[0] & 0xFF) == 0xFF
                && ((h[1] & 0xFF) == 0xFB || (h[1] & 0xFF) == 0xF3 || (h[1] & 0xFF) == 0xF2)) return "mp3";
        // M4A：....ftypM4A（ISO BMFF，brand 从第 8 字节起为 M4A）
        if (n >= 11 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p'
                && h[8] == 'M' && h[9] == '4' && h[10] == 'A') return "m4a";
        // zip 族（PK\x03\x04）：docx/xlsx/pptx/zip 统一归 bin
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && (h[2] & 0xFF) == 0x03 && (h[3] & 0xFF) == 0x04) return "bin";
        return "unknown";
    }

    /** 原始文件名仅用于回显与落库（≤200 字符）：去掉路径部分，绝不参与落盘路径拼接 */
    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "未命名文件";
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }
}

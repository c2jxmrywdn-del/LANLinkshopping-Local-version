package com.lanlink.shopping.support;

import com.lanlink.shopping.service.ChatMediaService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 退款证据单测脚手架：在真实上传根（ChatMediaService.chatUploadRoot()）落一个最小证据文件，
 * 供 validator 的 File.exists 归属校验命中；URL 形态与 ChatMediaService 落盘名一致
 * （首段 userId / 末段扩展名是校验硬约束，中段可加 UUID 防同毫秒碰撞）。
 * 用例须在 finally / @AfterEach 调 {@link #delete(String)} 回收，避免污染工作区。
 */
public final class RefundEvidenceTestFiles {

    private RefundEvidenceTestFiles() {}

    /** 落盘一个真实证据文件并返回含该 URL 的合法证据 JSON（单元素 image） */
    public static String writeImageEvidence(long userId) throws IOException {
        return writeEvidenceJson(userId, "jpg",
                "[{\"type\":\"image\",\"url\":\"__URL__\",\"name\":\"聊天记录截图.png\"}]");
    }

    /** 落盘一个真实证据文件并返回其 /api/uploads 相对 URL（用于组装多元素 JSON） */
    public static String writeEvidenceUrl(long userId, String ext) throws IOException {
        String month = new SimpleDateFormat("yyyyMM").format(new Date());
        File dir = new File(ChatMediaService.chatUploadRoot(), "chat" + File.separator + month);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("创建测试证据目录失败: " + dir);
        String name = userId + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID() + "." + ext;
        File f = new File(dir, name);
        Files.write(f.toPath(), "evidence".getBytes(StandardCharsets.UTF_8));
        return "/api/uploads/chat/" + month + "/" + name;
    }

    private static String writeEvidenceJson(long userId, String ext, String template) throws IOException {
        return template.replace("__URL__", writeEvidenceUrl(userId, ext));
    }

    /** 按 URL 删除测试证据文件（含父目录尝试清理，目录非空则忽略） */
    public static void delete(String url) {
        if (url == null) return;
        String rel = url.startsWith("/api/uploads/") ? url.substring("/api/uploads/".length()) : null;
        if (rel == null) return;
        File f = new File(ChatMediaService.chatUploadRoot(), rel);
        if (f.delete()) {
            File parent = f.getParentFile();
            if (parent != null) parent.delete(); // 仅空目录会删成功
        }
    }

    /** 从证据 JSON（单元素或多元素）提取全部 URL 并逐个回收 */
    public static void deleteByUrlJson(String evidenceJson) {
        if (evidenceJson == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"url\":\"([^\"]+)\"").matcher(evidenceJson);
        while (m.find()) {
            delete(m.group(1));
        }
    }
}

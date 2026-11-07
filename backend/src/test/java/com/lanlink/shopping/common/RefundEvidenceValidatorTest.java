package com.lanlink.shopping.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退款证据权威校验单测（out/refund-map-design.md §1.4 规则全集）：
 * 空值/超量/非法 type/URL 前缀/穿越/type↔扩展名一致/归属当前用户/存在性/超长 name/归一化输出。
 */
class RefundEvidenceValidatorTest {

    private static final long UID = 7L;

    @TempDir
    Path tempRoot;

    /** 在临时上传根落盘真实文件并返回其 URL（形态与 ChatMediaService 落盘名一致） */
    private String urlOf(String month, String fileName, String ext) throws IOException {
        Path dir = tempRoot.resolve("chat").resolve(month);
        Files.createDirectories(dir);
        Path f = dir.resolve(fileName);
        Files.write(f, ("evidence." + ext).getBytes(StandardCharsets.UTF_8));
        return "/api/uploads/chat/" + month + "/" + fileName;
    }

    private String item(String type, String url, String name) {
        return "[{\"type\":\"" + type + "\",\"url\":\"" + url + "\",\"name\":\"" + name + "\"}]";
    }

    @Test
    void rejectsMissingEvidence() {
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(null, UID, tempRoot.toFile()));
        assertTrue(ex1.getMessage().contains("请至少上传"));

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize("   ", UID, tempRoot.toFile()));
        assertTrue(ex2.getMessage().contains("请至少上传"));

        BusinessException ex3 = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize("[]", UID, tempRoot.toFile()));
        assertTrue(ex3.getMessage().contains("请至少上传"));
    }

    @Test
    void rejectsMalformedJsonAndNonArray() {
        assertThrows(BusinessException.class, () -> RefundEvidenceValidator.normalize("not-json", UID, tempRoot.toFile()));
        assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize("{\"type\":\"image\"}", UID, tempRoot.toFile()));
    }

    @Test
    void rejectsOverMaxCount() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ValidationRules.REFUND_EVIDENCE_MAX_COUNT + 1; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"image\",\"url\":\"/api/uploads/chat/202611/")
              .append(UID).append("_17300000000").append(i).append(".jpg\",\"name\":\"a.png\"}");
        }
        sb.append(']');
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(sb.toString(), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("最多上传 9"));
    }

    @Test
    void rejectsIllegalType() throws IOException {
        String url = urlOf("202611", UID + "_1730000000.jpg", "jpg");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("audio", url, "a.mp3"), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("类型不支持"));
    }

    @Test
    void rejectsNonUploadPrefix() throws IOException {
        String url = urlOf("202611", UID + "_1730000000.jpg", "jpg");
        String external = item("image", "https://evil.example.com/x.jpg", "外链图");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(external, UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("本平台上传"));

        // 商品图域 URL：形态合法（owner/ext 通过）但 chat 落盘不存在 → 存在性校验拒绝（非 chat 域文件不该进退款凭证）
        String otherUpload = item("image", "/api/uploads/products/" + UID + "_1730000000.webp", "商品图");
        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(otherUpload, UID, tempRoot.toFile()));
        assertTrue(ex2.getMessage().contains("不存在"));
        assertNotNull(url);
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        String url = urlOf("202611", UID + "_1730000000.jpg", "jpg");
        String traversal = item("image", "/api/uploads/chat/202611/../../" + UID + "_1730000000.jpg", "x.png");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(traversal, UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("不合法"));
        assertNotNull(url);
    }

    @Test
    void rejectsTypeExtensionMismatch() throws IOException {
        // PDF 文件伪装成 image 证据 → type↔落盘扩展名不一致
        String url = urlOf("202611", UID + "_1730000000.pdf", "pdf");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, "假图片.pdf"), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("不一致"));
    }

    @Test
    void rejectsEvidenceOwnedByOtherUser() throws IOException {
        String url = urlOf("202611", "999_1730000000.jpg", "jpg");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, "他人截图.png"), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("不属于当前用户"));
    }

    @Test
    void rejectsMalformedOwnerSegment() throws IOException {
        String url = urlOf("202611", "abc_1730000000.jpg", "jpg");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, "坏名.png"), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("不合法"));
    }

    @Test
    void rejectsMissingFile() throws IOException {
        String url = "/api/uploads/chat/202611/" + UID + "_1730000000.jpg"; // 未落盘
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, "x.png"), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void rejectsOverlongNameAndBlankName() throws IOException {
        String url = urlOf("202611", UID + "_1730000000.jpg", "jpg");
        String longName = "长".repeat(ValidationRules.REFUND_EVIDENCE_NAME_MAX + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, longName), UID, tempRoot.toFile()));
        assertTrue(ex.getMessage().contains("长度限制"));

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> RefundEvidenceValidator.normalize(item("image", url, "  "), UID, tempRoot.toFile()));
        assertTrue(ex2.getMessage().contains("缺少文件名"));
    }

    @Test
    void normalizesValidEvidenceAndStripsExtraFields() throws IOException {
        String jpg = urlOf("202611", UID + "_1730000000.jpg", "jpg");
        String pdf = urlOf("202611", UID + "_1730000001.pdf", "pdf");
        String raw = "[{\"type\":\"image\",\"url\":\"" + jpg + "\",\"name\":\"聊天记录截图.png\",\"junk\":\"inject\"},"
                + "{\"type\":\"file\",\"url\":\"" + pdf + "\",\"name\":\"协商说明.pdf\"}]";
        String out = RefundEvidenceValidator.normalize(raw, UID, tempRoot.toFile());
        assertTrue(out.startsWith("[{"));
        assertTrue(out.contains("\"type\":\"image\""));
        assertTrue(out.contains(jpg));
        assertTrue(out.contains("\"type\":\"file\""));
        assertTrue(out.contains(pdf));
        assertFalse(out.contains("junk"), "归一化必须剔除多余字段");
        // 元素顺序保持输入顺序
        assertTrue(out.indexOf(jpg) < out.indexOf(pdf));
    }

    @Test
    void acceptsVideoAndBinFileShapes() throws IOException {
        String mp4 = urlOf("202611", UID + "_1730000002.mp4", "mp4");
        String bin = urlOf("202611", UID + "_1730000003.bin", "bin");
        String out = RefundEvidenceValidator.normalize(item("video", mp4, "开箱视频.mp4"), UID, tempRoot.toFile());
        assertTrue(out.contains(mp4));
        String out2 = RefundEvidenceValidator.normalize(item("file", bin, "压缩包.bin"), UID, tempRoot.toFile());
        assertTrue(out2.contains(bin));
    }
}

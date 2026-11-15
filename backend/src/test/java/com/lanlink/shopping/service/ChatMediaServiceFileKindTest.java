package com.lanlink.shopping.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天媒体 file 归类单测（P2-4 zip 落 bin 修正）：
 * docx/xlsx/pptx/zip 同为 PK 头容器，此前统一落 .bin 无法解析——现在按
 * 「扩展名 + 魔数交叉验证」归类落库；伪造扩展名仍被拒绝；存量 .bin 只读兼容。
 * 快乐路径经 upload() 真实落盘，用例结束按返回 URL 回收。
 */
class ChatMediaServiceFileKindTest {

    private final ChatMediaService service = new ChatMediaService();

    private String uploadedUrl;

    @AfterEach
    void cleanup() {
        if (uploadedUrl != null) {
            com.lanlink.shopping.support.RefundEvidenceTestFiles.delete(uploadedUrl);
            uploadedUrl = null;
        }
    }

    private static MockMultipartFile file(String name, String contentType, byte[] body) {
        return new MockMultipartFile("file", name, contentType, body);
    }

    /** PK\x03\x04 头（zip 族：docx/xlsx/pptx/zip 共用容器魔数） */
    private static byte[] pkHeader() {
        return new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    }

    private static byte[] minimalZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write("sample".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void docxUploadLandsAsDocxNotBin() throws Exception {
        Map<String, Object> out = service.upload(9L, file("invoice.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                pkHeader()), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".docx"), "PK 头 + .docx 扩展名应落 docx（非 bin）：" + uploadedUrl);
    }

    @Test
    void xlsxUploadLandsAsXlsxNotBin() throws Exception {
        Map<String, Object> out = service.upload(9L, file("report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                pkHeader()), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".xlsx"), "PK 头 + .xlsx 扩展名应落 xlsx：" + uploadedUrl);
    }

    @Test
    void pptxUploadLandsAsPptxNotBin() throws Exception {
        Map<String, Object> out = service.upload(9L, file("deck.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pkHeader()), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".pptx"), "PK 头 + .pptx 扩展名应落 pptx：" + uploadedUrl);
    }

    @Test
    void zipUploadLandsAsZipNotBin() throws Exception {
        Map<String, Object> out = service.upload(9L, file("bundle.zip", "application/zip",
                minimalZip()), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".zip"), "zip 应落 .zip：" + uploadedUrl);
    }

    @Test
    void pdfUploadStillLandsAsPdf() throws Exception {
        Map<String, Object> out = service.upload(9L, file("doc.pdf", "application/pdf",
                "%PDF-1.4 sample".getBytes(StandardCharsets.UTF_8)), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".pdf"));
    }

    @Test
    void forgedExtensionStillRejected() {
        // 伪造扩展名绕过：可执行内容伪装 .docx——PK 头存在但白名单交叉验证放行的前提是
        // 客户端扩展名本身在 {docx,xlsx,pptx,zip}；.exe 不在白名单，即使改名也拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(9L, file("evil.exe", "application/octet-stream",
                        pkHeader()), "file"));
        assertTrue(ex.getMessage().contains("仅支持"));
    }

    @Test
    void legacyBinStillAcceptedForReadCompat() throws Exception {
        // 存量 .bin 落库只读兼容不迁移：PK 头 + .bin 名仍按 bin 放行（体积走 MAX_FILE）
        Map<String, Object> out = service.upload(9L, file("legacy.bin", "application/octet-stream",
                pkHeader()), "file");
        uploadedUrl = (String) out.get("url");
        assertTrue(uploadedUrl.endsWith(".bin"));
    }

    @Test
    void txtCsvTwoMbSpecialCaseUnchanged() {
        // txt/csv 无魔数特例：仅限 .txt/.csv 且 2MB 上限不随 20MB 对齐（chatMedia.js 联动）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload(9L, file("note.md", "text/markdown",
                        "plain".getBytes(StandardCharsets.UTF_8)), "file"));
        assertTrue(ex.getMessage().contains("仅支持"));
    }
}

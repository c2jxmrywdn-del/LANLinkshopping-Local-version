package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档抽取的最小样本测试：纯本地生成，不依赖任何外部样例文件。
 */
class SupportDocumentTextExtractorTest {

    private final SupportDocumentTextExtractor extractor = new SupportDocumentTextExtractor();

    @Test
    void extractsPlainTextAndStripsUtf8Bom() {
        byte[] body = "客服备注：订单 SM202611080001 已发货".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        SupportDocumentTextExtractor.Result result = extractor.extract("note.txt", "text/plain", withBom);

        assertEquals("客服备注：订单 SM202611080001 已发货", result.text());
        assertFalse(result.scanned());
        assertNull(result.pageCount());
    }

    @Test
    void extractsCsvAsPlainText() {
        byte[] csv = "orderNo,amount\nSM001,199.00\n".getBytes(StandardCharsets.UTF_8);

        SupportDocumentTextExtractor.Result result = extractor.extract("orders.csv", "text/csv", csv);

        assertTrue(result.text().contains("SM001"));
        assertTrue(result.text().contains("199.00"));
    }

    @Test
    void extractsTextFromGeneratedPdf() throws Exception {
        byte[] pdf = minimalPdf("Support media PDF extraction sample - order SM123456 amount 199.00");

        SupportDocumentTextExtractor.Result result = extractor.extract("sample.pdf", "application/pdf", pdf);

        assertEquals(1, result.pageCount());
        assertTrue(result.text().contains("SM123456"));
        // 正文超过 50 字，不应被误判为扫描件
        assertFalse(result.scanned());
    }

    @Test
    void marksBlankPdfAsScanned() throws Exception {
        byte[] pdf = minimalPdf("");

        SupportDocumentTextExtractor.Result result = extractor.extract("scan.pdf", "application/pdf", pdf);

        assertEquals(1, result.pageCount());
        assertTrue(result.scanned());
    }

    @Test
    void extractsTextEntriesFromZip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write("第一个文件：SM001".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("b.csv"));
            zip.write("orderNo,amount\nSM002,88.00".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        SupportDocumentTextExtractor.Result result = extractor.extract("bundle.zip", "application/zip",
                out.toByteArray());

        assertTrue(result.text().contains("【a.txt】"));
        assertTrue(result.text().contains("【b.csv】"));
        assertTrue(result.text().contains("SM002"));
    }

    @Test
    void oversizedZipMarksPartialWithReason() throws Exception {
        // 21 个条目（> MAX_ZIP_ENTRIES=20）：partial=true + 原因（P2 超限显式化）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 21; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zip.write(("内容 " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }

        SupportDocumentTextExtractor.Result result = extractor.extract("big.zip", "application/zip",
                out.toByteArray());

        assertTrue(result.partial());
        assertTrue(result.partialReason().contains("20"));
    }

    @Test
    void binaryZipStillListsEntryNames() throws Exception {
        // 二进制压缩包抽不出正文：文件清单仍进上下文（P2 zip 清单枚举），不静默返回空
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("invoice.bin"));
            zip.write(new byte[]{0, 1, 2, 0, 3});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("report.pdf"));
            zip.write(new byte[]{0, 4, 0, 5});
            zip.closeEntry();
        }

        SupportDocumentTextExtractor.Result result = extractor.extract("docs.zip", "application/zip",
                out.toByteArray());

        assertTrue(result.text().contains("invoice.bin"));
        assertTrue(result.text().contains("report.pdf"));
        assertTrue(result.text().contains("2 个文件"));
    }

    @Test
    void legacyDocStillRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> extractor.extract("legacy.doc", "application/msword", new byte[]{1, 2, 3}));

        assertEquals(415, error.getCode());
        assertTrue(error.getMessage().contains(".docx"));
    }

    private static byte[] minimalPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                if (!text.isEmpty()) {
                    content.showText(text);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}

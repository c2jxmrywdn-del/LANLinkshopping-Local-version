package com.lanlink.shopping.support.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 图像/文件理解扩容的消费链测试：真实小样 fixture 全部测试内生成，零外部样例文件。
 *
 * <p>覆盖规划 P2-5/P2-6 的机器可判口径：
 * <ul>
 *   <li>图片消息不再止步于存储——视觉降级链结果落 summary 并计量 capability=VISION；</li>
 *   <li>视觉全空时 OCR-only 是合法终态（摘要回退 OCR 文本），不静默编造；</li>
 *   <li>PDF/DOCX/XLSX/zip 真实小样解析进理解链（summary/ocrText 落库）；</li>
 *   <li>超限 zip → entities_json.partial=true + skippedCount 原因（消灭静默丢弃）；</li>
 *   <li>文档解析计量 capability=DOC（正文字符数近似 tokens）；</li>
 *   <li>扫描版 PDF 渲染页数走 SUPPORT_MEDIA_PDF_SCAN_PAGES。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportMediaParseConsumerImageDocTest {

    private static final String VISITOR = "2f6f1d1e-3c41-4a5b-8c7d-1a2b3c4d5e6f";

    @Mock
    private SupportMediaRepository repository;

    /** 真实抽取器（PDF/DOCX/XLSX/zip fixture 走真解析）；损坏文件用例在其上 stub 抛错（spy） */
    private SupportDocumentTextExtractor extractor;

    @Mock
    private SupportMediaPerceptionClient perception;

    @Mock
    private SupportLlmUsageRecorder usageRecorder;

    @Mock
    private S3Client s3;

    private SupportMediaParseConsumer consumer;

    @BeforeEach
    void setUp() {
        extractor = org.mockito.Mockito.spy(new SupportDocumentTextExtractor());
        consumer = new SupportMediaParseConsumer(repository, extractor, perception, usageRecorder,
                new StorageProperties(), new ObjectMapper(), s3);
    }

    private void stubObjectStorage(byte[] bytes) {
        ResponseInputStream<GetObjectResponse> in = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(in);
    }

    /** 认领链桩：findById 返回待解析行 + markParsing CAS 成功（kind 决定 parse 分派分支） */
    private void stubRow(String kind, String fileName, String contentType) {
        // findById + markParsing 一起桩：缺前者 row==null 会提前 ack 跳过，缺后者 CAS 失败跳过
        when(repository.findById(9L)).thenReturn(Optional.of(row(kind, fileName, contentType)));
        when(repository.markParsing(9L)).thenReturn(1);
    }

    private SupportMediaRepository.Row row(String kind, String fileName, String contentType) {
        return new SupportMediaRepository.Row(9L, "SM1", null, null, VISITOR, kind,
                "k", fileName, contentType, 1000L, "sha", null, null, null, null,
                SupportMediaRepository.STATUS_UPLOADED, null, null, null, null,
                null, null, null, 0, null, null, null);
    }

    private void consume(String payload) {
        consumer.consume(payload);
    }

    // ==================== B1 图像理解扩容 ====================

    @Test
    void imageUnderstandingEntersChainAndVisionLedger() {
        stubRow("IMAGE", "photo.png", "image/png");
        stubObjectStorage(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        when(perception.ocr(any(), anyString())).thenReturn(Optional.of("订单号 SM123"));
        when(perception.visionWithFallback(any(), anyString(), anyString())).thenReturn(Optional.of(
                new SupportMediaPerceptionClient.VisionResult("一张发货单照片，含订单 SM123",
                        "mimo", "mimo-v2.6-flash")));
        when(repository.markReady(eq(9L), eq("订单号 SM123"), isNull(),
                contains("发货单"), eq("mimo"), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"IMAGE\",\"objectKey\":\"k\"}");

        // 理解结果进计量账本：capability=VISION、模型=降级链实际生效腿
        verify(usageRecorder).recordVision(eq(VISITOR), eq(9L), eq("mimo-v2.6-flash"),
                contains("发货单"));
        verify(usageRecorder, never()).recordDocument(anyString(), anyLong(), any());
        // 摘要落库（理解链经 grounding 读取）
        verify(repository).markReady(eq(9L), eq("订单号 SM123"), isNull(),
                contains("发货单"), eq("mimo"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void imageFallsBackToOcrOnlyWhenVisionChainEmpty() {
        stubRow("IMAGE", "photo.png", "image/png");
        stubObjectStorage(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        // 视觉降级链三段全空（MiMo/本地腿/OCR 视觉侧均不可用）：OCR-only 是合法终态
        when(perception.ocr(any(), anyString())).thenReturn(Optional.of("发票抬头：LANLink"));
        when(perception.visionWithFallback(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.markReady(eq(9L), eq("发票抬头：LANLink"), isNull(),
                contains("发票"), anyString(), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"IMAGE\",\"objectKey\":\"k\"}");

        // 视觉链无产出不计量（不落空账），摘要回退 OCR 文本
        verify(usageRecorder, never()).recordVision(anyString(), anyLong(), anyString(), anyString());
        verify(repository).markReady(eq(9L), eq("发票抬头：LANLink"), isNull(),
                contains("发票"), anyString(), any(), isNull(), isNull(), isNull());
    }

    // ==================== B2 文件解析扩容（真实小样 fixture）====================

    @Test
    void pdfFixtureTextEntersUnderstandingChain() throws Exception {
        // PDFBox 标准 14 字体（Helvetica/WinAnsi）不含 CJK 字形，fixture 用 ASCII 文本
        byte[] pdf = minimalPdf("Support media PDF sample: order SM20261110001 amount 199.00");
        stubRow("DOCUMENT", "sample.pdf", "application/pdf");
        stubObjectStorage(pdf);
        when(repository.markReady(eq(9L), isNull(), isNull(), contains("SM20261110001"),
                eq("pdfbox"), any(), eq(1), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        // DOC 计量：正文字符数近似 tokens 落账
        ArgumentCaptor<SupportDocumentTextExtractor.Result> doc =
                ArgumentCaptor.forClass(SupportDocumentTextExtractor.Result.class);
        verify(usageRecorder).recordDocument(eq(VISITOR), eq(9L), doc.capture());
        assertTrue(doc.getValue().text().contains("SM20261110001"));
        verify(repository).markReady(eq(9L), isNull(), isNull(), contains("SM20261110001"),
                eq("pdfbox"), any(), eq(1), isNull(), isNull());
    }

    @Test
    void docxFixtureTextEntersUnderstandingChain() throws Exception {
        byte[] docx = minimalDocx("客服样本 DOCX：退货政策 7 天无理由");
        stubRow("DOCUMENT", "sample.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        stubObjectStorage(docx);
        when(repository.markReady(eq(9L), isNull(), isNull(), contains("退货政策"),
                eq("poi"), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        verify(usageRecorder).recordDocument(eq(VISITOR), eq(9L), any());
        verify(repository).markReady(eq(9L), isNull(), isNull(), contains("退货政策"),
                eq("poi"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void xlsxFixtureTextEntersUnderstandingChain() throws Exception {
        byte[] xlsx = minimalXlsx();
        stubRow("DOCUMENT", "sample.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        stubObjectStorage(xlsx);
        when(repository.markReady(eq(9L), isNull(), isNull(), contains("SKU1001"),
                eq("poi"), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        verify(usageRecorder).recordDocument(eq(VISITOR), eq(9L), any());
        verify(repository).markReady(eq(9L), isNull(), isNull(), contains("SKU1001"),
                eq("poi"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void zipFixtureListsEntriesIntoUnderstandingChain() throws Exception {
        byte[] zip = minimalZip("order.txt", "订单 SM001 已发货", "memo.txt", "买家备注：尽快送达");
        stubRow("DOCUMENT", "bundle.zip", "application/zip");
        stubObjectStorage(zip);
        when(repository.markReady(eq(9L), isNull(), isNull(), contains("SM001"),
                eq("poi"), any(), isNull(), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        verify(usageRecorder).recordDocument(eq(VISITOR), eq(9L), any());
        verify(repository).markReady(eq(9L), isNull(), isNull(), contains("SM001"),
                eq("poi"), any(), isNull(), isNull(), isNull());
    }

    @Test
    void oversizedZipMarksPartialIntoEntitiesJson() throws Exception {
        // 21 个条目（> MAX_ZIP_ENTRIES=20）：partial=true 且原因进 entities_json
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 21; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zip.write(("内容 " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        stubRow("DOCUMENT", "bundle.zip", "application/zip");
        stubObjectStorage(out.toByteArray());
        when(repository.markReady(eq(9L), isNull(), isNull(), anyString(), eq("poi"),
                any(), isNull(), isNull(), contains("partial"))).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        ArgumentCaptor<String> entities = ArgumentCaptor.forClass(String.class);
        verify(repository).markReady(eq(9L), isNull(), isNull(), anyString(), eq("poi"),
                any(), isNull(), isNull(), entities.capture());
        assertNotNull(entities.getValue());
        assertTrue(entities.getValue().contains("\"partial\":true"));
        assertTrue(entities.getValue().contains("仅解析前 20 个"));
    }

    @Test
    void scannedPdfUsesConfiguredScanPages() throws Exception {
        byte[] pdf = minimalPdf("");
        stubRow("DOCUMENT", "scan.pdf", "application/pdf");
        stubObjectStorage(pdf);
        consumer.setPdfScanPagesForTest(1);
        // 扫描版 PDF：视觉降级链逐页理解
        when(perception.visionWithFallback(any(), anyString(), anyString())).thenReturn(Optional.of(
                new SupportMediaPerceptionClient.VisionResult("扫描页文字：订单 SM888", "mimo", "mimo-v2.6-flash")));
        when(repository.markReady(eq(9L), isNull(), isNull(), contains("SM888"),
                eq("pdfbox+mimo"), any(), eq(1), isNull(), isNull())).thenReturn(1);

        consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}");

        verify(extractor).renderPdfFirstPages(any(), eq(1), eq(150f));
        verify(repository).markReady(eq(9L), isNull(), isNull(), contains("SM888"),
                eq("pdfbox+mimo"), any(), eq(1), isNull(), isNull());
    }

    @Test
    void documentParseFailureRetriesWithoutUsage() {
        stubRow("DOCUMENT", "sample.pdf", "application/pdf");
        stubObjectStorage("corrupt".getBytes(StandardCharsets.UTF_8));
        org.mockito.Mockito.doThrow(new BusinessException(422, "PDF 解析失败，文件可能已损坏或加密"))
                .when(extractor).extract(anyString(), anyString(), any());
        when(repository.markRetry(eq(9L), eq(1), anyString())).thenReturn(1);

        BusinessException error = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> consume("{\"mediaId\":9,\"mediaNo\":\"SM1\",\"kind\":\"DOCUMENT\",\"objectKey\":\"k\"}"));

        assertEquals(422, error.getCode());
        verify(usageRecorder, never()).recordDocument(anyString(), anyLong(), any());
        verify(repository, never()).markReady(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ==================== fixtures（测试内生成，零外部样例）====================

    private static byte[] minimalPdf(String text) throws IOException {
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

    private static byte[] minimalDocx(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] minimalXlsx() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("订单");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("sku");
            header.createCell(1).setCellValue("amount");
            org.apache.poi.ss.usermodel.Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("SKU1001");
            data.createCell(1).setCellValue("199.00");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] minimalZip(String name1, String content1, String name2, String content2)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(name1));
            zip.write(content1.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(name2));
            zip.write(content2.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}

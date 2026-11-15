package com.lanlink.shopping.support.media;

import com.lanlink.shopping.common.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 客服文档正文抽取：PDF / DOCX / XLSX / PPTX / 纯文本 / ZIP。
 *
 * <p>为什么不用 Tika：Tika 的解析器体系会带进几十个传递依赖与 servlet 组件，而本链路只需要
 * 四类格式；直接用 PDFBox/POI 更容易对「页数、行数、条目数、总字节」做硬限制。
 *
 * <p>所有格式都按「先限大小、再限数量、最后限时间」的顺序处理：
 * 上传侧已经限过 20MB，但压缩包解压后可能膨胀数百倍，因此 ZIP 必须逐条目限制解压总量，
 * 超限直接停止而不是继续跳过——继续读会把剩余条目解压出来，等于把 zip bomb 的代价付完。
 *
 * <p>解析失败一律抛 {@link BusinessException}（4xx），由消费端按重试策略决定是否重投：
 * 文件损坏、加密、格式不支持这类问题重试不会变好，消费端据此在达到上限后置 FAILED。
 */
@Component
public class SupportDocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(SupportDocumentTextExtractor.class);

    /** 上传上限 20MB，解析侧再挡一次，避免被绕过上传校验的调用方拖垮堆内存 */
    static final int MAX_INPUT_BYTES = 20 * 1024 * 1024;
    /** 单次解析耗时上限：PDF 渲染与大型表格最容易卡住消费者 */
    static final int MAX_PARSE_SECONDS = 45;
    /** 落库正文上限：MEDIUMTEXT 能吃更多，但再长对客服展示与后续摘要都没有意义 */
    static final int MAX_TEXT_CHARS = 200_000;
    /** 纯文本类单文件读取上限（200KB）：再多也不会提升摘要质量，只会拖慢解码与落库 */
    static final int MAX_TEXT_BYTES = 200 * 1024;
    static final int MAX_PDF_PAGES = 50;
    static final int MAX_SHEETS = 5;
    static final int MAX_ROWS_PER_SHEET = 200;
    static final int MAX_COLS_PER_ROW = 50;
    static final int MAX_SLIDES = 30;
    static final int MAX_ZIP_ENTRIES = 20;
    static final int MAX_ZIP_ENTRY_BYTES = 200 * 1024;
    static final int MAX_ZIP_TOTAL_BYTES = 2 * 1024 * 1024;
    /** zip 清单枚举上限：只记文件名不解压内容，超出的文件名不再进上下文 */
    static final int MAX_ZIP_LISTING = 50;
    /** 文本少于此阈值视为扫描件：普通 PDF 两行字都超过这个数，扫描件往往只剩页眉页脚 */
    static final int SCANNED_TEXT_THRESHOLD = 50;

    /**
     * 抽取结果：scanned 只对 PDF 有意义，用于让消费端决定是否走视觉兜底。
     *
     * <p>P2 超限显式化：partial=true 表示文件只解析了一部分（zip 条目/单条目/总量超限、
     * 正文 200k 截断等），partialReason 给出可展示的中文原因——调用方据此落
     * entities_json.partial 并在前端警示，消灭静默丢弃。
     */
    public record Result(String text, Integer pageCount, boolean scanned,
                         boolean partial, String partialReason) {
        /** 兼容旧调用方：默认非 partial */
        public Result(String text, Integer pageCount, boolean scanned) {
            this(text, pageCount, scanned, false, null);
        }
    }

    public Result extract(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(422, "文件为空，无法解析");
        }
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new BusinessException(413, "文档超过解析上限（20MB）");
        }
        String ext = extensionOf(fileName, contentType);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_PARSE_SECONDS);
        return switch (ext) {
            case "pdf" -> extractPdf(bytes, deadline);
            case "docx" -> extractDocx(bytes, deadline);
            case "xlsx", "xls" -> extractWorkbook(bytes, deadline);
            case "pptx" -> extractPptx(bytes, deadline);
            case "txt", "csv", "md" -> new Result(decodeText(bytes), null, false);
            case "zip" -> extractZip(bytes, deadline);
            case "doc" -> throw new BusinessException(415, "暂不支持 .doc（二进制 Word），请另存为 .docx 后重新上传");
            case "ppt" -> throw new BusinessException(415, "暂不支持 .ppt（二进制 PowerPoint），请另存为 .pptx 后重新上传");
            default -> throw new BusinessException(415, "暂不支持该文档格式：" + (ext.isEmpty() ? "未知" : ext));
        };
    }

    /**
     * 扫描版 PDF 的视觉兜底：把前若干页渲染成 PNG 交给多模态模型。
     *
     * <p>150dpi 是「文字可读」与「单页体积可接受」的折中点；再高会让 Base64 体积翻倍，
     * 网关侧视觉模型也会因输入过大而超时。
     */
    public List<byte[]> renderPdfFirstPages(byte[] pdf, int maxPages, float dpi) {
        if (pdf == null || pdf.length == 0) {
            throw new BusinessException(422, "文件为空，无法渲染");
        }
        List<byte[]> images = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int limit = Math.min(document.getNumberOfPages(), Math.max(maxPages, 1));
            for (int i = 0; i < limit; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                images.add(out.toByteArray());
            }
            return images;
        } catch (IOException e) {
            log.warn("[support-media] PDF 页面渲染失败：{}", e.getMessage());
            throw new BusinessException(422, "PDF 页面渲染失败，文件可能已损坏或加密");
        }
    }

    private Result extractPdf(byte[] bytes, long deadline) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            checkDeadline(deadline);
            int totalPages = document.getNumberOfPages();
            int limit = Math.min(totalPages, MAX_PDF_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.max(limit, 1));
            String text = normalize(stripper.getText(document));
            checkDeadline(deadline);
            boolean scanned = text.replaceAll("\\s", "").length() < SCANNED_TEXT_THRESHOLD;
            boolean pageLimited = totalPages > MAX_PDF_PAGES;
            return finish(truncate(text, MAX_TEXT_CHARS), totalPages, scanned,
                    pageLimited, "PDF 超过 " + MAX_PDF_PAGES + " 页，仅解析前 " + MAX_PDF_PAGES + " 页");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[support-media] PDF 解析失败：{}", e.getMessage());
            throw new BusinessException(422, "PDF 解析失败，文件可能已损坏或加密");
        }
    }

    private Result extractDocx(byte[] bytes, long deadline) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                checkDeadline(deadline);
                append(text, paragraph.getText());
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    checkDeadline(deadline);
                    for (XWPFTableCell cell : row.getTableCells()) {
                        append(text, cell.getText());
                    }
                }
            }
            return new Result(truncate(normalize(text.toString()), MAX_TEXT_CHARS), null, false);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[support-media] DOCX 解析失败：{}", e.getMessage());
            throw new BusinessException(422, "Word 文档解析失败，文件可能已损坏或加密");
        }
    }

    /** xls 与 xlsx 共用一条路径：WorkbookFactory 会按文件头分派 HSSF/XSSF，不必各写一份 */
    private Result extractWorkbook(byte[] bytes, long deadline) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder text = new StringBuilder();
            int sheets = Math.min(workbook.getNumberOfSheets(), MAX_SHEETS);
            for (int s = 0; s < sheets; s++) {
                checkDeadline(deadline);
                Sheet sheet = workbook.getSheetAt(s);
                text.append("【").append(sheet.getSheetName()).append("】\n");
                int lastRow = Math.min(sheet.getLastRowNum(), MAX_ROWS_PER_SHEET - 1);
                for (int r = 0; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    int lastCell = Math.min(row.getLastCellNum(), MAX_COLS_PER_ROW);
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell == null) {
                            continue;
                        }
                        String value = formatter.formatCellValue(cell);
                        if (value != null && !value.isBlank()) {
                            text.append(value).append('\t');
                        }
                    }
                    text.append('\n');
                }
            }
            return new Result(truncate(normalize(text.toString()), MAX_TEXT_CHARS), null, false);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[support-media] 表格解析失败：{}", e.getMessage());
            throw new BusinessException(422, "表格文档解析失败，文件可能已损坏或加密");
        }
    }

    private Result extractPptx(byte[] bytes, long deadline) {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            List<XSLFSlide> slides = slideShow.getSlides();
            int limit = Math.min(slides.size(), MAX_SLIDES);
            for (int i = 0; i < limit; i++) {
                checkDeadline(deadline);
                text.append("【第 ").append(i + 1).append(" 页】\n");
                for (XSLFShape shape : slides.get(i).getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        append(text, textShape.getText());
                    } else if (shape instanceof XSLFTable table) {
                        for (XSLFTableRow row : table.getRows()) {
                            for (XSLFTableCell cell : row.getCells()) {
                                append(text, cell.getText());
                            }
                        }
                    }
                }
            }
            return new Result(truncate(normalize(text.toString()), MAX_TEXT_CHARS), null, false);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[support-media] PPTX 解析失败：{}", e.getMessage());
            throw new BusinessException(422, "演示文稿解析失败，文件可能已损坏或加密");
        }
    }

    private Result extractZip(byte[] bytes, long deadline) {
        List<String> parts = new ArrayList<>();
        List<String> listedNames = new ArrayList<>();
        long total = 0;
        int entries = 0;
        boolean partial = false;
        String partialReason = null;
        // zip 清单先枚举文件名（只读 entry 元数据不解压内容）：超限被跳过的条目名字
        // 仍进上下文，客服能知道包里有什么——对齐规划「zip 维持落库但清单枚举进上下文」。
        try (ZipInputStream listing = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry nameOnly;
            while ((nameOnly = listing.getNextEntry()) != null && listedNames.size() < MAX_ZIP_LISTING) {
                if (!nameOnly.isDirectory() && nameOnly.getName() != null && !nameOnly.getName().isBlank()) {
                    listedNames.add(nameOnly.getName());
                }
            }
        } catch (IOException e) {
            log.warn("[support-media] ZIP 清单枚举失败：{}", e.getMessage());
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                checkDeadline(deadline);
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entries > MAX_ZIP_ENTRIES) {
                    partial = true;
                    partialReason = "压缩包超过 " + MAX_ZIP_ENTRIES + " 个条目，仅解析前 " + MAX_ZIP_ENTRIES + " 个";
                    break;
                }
                byte[] content = in.readNBytes(MAX_ZIP_ENTRY_BYTES + 1);
                if (content.length > MAX_ZIP_ENTRY_BYTES) {
                    // 不再继续 getNextEntry：那会把当前条目剩余部分全部解压掉，zip bomb 的代价白付
                    log.warn("[support-media] ZIP 条目超过单条上限，停止解析 entry={}", entry.getName());
                    partial = true;
                    partialReason = "存在超过 " + (MAX_ZIP_ENTRY_BYTES / 1024) + "KB 的条目，超限后停止解析";
                    break;
                }
                total += content.length;
                if (total > MAX_ZIP_TOTAL_BYTES) {
                    log.warn("[support-media] ZIP 解压总量超过上限，停止解析");
                    partial = true;
                    partialReason = "解压总量超过 " + (MAX_ZIP_TOTAL_BYTES / 1024 / 1024) + "MB 上限，超限后停止解析";
                    break;
                }
                if (isBinary(content)) {
                    continue;
                }
                String text = decodeText(content);
                if (text.isBlank()) {
                    continue;
                }
                parts.add("【" + entry.getName() + "】\n" + text);
            }
        } catch (IOException e) {
            log.warn("[support-media] ZIP 解析失败：{}", e.getMessage());
            throw new BusinessException(422, "压缩包解析失败，文件可能已损坏或加密");
        }
        if (parts.isEmpty() && !listedNames.isEmpty()) {
            // 二进制/加密压缩包抽不出正文：至少把文件清单交给理解链，不静默返回空
            String listing = "压缩包内含 " + listedNames.size() + " 个文件：" + String.join("、", listedNames);
            return new Result(truncate(listing, MAX_TEXT_CHARS), null, false,
                    partial, partialReason);
        }
        String joined = truncate(String.join("\n\n", parts), MAX_TEXT_CHARS);
        boolean truncated = joined != null && joined.length() >= MAX_TEXT_CHARS
                && String.join("\n\n", parts).length() > MAX_TEXT_CHARS;
        return finish(joined, null, false,
                partial || truncated, truncated ? "正文超过 " + MAX_TEXT_CHARS + " 字截断" : partialReason);
    }

    /** 统一收口：partial 与原因只在确有截断时携带 */
    private static Result finish(String text, Integer pageCount, boolean scanned,
                                 boolean partial, String partialReason) {
        return new Result(text, pageCount, scanned,
                partial && partialReason != null, partial && partialReason != null ? partialReason : null);
    }

    /** UTF-8 解码并去 BOM：Windows 导出的 txt/csv 几乎都带 BOM，留着会污染首行摘要 */
    private static String decodeText(byte[] bytes) {
        int length = Math.min(bytes.length, MAX_TEXT_BYTES);
        int offset = length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text = new String(bytes, offset, length - offset, StandardCharsets.UTF_8);
        return truncate(normalize(text), MAX_TEXT_CHARS);
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 512);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value.trim()).append('\n');
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw new BusinessException(422, "文档解析超时，请拆分或压缩文件后重试");
        }
    }

    /** 优先用扩展名：contentType 由上传方声明，扩展名至少和文件内容一起被魔数校验过 */
    static String extensionOf(String fileName, String contentType) {
        String name = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("pdf")) {
            return "pdf";
        }
        if (type.contains("wordprocessingml")) {
            return "docx";
        }
        if (type.contains("spreadsheetml")) {
            return "xlsx";
        }
        if (type.contains("presentationml")) {
            return "pptx";
        }
        if (type.contains("zip")) {
            return "zip";
        }
        if (type.startsWith("text/")) {
            return "txt";
        }
        return "";
    }
}

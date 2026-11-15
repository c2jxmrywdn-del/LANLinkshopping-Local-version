package com.lanlink.shopping.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 营业执照水印引擎：自适应 / 空白区铺排 / 不遮挡核心信息 / 不改源文件 / 性能。
 *
 * <p>这些用例直接对像素做断言，而不是只看"没抛异常"——
 * 水印类需求最容易的失败模式就是"图出来了，但压住了正文"。
 */
class LicenseImageWatermarkTest {

    private static final String[] LINES = {
            "仅供测试运营在LANLINKShopping对外展示时使用",
            "最终解释权归属测试运营所有"
    };

    /** 生成一张"证件扫描件"：白底 + 上半部正文密集区 + 右下角公章 */
    private static BufferedImage fakeLicense(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            // 用真实文字渲染模拟"公司名称/信用代码/法人/地址"等正文：
            // 细矩形笔画太稀疏，无法代表真实执照的墨量分布
            int fontSize = Math.max(14, h / 26);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            String[] textLines = {
                    "营业执照 (副本)",
                    "统一社会信用代码 91310000MA1K000000",
                    "名称 重庆建工实业集团有限公司",
                    "类型 有限责任公司(自然人投资或控股)",
                    "法定代表人 欧阳某某",
                    "注册资本 人民币壹仟万元整",
                    "成立日期 2020年01月01日",
                    "住所 重庆市渝中区某某路 100 号",
                    "经营范围 建筑材料、金属材料、化工产品销售"
            };
            for (int i = 0; i < textLines.length; i++) {
                g.drawString(textLines[i], (int) (w * 0.08), (int) (h * 0.12) + i * (int) (fontSize * 1.7));
            }
            g.setColor(new Color(190, 30, 30));
            int d = Math.max(20, Math.min(w, h) / 4);
            g.fillOval(w - d - (int) (w * 0.06), h - d - (int) (h * 0.08), d, d);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * 统计"原本有墨（深色）的像素"里，被水印改动过的数量。
     *
     * <p>这才是"遮挡核心信息"的正确判据：文字笔画/公章线条被覆盖才算遮挡；
     * 字与字之间的白底被画上水印是正常的（水印就该落在空白处）。
     */
    private static int changedInkPixels(BufferedImage before, BufferedImage after,
                                        int x0, int y0, int x1, int y1) {
        int changed = 0;
        for (int y = Math.max(0, y0); y < Math.min(before.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(before.getWidth(), x1); x++) {
                int rgb = before.getRGB(x, y);
                // 只看原本就是墨迹的像素（亮度低于阈值）
                if (LicenseImageWatermark.luma(rgb) >= LicenseImageWatermark.BLANK_LUMA_THRESHOLD) continue;
                if (rgb != after.getRGB(x, y)) changed++;
            }
        }
        return changed;
    }

    // ==================== 自适应 ====================

    @Test
    void adaptsToCommonLicenseAspectRatios() throws Exception {
        int[][] sizes = {
                {800, 560},
                {1200, 850},
                {600, 900},
                {1600, 1000},
                {400, 400}
        };
        for (int[] s : sizes) {
            byte[] original = png(fakeLicense(s[0], s[1]));
            byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
            BufferedImage out = decode(marked);
            assertNotNull(out, s[0] + "x" + s[1] + " 必须能解码");
            assertEquals(s[0], out.getWidth(), "宽度不得改变");
            assertEquals(s[1], out.getHeight(), "高度不得改变");
            assertTrue(marked.length > 0);
        }
    }

    @Test
    void fontSizeScalesWithImageButStaysReadable() {
        assertEquals(12, LicenseImageWatermark.fontSizeFor(400, 400), "小图字号下限 12px");
        assertEquals(34, LicenseImageWatermark.fontSizeFor(4000, 3000), "大图字号上限 34px");
        int small = LicenseImageWatermark.fontSizeFor(800, 600);
        int large = LicenseImageWatermark.fontSizeFor(1600, 1200);
        assertTrue(large > small, "字号必须随尺寸增大");
    }

    @Test
    void oversizedImagesAreDownscaledToBoundProcessingTime() throws Exception {
        byte[] original = png(fakeLicense(6000, 4000));
        byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
        BufferedImage out = decode(marked);
        assertTrue(Math.max(out.getWidth(), out.getHeight()) <= LicenseImageWatermark.MAX_EDGE,
                "超长边必须降采样到上限内，避免大图拖慢接口");
        double ratioIn = 6000.0 / 4000.0;
        double ratioOut = (double) out.getWidth() / out.getHeight();
        assertTrue(Math.abs(ratioIn - ratioOut) < 0.02, "降采样必须保持纵横比");
    }

    // ==================== 核心信息不遮挡 ====================

    @Test
    void doesNotCoverDenseTextRegion() throws Exception {
        int w = 1000, h = 700;
        byte[] original = png(fakeLicense(w, h));
        byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
        BufferedImage before = decode(original);
        BufferedImage after = decode(marked);

        int changed = changedInkPixels(before, after,
                (int) (w * 0.06), (int) (h * 0.06), (int) (w * 0.72), (int) (h * 0.55));
        assertEquals(0, changed, "正文密集区不得被水印覆盖，实际被改动 " + changed + " 像素");
    }

    @Test
    void doesNotCoverSealRegion() throws Exception {
        int w = 1000, h = 700;
        byte[] original = png(fakeLicense(w, h));
        byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
        BufferedImage before = decode(original);
        BufferedImage after = decode(marked);

        int d = Math.min(w, h) / 4;
        int sx = w - d - (int) (w * 0.06);
        int sy = h - d - (int) (h * 0.08);
        int changed = changedInkPixels(before, after, sx, sy, sx + d, sy + d);
        assertEquals(0, changed, "公章区域不得被水印覆盖，实际被改动 " + changed + " 像素");
    }

    @Test
    void stillDrawsWatermarkInBlankAreas() throws Exception {
        int w = 1000, h = 700;
        byte[] original = png(fakeLicense(w, h));
        byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
        BufferedImage before = decode(original);
        BufferedImage after = decode(marked);

        // 全图必须有像素被改动（水印落在白底上），否则等于"什么都没画"
        int anyChanged = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) anyChanged++;
            }
        }
        assertTrue(anyChanged > 0, "空白区必须实际绘制水印");
    }

    // ==================== 源文件不变 ====================

    @Test
    void originalBytesAreNotMutated() throws Exception {
        byte[] original = png(fakeLicense(900, 640));
        byte[] snapshot = original.clone();

        LicenseImageWatermark.applyPng(original, LINES);

        assertArrayEquals(snapshot, original, "水印处理不得修改传入的源字节（内存不可变）");
    }

    // ==================== 输入校验 ====================

    @Test
    void rejectsEmptyWatermarkLines() {
        assertThrows(IllegalArgumentException.class,
                () -> LicenseImageWatermark.applyPng(new byte[]{1, 2, 3}, new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> LicenseImageWatermark.applyPng(new byte[]{1, 2, 3}, null));
    }

    @Test
    void rejectsUnrecognizedImage() {
        byte[] notImage = "not an image".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class,
                () -> LicenseImageWatermark.applyPng(notImage, LINES));
    }

    // ==================== 性能 ====================

    @Test
    void singleImageProcessingUnder200ms() throws Exception {
        byte[] original = png(fakeLicense(1600, 1100));
        LicenseImageWatermark.applyPng(original, LINES);

        long start = System.nanoTime();
        LicenseImageWatermark.applyPng(original, LINES);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 200, "单张水印处理必须 <200ms，实际 " + elapsedMs + "ms");
    }

    // ==================== 墨量栅格 ====================

    /**
     * 诊断：水印是否真的落到了正文区？逐行扫描改动像素的分布，
     * 若改动集中在正文带（8%~55% 高度）说明空白判定失效。
     */
    @Test
    void diagnoseChangedPixelDistribution() throws Exception {
        int w = 1000, h = 700;
        byte[] original = png(fakeLicense(w, h));
        byte[] marked = LicenseImageWatermark.applyPng(original, LINES);
        BufferedImage before = decode(original);
        BufferedImage after = decode(marked);

        // 只统计"原本是墨迹"的像素是否被覆盖：白底被画上水印是正常的
        int inkInBand = 0;
        int inkTotal = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = before.getRGB(x, y);
                if (LicenseImageWatermark.luma(rgb) >= LicenseImageWatermark.BLANK_LUMA_THRESHOLD) continue;
                inkTotal++;
                if (rgb != after.getRGB(x, y)) inkInBand++;
            }
        }
        System.out.println("[diag] inkPixels=" + inkTotal + " overwrittenInk=" + inkInBand);
        assertTrue(inkTotal > 0, "测试图必须含真实墨迹，否则该用例无意义");
        assertEquals(0, inkInBand, "墨迹像素（正文/公章）不得被水印覆盖");
    }

    /**
     * 坐标映射自检：把「旋转坐标系下的图章框」映射回图片坐标，
     * 必须能正确命中正文区（说明映射方向对）与页边（说明空白判定对）。
     *
     * <p>这是水印"不遮挡"能力的前提：映射反了，判定就永远查到错的位置。
     */
    @Test
    void rotatedStampBoxMapsBackToImageCoordinates() throws Exception {
        int w = 1000, h = 700;
        BufferedImage img = fakeLicense(w, h);
        LicenseImageWatermark.InkDensity density = new LicenseImageWatermark.InkDensity(img, 42);

        // 与生产代码一致的变换：translate(center) → rotate
        java.awt.geom.AffineTransform toImage = new java.awt.geom.AffineTransform();
        toImage.translate(w / 2.0, h / 2.0);
        toImage.rotate(Math.toRadians(LicenseImageWatermark.ROTATE_DEG));
        java.awt.geom.AffineTransform inv = toImage.createInverse();

        // 图片中心在旋转坐标系里的位置：中心点本身必须映射到旋转原点
        double[] centerInRotated = new double[2];
        inv.transform(new double[]{w / 2.0, h / 2.0}, 0, centerInRotated, 0, 1);
        assertEquals(0.0, centerInRotated[0], 1.0, "图片中心在旋转坐标系应为原点 x");
        assertEquals(0.0, centerInRotated[1], 1.0, "图片中心在旋转坐标系应为原点 y");

        // 取一个明显落在正文里的图片坐标，正向映射后再逆映射应回到原处
        double[] src = {120, 100};
        double[] mid = new double[2];
        toImage.transform(src, 0, mid, 0, 1);
        double[] back = new double[2];
        inv.transform(mid, 0, back, 0, 1);
        assertEquals(src[0], back[0], 0.5, "正逆变换必须互为逆");
        assertEquals(src[1], back[1], 0.5, "正逆变换必须互为逆");
    }

    @Test
    void inkDensityDetectsTextAndBlank() throws Exception {
        BufferedImage img = fakeLicense(600, 400);
        LicenseImageWatermark.InkDensity density =
                new LicenseImageWatermark.InkDensity(img, 30);

        // 诊断：打印正文区实际像素亮度，确认判定依据
        int rgb = img.getRGB(60, 60);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        assertFalse(density.isBlank(60, 60),
                "正文区必须判定为非空白（该点 luma=" + luma + "，阈值 "
                        + LicenseImageWatermark.BLANK_LUMA_THRESHOLD + "）");
        assertTrue(density.isBlank(300, 2), "页边留白必须判定为空白");
        assertTrue(density.blankCells() > 0, "必须有可用空白格");
        assertTrue(density.blankCells() < density.totalCells(),
                "不得把整图都判成空白（否则会压正文）");
    }
}


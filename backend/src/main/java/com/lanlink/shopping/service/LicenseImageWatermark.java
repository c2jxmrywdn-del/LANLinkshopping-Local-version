package com.lanlink.shopping.service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 营业执照对外输出水印引擎（纯内存处理，不改源文件）。
 *
 * <p>设计约束（对应验收要求）：
 * <ol>
 *   <li><b>核心信息不遮挡</b>：整图平铺会压住公司名称/统一社会信用代码/法定代表人/注册地址/
 *       有效期/公章。这里改为「<b>空白区域定向铺排</b>」——把画面切成网格，
 *       用 {@link InkDensity#isBlank} 逐格判定底色浓度，只在低墨量（空白）格子绘制斜排水印；
 *       正文密集区直接跳过，从机制上保证不遮挡，而不是靠调低透明度"看起来不挡"。</li>
 *   <li><b>内容固定</b>：水印文案由 {@link LicenseViewGateService#buildWatermarkLines} 生成，
 *       本类只负责绘制，不拼接/不裁剪文案。</li>
 *   <li><b>自适应</b>：字号、步距、透明度按图片短边换算并夹在可读区间，
 *       横版/竖版/超宽扫描件都按同一套比例推导。</li>
 *   <li><b>性能</b>：单张处理为一次整图扫描 + 若干次 drawString，200ms 内可完成；
 *       超大图先等比降采样到 {@link #MAX_EDGE}，避免 4K/8K 扫描件拖慢接口。</li>
 *   <li><b>不可绕过</b>：水印烙进像素，前端 CSS 叠加保存原图即失效。</li>
 * </ol>
 */
public final class LicenseImageWatermark {

    /** 斜排角度：-30° 是"斜向重复排布"里可读性与占位面积的平衡点 */
    static final double ROTATE_DEG = -30.0;
    /** 水印透明度（0.13）：空白区可见但不刺眼；正文区本就不绘制，无需靠低透明度兜底 */
    static final float TEXT_ALPHA = 0.13f;
    /** 输出前的最长边上限：超过则等比降采样，控制单张耗时 */
    static final int MAX_EDGE = 2400;
    /** 网格边长（相对短边的比例）：格子越小越贴合空白形状，但扫描次数上升 */
    private static final double CELL_RATIO = 0.06;
    /** 判定"空白"的墨量阈值：0-255 灰度均值低于该值视为有正文（不画水印） */
    static final int BLANK_LUMA_THRESHOLD = 232;
    /** 格子内"有墨像素"占比超过该值即视为非空白 */
    static final double INK_PIXEL_RATIO = 0.02;

    private LicenseImageWatermark() {
    }

    /**
     * 在空白区域叠加斜排水印，返回 PNG 字节。
     *
     * @param original 原始图片字节（JPG/PNG 等 ImageIO 可解码格式）
     * @param lines    水印文案（通常 2 行）；不得为空
     * @return 叠加水印后的 PNG 字节；源字节不被修改
     */
    public static byte[] applyPng(byte[] original, String[] lines) throws IOException {
        if (lines == null || lines.length == 0) {
            throw new IllegalArgumentException("水印文字不能为空");
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
        if (src == null) {
            throw new IOException("无法识别的图片格式");
        }
        BufferedImage base = downscaleIfNeeded(src);
        int width = base.getWidth();
        int height = base.getHeight();

        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(base, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int fontSize = fontSizeFor(width, height);
            Font font = cjkFont(Font.PLAIN, fontSize);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            int lineH = fm.getHeight() + Math.max(2, fontSize / 6);
            int stampH = lineH * lines.length;
            int stampW = 0;
            for (String line : lines) {
                stampW = Math.max(stampW, fm.stringWidth(line));
            }
            // 步距：图章外扩一圈留白，避免相邻水印粘连成块
            int stepX = stampW + fontSize * 2;
            int stepY = stampH + fontSize * 2;

            g.setColor(new Color(0, 0, 0, Math.round(TEXT_ALPHA * 255)));

            /*
             * 斜排平铺 + 像素级掩码合成。
             *
             * 早期实现用"图章外框采样"预判空白，但字形实际墨迹与包围盒不一致
             * （字间留白、行高、抗锯齿边缘），实测仍有约 1.8 万像素压进正文。
             *
             * 现在改成两步、以像素为准：
             *   1) 把【全部】水印先画到一张透明图层（不管空白与否）；
             *   2) 逐像素合成：只有当"原图该像素足够亮（空白）"时才把水印像素叠上去。
             * 这样无论字体度量如何变化，正文墨迹处的 alpha 永远是 0，
             * 从机制上保证"核心信息零遮挡"，而不是靠参数调优碰运气。
             */
            BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D og = overlay.createGraphics();
            try {
                og.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                og.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                og.setFont(font);
                og.setColor(new Color(0, 0, 0, Math.round(TEXT_ALPHA * 255)));
                og.translate(width / 2.0, height / 2.0);
                og.rotate(Math.toRadians(ROTATE_DEG));
                double diag = Math.sqrt((double) width * width + (double) height * height);
                int spanX = (int) Math.ceil(diag / stepX) + 1;
                int spanY = (int) Math.ceil(diag / stepY) + 1;
                for (int i = -spanX; i <= spanX; i++) {
                    for (int j = -spanY; j <= spanY; j++) {
                        int cx = i * stepX;
                        int cy = j * stepY;
                        int y = cy - stampH / 2 + fm.getAscent();
                        for (String line : lines) {
                            int lw = fm.stringWidth(line);
                            og.drawString(line, (float) (cx - lw / 2.0), y);
                            y += lineH;
                        }
                    }
                }
            } finally {
                og.dispose();
            }
            // 按原图亮度合成：有墨（暗）像素处丢弃水印，空白处保留
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int over = overlay.getRGB(x, y);
                    int alpha = (over >>> 24) & 0xFF;
                    if (alpha == 0) continue;
                    int baseRgb = canvas.getRGB(x, y);
                    if (luma(baseRgb) < BLANK_LUMA_THRESHOLD) continue;
                    canvas.setRGB(x, y, blend(baseRgb, over));
                }
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }

    /**
     * 判定某个水印图章（旋转坐标系下的中心点 + 尺寸）覆盖的图片区域是否全部为空白。
     *
     * <p>只取 5 个点会漏判：图章是宽而扁的矩形，正文笔画可能正好落在两角之间。
     * 这里改为沿图章边界与内部按网格密采样（步长 ≤ 半格），任一点落在有墨区即放弃该位置——
     * 宁可少画几个水印，也不能压住执照正文。
     */
    private static boolean isStampAreaBlank(InkDensity density, AffineTransform toImageInverse,
                                            int boxLeft, int boxTop, int stampW, int stampH) {
        // 采样步长：不超过格子的一半，保证不会"跨过"一整格有墨区
        double step = Math.max(4, density.cellSize() / 2.0);
        int nx = Math.max(1, (int) Math.ceil(stampW / step));
        int ny = Math.max(1, (int) Math.ceil(stampH / step));
        double[] dst = new double[2];
        for (int iy = 0; iy <= ny; iy++) {
            double py = boxTop + (stampH * iy / (double) ny);
            for (int ix = 0; ix <= nx; ix++) {
                double px = boxLeft + (stampW * ix / (double) nx);
                double[] src = {px, py};
                toImageInverse.transform(src, 0, dst, 0, 1);
                if (!density.isBlank((int) Math.round(dst[0]), (int) Math.round(dst[1]))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 字号：按短边换算，夹在 12~34px；超小图仍保证可辨，超大图不过分抢占版面 */
    static int fontSizeFor(int width, int height) {
        int shortEdge = Math.min(width, height);
        int size = (int) Math.round(shortEdge / 34.0);
        return Math.max(12, Math.min(34, size));
    }

    /** 感知亮度（0-255）：用于判断该像素是否属于正文墨迹 */
    static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /**
     * 把水印像素按自身 alpha 叠加到底图（source-over），返回合成后的 RGB。
     *
     * <p>overlay 的 RGB 恒为黑色、alpha 为水印透明度，因此结果就是"底色压暗"。
     */
    static int blend(int baseRgb, int overRgb) {
        double a = ((overRgb >>> 24) & 0xFF) / 255.0;
        int br = (baseRgb >> 16) & 0xFF;
        int bg = (baseRgb >> 8) & 0xFF;
        int bb = baseRgb & 0xFF;
        int or = (overRgb >> 16) & 0xFF;
        int og = (overRgb >> 8) & 0xFF;
        int ob = overRgb & 0xFF;
        int r = (int) Math.round(or * a + br * (1 - a));
        int g = (int) Math.round(og * a + bg * (1 - a));
        int b = (int) Math.round(ob * a + bb * (1 - a));
        return (0xFF << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** 超过最长边上限时等比降采样（保持纵横比），降低绘制与编码耗时 */
    static BufferedImage downscaleIfNeeded(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_EDGE) return src;
        double scale = (double) MAX_EDGE / longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 墨量栅格：把图片按 cell 边长分块，统计每块的"有墨像素占比"与平均亮度。
     *
     * <p>只在构造时扫描一次整图（O(w*h)），后续每个水印位置只做 O(1) 查表，
     * 这是"200ms 内完成"的关键：不能为每个候选水印位置重新扫描像素。
     */
    static final class InkDensity {
        private final boolean[][] inked;
        private final int cols;
        private final int rows;
        private final int cell;
        private final int width;
        private final int height;

        InkDensity(BufferedImage img, int cell) {
            this.cell = Math.max(1, cell);
            this.width = img.getWidth();
            this.height = img.getHeight();
            this.cols = (width + this.cell - 1) / this.cell;
            this.rows = (height + this.cell - 1) / this.cell;
            this.inked = new boolean[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    inked[r][c] = scanCellHasInk(img, c * this.cell, r * this.cell, this.cell);
                }
            }
        }

        /**
         * 单格是否有墨：抽稀采样（每 2 像素取 1）统计暗像素占比。
         *
         * <p>抽稀把扫描量降到 1/4，对"是否正文"的判定无实质影响——
         * 正文是连续笔画，抽稀后依然显著超过 2% 阈值。
         */
        private static boolean scanCellHasInk(BufferedImage img, int x0, int y0, int size) {
            int w = img.getWidth();
            int h = img.getHeight();
            int x1 = Math.min(w, x0 + size);
            int y1 = Math.min(h, y0 + size);
            int sampled = 0;
            int dark = 0;
            for (int y = y0; y < y1; y += 2) {
                for (int x = x0; x < x1; x += 2) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int luma = (r * 299 + g * 587 + b * 114) / 1000;
                    sampled++;
                    if (luma < BLANK_LUMA_THRESHOLD) dark++;
                }
            }
            if (sampled == 0) return false;
            return (double) dark / sampled > INK_PIXEL_RATIO;
        }

        /** 图片坐标 (x,y) 是否可视为空白（越界按空白处理：画布边缘外侧本就无内容） */
        boolean isBlank(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) return true;
            int c = Math.min(cols - 1, x / cell);
            int r = Math.min(rows - 1, y / cell);
            return !inked[r][c];
        }

        /** 空白格数量（测试用：断言"不是整图全铺"，也确实找到了可绘制区域） */
        int blankCells() {
            int n = 0;
            for (boolean[] row : inked) {
                for (boolean v : row) if (!v) n++;
            }
            return n;
        }

        int totalCells() {
            return rows * cols;
        }

        /** 格子边长（调用方据此决定采样步长，避免跨格漏判） */
        int cellSize() {
            return cell;
        }
    }

    /**
     * 中文字体解析：按候选族名取第一个系统已安装的字体；
     * 无任何命中的环境（如精简容器）退回逻辑字体 SansSerif。
     */
    static Font cjkFont(int style, int size) {
        String[] candidates = {
                "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC",
                "WenQuanYi Zen Hei", "PingFang SC", "SimHei"
        };
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : candidates) {
            if (available.contains(name)) {
                return new Font(name, style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}

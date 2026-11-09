package com.lanlink.shopping.service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 营业执照查看水印：多行斜排半透明平铺，铺满全图。
 *
 * <p>服务端绘制（对齐 {@link com.lanlink.shopping.module.marketingimage.MarketingImageWatermark} 的
 * 立场）：水印必须烙进像素流，前端 CSS 叠加保存原图即失效。仅 JDK 自带 Java2D，零新依赖。
 * 输出统一 PNG，且每次查看实时重绘（不做缓存），保证水印文字携带当次查看方身份。
 */
public final class LicenseImageWatermark {

    /** 斜排角度（约 -30°）与半透明 alpha（0.18-0.25 区间取 0.20）：可辨但不遮蔽执照信息 */
    private static final double ROTATE_DEG = -30.0;
    private static final int TEXT_ALPHA = 52; // ≈0.20 * 255
    private static final Color TEXT_COLOR = new Color(0, 0, 0, TEXT_ALPHA);

    private LicenseImageWatermark() {
    }

    public static byte[] applyPng(byte[] original, String[] lines) throws IOException {
        if (lines == null || lines.length == 0) {
            throw new IllegalArgumentException("水印文字不能为空");
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
        if (src == null) {
            throw new IOException("无法识别的图片格式");
        }
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(TEXT_COLOR);

            int fontSize = Math.max(14, Math.min(30, Math.min(width, height) / 22));
            g.setFont(cjkFont(Font.PLAIN, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int lineH = fm.getHeight() + 6;
            int stampH = lineH * lines.length;
            int stampW = 0;
            for (String line : lines) {
                stampW = Math.max(stampW, fm.stringWidth(line));
            }
            // 平铺步距：图章间距取字号 2 倍，保证网格铺满又不至于密不透风
            int stepX = stampW + fontSize * 2;
            int stepY = stampH + fontSize * 3;

            // 以画布中心为原点旋转后，在外扩对角线范围内平铺；裁剪由画布边界完成
            g.translate(width / 2.0, height / 2.0);
            g.rotate(Math.toRadians(ROTATE_DEG));
            double diag = Math.sqrt((double) width * width + (double) height * height);
            int span = (int) Math.ceil(diag / Math.min(stepX, stepY)) + 1;
            for (int i = -span; i <= span; i++) {
                for (int j = -span; j <= span; j++) {
                    int cx = i * stepX;
                    int cy = j * stepY;
                    int y = cy - stampH / 2 + fm.getAscent();
                    for (String line : lines) {
                        int lw = fm.stringWidth(line);
                        g.drawString(line, (float) (cx - lw / 2.0), y);
                        y += lineH;
                    }
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
     * 中文字体解析：按候选族名取第一个系统已安装的字体；
     * 无任何命中的环境（如精简容器）退回逻辑字体 SansSerif——该环境下逻辑字体同样缺 CJK 字形，
     * 属部署面字体缺包问题，需在镜像层安装字体（见 Dockerfile），不属于代码层可解。
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

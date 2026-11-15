package com.lanlink.shopping.module.marketingimage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 生成图片水印：右下角半透明黑底 + 白色「AI 生成」。
 *
 * <p>为什么必须由服务端绘制：合规要求 AI 生成内容对最终消费者可识别，不能依赖
 * 前端 CSS 叠加（用户保存原图后水印就丢了）。只用 JDK 自带的 Java2D，不新增图像库。
 */
final class MarketingImageWatermark {

    private static final String TEXT = "AI 生成";

    private MarketingImageWatermark() {
    }

    static byte[] applyPng(byte[] original) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
        if (src == null) {
            throw new IOException("无法识别的图片格式");
        }
        int width = src.getWidth();
        int height = src.getHeight();
        // ARGB 画布：保留原图透明通道，输出统一为 PNG
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int fontSize = Math.max(10, Math.min(40, width / 28));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int textWidth = fm.stringWidth(TEXT);
            int textHeight = fm.getHeight();

            int paddingX = Math.max(6, fontSize / 3);
            int paddingY = Math.max(4, fontSize / 5);
            int margin = Math.max(6, Math.min(width, height) / 40);
            int boxWidth = Math.min(width, textWidth + paddingX * 2);
            int boxHeight = Math.min(height, textHeight + paddingY * 2);
            // 小图上 margin 可能大于图片尺寸，用 max(0) 兜底，保证坐标不越界
            int x = Math.max(0, width - boxWidth - margin);
            int y = Math.max(0, height - boxHeight - margin);

            g.setColor(new Color(0, 0, 0, 150));
            int arc = Math.max(6, fontSize / 2);
            g.fillRoundRect(x, y, boxWidth, boxHeight, arc, arc);
            g.setColor(new Color(255, 255, 255, 235));
            g.drawString(TEXT, x + paddingX, y + paddingY + fm.getAscent());
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        return out.toByteArray();
    }
}

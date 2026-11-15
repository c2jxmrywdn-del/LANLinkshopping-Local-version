package com.lanlink.shopping.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 源文件一致性：水印只在内存中叠加，磁盘上的营业执照原件必须逐字节不变。
 *
 * <p>这是验收里"原始文件一致性验证"的可执行证据：
 * 用真实文件走一遍 read → watermark → 落盘输出，再比对源文件哈希与 mtime。
 */
class LicenseSourceFileImmutabilityTest {

    private static final String[] LINES = {
            "仅供测试运营在LANLINKShopping对外展示时使用",
            "最终解释权归属测试运营所有"
    };

    private static byte[] sampleLicensePng() throws Exception {
        int w = 900, h = 620;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            g.drawString("营业执照 (副本)", 60, 80);
            g.drawString("统一社会信用代码 91310000MA1K000000", 60, 120);
            g.drawString("名称 重庆建工实业集团有限公司", 60, 160);
            g.setColor(new Color(190, 30, 30));
            g.fillOval(620, 380, 180, 180);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    void watermarkingDoesNotTouchSourceFileOnDisk() throws Exception {
        Path dir = Files.createTempDirectory("license-src-");
        Path source = dir.resolve("license.png");
        try {
            Files.write(source, sampleLicensePng());

            byte[] beforeBytes = Files.readAllBytes(source);
            String beforeHash = sha256(beforeBytes);
            long beforeMtime = Files.getLastModifiedTime(source).toMillis();
            long beforeSize = Files.size(source);

            // 模拟生产链路：读源文件 → 内存加水印 → 输出到别处
            byte[] original = Files.readAllBytes(source);
            byte[] output = LicenseImageWatermark.applyPng(original, LINES);
            assertTrue(output.length > 0, "必须产出带水印图片");

            byte[] afterBytes = Files.readAllBytes(source);
            assertEquals(beforeHash, sha256(afterBytes), "源文件哈希必须完全一致");
            assertArrayEquals(beforeBytes, afterBytes, "源文件字节必须完全一致");
            assertEquals(beforeSize, Files.size(source), "源文件大小不得变化");
            assertEquals(beforeMtime, Files.getLastModifiedTime(source).toMillis(),
                    "源文件 mtime 不得变化（说明没有被写回）");

            // 输出与原图必须不同，证明水印确实加上了
            assertNotEquals(beforeHash, sha256(output), "输出图必须与原图不同（已加水印）");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void repeatedWatermarkingKeepsSourceStable() throws Exception {
        Path dir = Files.createTempDirectory("license-src-");
        Path source = dir.resolve("license.png");
        try {
            Files.write(source, sampleLicensePng());
            String hash = sha256(Files.readAllBytes(source));

            for (int i = 0; i < 3; i++) {
                LicenseImageWatermark.applyPng(Files.readAllBytes(source), LINES);
            }

            assertEquals(hash, sha256(Files.readAllBytes(source)),
                    "多次输出后源文件仍必须保持一致");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(dir);
        }
    }
}

package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;
import net.coobird.thumbnailator.Thumbnails;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 附件缩略图渲染（纯函数，不碰存储与数据库，便于单测）。
 *
 * <p>放在消费侧异步执行而不是上传线程里：一张 20MP 手机照片解码+缩放要几百毫秒到数秒，
 * 串在上传请求里会把"提交材料"这件事的成败绑在一个可有可无的预览上。
 *
 * <p>输出统一 JPEG：JDK 的 JPEG writer 遇到带 alpha 的 4 通道图会抛 "Bogus input colorspace"，
 * 所以带透明通道的源图先铺白底再编码；预览本来也不需要透明。
 */
public final class AttachmentThumbnails {

    /** 长边上限：列表页预览够用，又不会让缩略图本身变成负担 */
    public static final int MAX_EDGE = 512;
    private static final double OUTPUT_QUALITY = 0.82;
    /**
     * 像素总量上限（解压炸弹护栏）：文件不大但解码后是几亿像素的 PNG 会让堆直接爆掉，
     * 这一层必须在分配 BufferedImage 之前按声明尺寸拦一次。
     */
    public static final long MAX_SOURCE_PIXELS = 40_000_000L;

    public record Result(byte[] content, int width, int height, String contentType) {
    }

    private AttachmentThumbnails() {
    }

    /** 该类型能不能出缩略图：PDF 没有预览语义，WebP 在 JDK 里没有解码器（ImageIO 直接返回 null）。 */
    public static boolean supports(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.trim().toLowerCase();
        return "image/jpeg".equals(ct) || "image/png".equals(ct);
    }

    /**
     * @throws IOException    源图解不出来（截断、伪造后缀、不支持的编码）
     * @throws BusinessException 像素规模超限
     */
    public static Result render(byte[] source) throws IOException {
        if (source == null || source.length == 0) {
            throw new IOException("源文件为空");
        }
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) {
            throw new IOException("图像无法解码，可能是伪造后缀或文件截断");
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        if ((long) width * height > MAX_SOURCE_PIXELS) {
            throw new BusinessException(400, "图片像素规模超限，不做预览：" + width + "x" + height);
        }
        BufferedImage flat = flattenOnWhite(decoded);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(flat).size(MAX_EDGE, MAX_EDGE).keepAspectRatio(true)
                .outputFormat("jpg").outputQuality(OUTPUT_QUALITY).toOutputStream(out);
        return new Result(out.toByteArray(), width, height, "image/jpeg");
    }

    private static BufferedImage flattenOnWhite(BufferedImage src) {
        if (src.getType() != BufferedImage.TYPE_INT_ARGB
                && src.getType() != BufferedImage.TYPE_4BYTE_ABGR
                && src.getType() != BufferedImage.TYPE_INT_ARGB_PRE
                && src.getType() != BufferedImage.TYPE_4BYTE_ABGR_PRE) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return rgb;
    }
}

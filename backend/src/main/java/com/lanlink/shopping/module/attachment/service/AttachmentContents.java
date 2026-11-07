package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;

/**
 * 按魔数判定材料类型。
 *
 * <p>扩展名与 Content-Type 一律由内容推导，不信任客户端声明也不信任原始文件名的后缀：
 * 伪造 contentType 与「.pdf 里装 HTML」都在这条线上被挡掉，svg 因可携带脚本被排除在材料白名单外。
 */
public final class AttachmentContents {

    public record Detected(String extension, String contentType) {
    }

    private AttachmentContents() {
    }

    public static Detected sniff(byte[] content) {
        if (content == null || content.length < 8) {
            throw new BusinessException(400, "文件内容为空或已损坏");
        }
        int b0 = content[0] & 0xFF, b1 = content[1] & 0xFF, b2 = content[2] & 0xFF, b3 = content[3] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return new Detected("jpg", "image/jpeg");
        }
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return new Detected("png", "image/png");
        }
        if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && content.length >= 12 && content[8] == 'W' && content[9] == 'E'
                && content[10] == 'B' && content[11] == 'P') {
            return new Detected("webp", "image/webp");
        }
        if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') {
            return new Detected("pdf", "application/pdf");
        }
        throw new BusinessException(400, "仅支持 JPG/PNG/WebP/PDF 材料");
    }
}

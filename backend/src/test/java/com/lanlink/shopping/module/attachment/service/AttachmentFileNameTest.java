package com.lanlink.shopping.module.attachment.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展示文件名的净化边界。
 *
 * <p>路径段必须先剥掉；剥完之后再谈 ".." 已无法逃逸，所以不能一刀切删掉所有连续点，
 * 那会误伤 "合同..v2" 这类合法名称。截断按码点做，避免把代理对切成孤立代理项写进库和 JSON。
 */
class AttachmentFileNameTest {

    @Test
    void keepsLegitDoubleDotNamesAfterStrippingPathSegments() {
        assertEquals("合同..v2.pdf",
                AttachmentService.sanitizeFileName("/var/tmp/合同..v2.pdf", "pdf"));
    }

    @Test
    void truncatesOnCodePointBoundaryWithoutLoneSurrogate() {
        // 前导 1 个 BMP 字符把截断点顶到代理对中间：按 UTF-16 单元截就会留下孤立高代理项
        String name = "x" + "📄".repeat(150) + ".pdf";

        String out = AttachmentService.sanitizeFileName(name, "pdf");

        assertTrue(out.codePointCount(0, out.length()) <= 200, "长度应按码点收敛");
        assertFalse(Character.isHighSurrogate(out.charAt(out.length() - 1)),
                "结尾不能是孤立高代理项，否则写库与 JSON 序列化会炸");
    }

    @Test
    void fallsBackToDerivedExtensionWhenNameIsEmpty() {
        assertEquals("material.pdf", AttachmentService.sanitizeFileName("   ", "pdf"));
        assertEquals("material.pdf", AttachmentService.sanitizeFileName(null, "pdf"));
    }
}

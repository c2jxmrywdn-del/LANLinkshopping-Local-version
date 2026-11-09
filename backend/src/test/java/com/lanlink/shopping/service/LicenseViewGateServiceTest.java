package com.lanlink.shopping.service;

import com.lanlink.shopping.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LicenseViewGateServiceTest {

    private AtomicLong now;
    private LicenseViewGateService service;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000L);
        service = new LicenseViewGateService(5 * 60 * 1000L, 5 * 60 * 1000L, now::get);
    }

    // ===== 水印文字模板 =====

    @Test
    void watermarkTextNormalNames() {
        String[] lines = LicenseViewGateService.buildWatermarkLines("云链科技", "星空贸易");
        assertEquals(2, lines.length);
        assertEquals("仅供云链科技对外展示时使用", lines[0]);
        assertEquals("最终解释权归属星空贸易所有", lines[1]);
    }

    @Test
    void watermarkTextFallsBackWhenViewerBlank() {
        // 查看方为空（无企业名且无昵称）→ 回退平台运营占位，模板不能出现空洞
        String[] lines = LicenseViewGateService.buildWatermarkLines("  ", "星空贸易");
        assertEquals("仅供LANLink 平台运营对外展示时使用", lines[0]);
        assertEquals("最终解释权归属星空贸易所有", lines[1]);
        String[] nullLines = LicenseViewGateService.buildWatermarkLines(null, "星空贸易");
        assertEquals("仅供LANLink 平台运营对外展示时使用", nullLines[0]);
    }

    @Test
    void watermarkTextFallsBackWhenMerchantBlank() {
        String[] lines = LicenseViewGateService.buildWatermarkLines("云链科技", "");
        assertEquals("最终解释权归属对应商户所有", lines[1]);
    }

    // ===== 验证码 =====

    @Test
    void captchaIssueAndCorrectCodeIssuesTicket() {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(1L);
        assertNotNull(issue.captchaId());
        assertTrue(issue.imageBase64().startsWith("data:image/png;base64,"));
        String ticket = service.issueTicket(9L, 1L, issue.captchaId(), service.codeOf(issue.captchaId()));
        assertNotNull(ticket);
        assertFalse(ticket.isBlank());
    }

    @Test
    void captchaWrongCodeRejectedAndCountsAttempts() {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(1L);
        BusinessException first = assertThrows(BusinessException.class,
                () -> service.issueTicket(9L, 1L, issue.captchaId(), "XXXX"));
        assertTrue(first.getMessage().contains("验证码错误"));
        // 第 1 次错码在上，这里再错 4 次，第 5 次触发作废
        BusinessException last = null;
        for (int i = 0; i < 4; i++) {
            last = assertThrows(BusinessException.class,
                    () -> service.issueTicket(9L, 1L, issue.captchaId(), "XXXX"));
        }
        assertNotNull(last);
        assertTrue(last.getMessage().contains("超限"), "第 5 次错码应作废，实际：" + last.getMessage());
        // 作废后该验证码整体失效（码仍错——真值已随作废移除）
        BusinessException after = assertThrows(BusinessException.class,
                () -> service.issueTicket(9L, 1L, issue.captchaId(), "XXXX"));
        assertTrue(after.getMessage().contains("失效"));
    }

    @Test
    void captchaSingleUse() {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(1L);
        service.issueTicket(9L, 1L, issue.captchaId(), service.codeOf(issue.captchaId()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.issueTicket(9L, 1L, issue.captchaId(), service.codeOf(issue.captchaId())));
        assertTrue(ex.getMessage().contains("已被使用"));
    }

    @Test
    void captchaBoundToMerId() {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.issueTicket(9L, 2L, issue.captchaId(), service.codeOf(issue.captchaId())));
        assertTrue(ex.getMessage().contains("不匹配"));
    }

    @Test
    void captchaExpiredRejected() {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(1L);
        now.addAndGet(5 * 60 * 1000L + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.issueTicket(9L, 1L, issue.captchaId(), service.codeOf(issue.captchaId())));
        // 过期盒可能已被后台清扫（报「失效」）或仍在命中逐盒检查（报「过期」），语义均为拒绝
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("过期") || ex.getMessage().contains("失效"));
    }

    // ===== 票据 =====

    private String ticketFor(long userId, long merId) {
        LicenseViewGateService.CaptchaIssue issue = service.newCaptcha(merId);
        return service.issueTicket(userId, merId, issue.captchaId(), service.codeOf(issue.captchaId()));
    }

    @Test
    void ticketConsumeOnceThenReplayRejected() {
        String ticket = ticketFor(9L, 1L);
        assertDoesNotThrow(() -> service.consumeTicket(9L, 1L, ticket));
        // 重放：单次使用语义下第二次消费必须拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consumeTicket(9L, 1L, ticket));
        assertTrue(ex.getMessage().contains("已被使用"));
    }

    @Test
    void ticketWrongMerIdRejected() {
        String ticket = ticketFor(9L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consumeTicket(9L, 2L, ticket));
        assertTrue(ex.getMessage().contains("不匹配"));
        // 错商户的票据未被消费：原商户仍可用
        assertDoesNotThrow(() -> service.consumeTicket(9L, 1L, ticket));
    }

    @Test
    void ticketWrongUserRejected() {
        String ticket = ticketFor(9L, 1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consumeTicket(8L, 1L, ticket));
        assertTrue(ex.getMessage().contains("不匹配"));
    }

    @Test
    void ticketExpiredRejected() {
        String ticket = ticketFor(9L, 1L);
        now.addAndGet(5 * 60 * 1000L + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.consumeTicket(9L, 1L, ticket));
        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("过期") || ex.getMessage().contains("无效"));
    }

    @Test
    void ticketMissingOrUnknownRejected() {
        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.consumeTicket(9L, 1L, ""));
        assertTrue(missing.getMessage().contains("缺少查看凭证"));
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.consumeTicket(9L, 1L, "not-a-ticket"));
        assertTrue(unknown.getMessage().contains("无效"));
    }

    // ===== 水印图渲染（PNG 结构级校验） =====

    @Test
    void watermarkPaintProducesDecodablePng() throws Exception {
        java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = src.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(src, "png", bos);
        byte[] out = LicenseImageWatermark.applyPng(bos.toByteArray(),
                LicenseViewGateService.buildWatermarkLines("云链科技", "星空贸易"));
        java.awt.image.BufferedImage result = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(out));
        assertNotNull(result, "水印输出必须为可解码 PNG");
        assertEquals(600, result.getWidth());
        assertEquals(400, result.getHeight());
    }
}

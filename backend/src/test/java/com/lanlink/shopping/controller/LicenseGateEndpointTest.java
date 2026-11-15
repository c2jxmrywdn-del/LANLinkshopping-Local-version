package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.service.LicenseViewGateService;
import com.lanlink.shopping.service.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 营业执照查看端点的门控契约：未验证一律拦截，且不泄露票据状态差异。
 *
 * <p>直接驱动控制器方法（不启 Spring），断言真实 HTTP 状态码与统一错误文案——
 * 这是"禁止任何未验证请求触达图片资源"的可执行证据。
 */
class LicenseGateEndpointTest {

    private MerchantService merchantService;
    private LicenseViewGateService gate;
    private MerchantController controller;

    @BeforeEach
    void setUp() {
        merchantService = mock(MerchantService.class);
        gate = mock(LicenseViewGateService.class);
        controller = new MerchantController(merchantService, gate);
    }

    private MockHttpServletRequest requestOf(User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (user != null) {
            request.getSession(true)
                    .setAttribute(com.lanlink.shopping.config.UserContext.SESSION_KEY, user);
        }
        return request;
    }

    private User user(Long id, Long roleId) {
        User u = new User();
        u.setUserId(id);
        u.setRoleId(roleId);
        u.setNickname("测试账号");
        return u;
    }

    private Merchant merchant(long merId, Long userId, String licenseUrl) {
        Merchant m = new Merchant();
        m.setMerId(merId);
        m.setUserId(userId);
        m.setLicenseUrl(licenseUrl);
        m.setEntId(merId * 10);
        return m;
    }

    private String body(ResponseEntity<byte[]> resp) {
        return resp.getBody() == null ? "" : new String(resp.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void merchantEndpointRejectsWhenTicketMissing() {
        when(merchantService.getByUser(1L)).thenReturn(merchant(10L, 1L, "/api/uploads/licenses/a.png"));
        doThrow(new BusinessException(403, "缺少查看凭证"))
                .when(gate).consumeTicket(eq(1L), eq(10L), isNull());

        ResponseEntity<byte[]> resp = controller.myLicenseWatermarked(null, requestOf(user(1L, 2L)));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(), "未带票据必须 403");
        assertTrue(body(resp).contains("请先完成人机验证"), "必须返回统一提示: " + body(resp));
        verify(merchantService, never()).merchantDisplayName(any());
    }

    @Test
    void merchantEndpointHidesTicketStateDifference() {
        when(merchantService.getByUser(1L)).thenReturn(merchant(10L, 1L, "/api/uploads/licenses/a.png"));

        String[] reasons = {"查看凭证已过期", "查看凭证已被使用", "查看凭证与当前账号或商户不匹配"};
        for (String reason : reasons) {
            doThrow(new BusinessException(403, reason))
                    .when(gate).consumeTicket(eq(1L), eq(10L), eq("t"));
            ResponseEntity<byte[]> resp = controller.myLicenseWatermarked("t", requestOf(user(1L, 2L)));
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
            assertTrue(body(resp).contains("请先完成人机验证"),
                    "对外不得暴露具体原因（" + reason + "），实际: " + body(resp));
        }
    }

    @Test
    void merchantEndpointRejectsAnonymous() {
        ResponseEntity<byte[]> resp = controller.myLicenseWatermarked("t", requestOf(null));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        verifyNoInteractions(gate);
    }

    @Test
    void merchantEndpointRejectsWhenNoMerchantProfile() {
        when(merchantService.getByUser(1L)).thenReturn(null);
        ResponseEntity<byte[]> resp = controller.myLicenseWatermarked("t", requestOf(user(1L, 2L)));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        verifyNoInteractions(gate);
    }

    @Test
    void merchantCaptchaAlwaysBindsToOwnMerId() {
        when(merchantService.getByUser(1L)).thenReturn(merchant(10L, 1L, "/x.png"));
        when(gate.newCaptcha(anyLong()))
                .thenReturn(new LicenseViewGateService.CaptchaIssue("cid", "data:image/png;base64,AAA"));

        controller.myLicenseCaptcha(requestOf(user(1L, 2L)));

        verify(gate).newCaptcha(10L);
    }

    @Test
    void merchantVerifyIgnoresClientSuppliedMerId() {
        when(merchantService.getByUser(1L)).thenReturn(merchant(10L, 1L, "/x.png"));
        when(gate.issueTicket(anyLong(), anyLong(), anyString(), anyString())).thenReturn("ticket");

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("merId", 999L);
        body.put("captchaId", "cid");
        body.put("captchaCode", "ABCD");
        controller.myLicenseVerify(body, requestOf(user(1L, 2L)));

        verify(gate).issueTicket(1L, 10L, "cid", "ABCD");
        verify(gate, never()).issueTicket(anyLong(), eq(999L), anyString(), anyString());
    }

    @Test
    void adminEndpointUsesSameUnifiedMessage() {
        doThrow(new BusinessException(403, "查看凭证已过期"))
                .when(gate).consumeTicket(eq(9L), eq(10L), eq("bad"));

        ResponseEntity<byte[]> resp = controller.licenseWatermarked(10L, "bad",
                requestOf(user(9L, 3L)));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(body(resp).contains("请先完成人机验证"), body(resp));
        assertFalse(body(resp).contains("已过期"), "运营端同样不得回显内部原因");
    }

    @Test
    void returnsNotFoundWhenLicenseMissing() {
        when(merchantService.getByUser(1L)).thenReturn(merchant(10L, 1L, null));
        doNothing().when(gate).consumeTicket(eq(1L), eq(10L), eq("t"));

        ResponseEntity<byte[]> resp = controller.myLicenseWatermarked("t", requestOf(user(1L, 2L)));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertTrue(body(resp).contains("暂未上传营业执照"), body(resp));
    }

    /**
     * 权属主体不可伪造：商户企业名缺失时必须拒绝出图，
     * 不能回退成「商户#10」之类的合成名，也不能输出无水印原图。
     */
    @Test
    void rejectsWhenMerchantCompanyNameMissing() throws Exception {
        // 造一份真实可读的执照文件，确保能走到水印文案生成（而不是提前 404 文件缺失）
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "uploads", "licenses");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Path png = dir.resolve("gate-test-" + java.util.UUID.randomUUID() + ".png");
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(80, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 80, 60);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", png.toFile());
        try {
            when(merchantService.getByUser(1L))
                    .thenReturn(merchant(10L, 1L, "/api/uploads/licenses/" + png.getFileName()));
            doNothing().when(gate).consumeTicket(eq(1L), eq(10L), eq("t"));
            when(merchantService.merchantDisplayName(any())).thenReturn(null);
            when(merchantService.adminDisplayName(1L)).thenReturn("平台运营");

            ResponseEntity<byte[]> resp = controller.myLicenseWatermarked("t", requestOf(user(1L, 2L)));

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode(), "缺公司名必须拒绝出图");
            assertTrue(body(resp).contains("商户企业名称缺失"), body(resp));
            assertNotEquals(org.springframework.http.MediaType.IMAGE_PNG, resp.getHeaders().getContentType(),
                    "拒绝时不得返回图片内容类型");
            assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, resp.getHeaders().getContentType());
        } finally {
            java.nio.file.Files.deleteIfExists(png);
        }
    }

    /**
     * 端到端文案契约：控制器交给水印引擎的两行文字，
     * 第二行必须逐字是「最终解释权归属{商户公司名}所有」。
     */
    @Test
    void watermarkLinesAnchorOwnershipToMerchantCompany() {
        when(merchantService.adminDisplayName(9L)).thenReturn("平台运营");

        String[] lines = controller.watermarkLinesFor("华晨建材集团有限公司", 9L);

        assertEquals("仅供平台运营在LANLINKShopping对外展示时使用", lines[0]);
        assertEquals("最终解释权归属华晨建材集团有限公司所有", lines[1],
                "权属行必须逐字等于商户公司名，不得替换为查看方");
    }
}

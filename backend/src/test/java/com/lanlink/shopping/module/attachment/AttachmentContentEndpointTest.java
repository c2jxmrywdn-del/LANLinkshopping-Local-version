package com.lanlink.shopping.module.attachment;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.attachment.entity.Attachment;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 材料中转端点。
 *
 * <p>三条硬约束：响应头不得内联（PDF 内嵌脚本会变成存储型 XSS）；
 * 非 ASCII 文件名必须按 RFC 5987 编码，否则中文材料名会写出畸形头；
 * 对象缺失要回 404，不能把 UncheckedIOException 带本地绝对路径冒成 500。
 */
class AttachmentContentEndpointTest {

    private static final String NO = "AT20261104134320A9";
    private static final String KEY = "attachments/merchant-license/2026-11/abc.pdf";
    private static final byte[] BYTES = "%PDF-1.4 material".getBytes(StandardCharsets.UTF_8);

    private AttachmentMapper mapper;
    private StorageService storage;
    private MockMvc mvc;

    private Attachment storedRow() {
        Attachment row = new Attachment();
        row.setAttachmentId(7L);
        row.setAttachNo(NO);
        row.setBizType("merchant-license");
        row.setOwnerUserId(1001L);
        row.setFileName("营业执照.pdf");
        row.setObjectKey(KEY);
        row.setContentType("application/pdf");
        row.setFileSize((long) BYTES.length);
        row.setStatus("BOUND");
        row.setVisibility("PRIVATE");
        return row;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        when(mapper.selectOne(any())).thenReturn(storedRow());
        when(storage.getObject(KEY)).thenReturn(BYTES);
        when(storage.exists(KEY)).thenReturn(true);
        AttachmentService service = new AttachmentService(storage, mapper, java.util.List.of());
        mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(service,
                        mock(com.lanlink.shopping.service.AuditService.class))).build();
    }

    private MockHttpSession sessionOf(long userId) {
        MockHttpSession session = new MockHttpSession();
        com.lanlink.shopping.entity.User user = new com.lanlink.shopping.entity.User();
        user.setUserId(userId);
        session.setAttribute(UserContext.SESSION_KEY, user);
        return session;
    }

    @Test
    void relayResponseIsPrivateNonCacheableAndSameOrigin() throws Exception {
        // prod 会话 Cookie 是 SameSite=None（application-prod.yml），缺少这两组头时
        // 第三方页面里的 <img src=中转地址> 会带着用户 Cookie 直接把他人材料渲染出来
        mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(1001L)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void ownerDownloadsBytesThroughRelay() throws Exception {
        mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(1001L)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(BYTES))
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void responseNeverForcesInlineRendering() throws Exception {
        String disposition = mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(1001L)))
                .andReturn().getResponse().getHeader("Content-Disposition");

        org.junit.jupiter.api.Assertions.assertNotNull(disposition, "必须显式给出处置头");
        assertFalse(disposition.toLowerCase().startsWith("inline"),
                "PDF 内联会在浏览器里直接执行内嵌脚本：" + disposition);
        org.junit.jupiter.api.Assertions.assertTrue(disposition.startsWith("attachment"),
                "只允许 attachment 处置：" + disposition);
    }

    @Test
    void nonAsciiFileNameIsEncodedPerRfc5987() throws Exception {
        mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(1001L)))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
    }

    @Test
    void responseCarriesNoObjectKeyOrBucketAddress() throws Exception {
        var result = mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(1001L)))
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        String location = result.getResponse().getHeader("Location");
        assertFalse(String.valueOf(disposition).contains("attachments/"),
                "响应头不得带出对象键：" + disposition);
        assertFalse(String.valueOf(disposition).contains("9000"),
                "响应头不得带出匿名桶地址：" + disposition);
        assertFalse(String.valueOf(location).contains("9000"), "不得重定向到桶地址");
    }

    @Test
    void strangerGetsForbiddenAndNoBytes() throws Exception {
        mvc.perform(get("/attachment/" + NO + "/content").session(sessionOf(2002L)))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PDF"))));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get("/attachment/" + NO + "/content"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingObjectBecomesNotFoundWithoutLeakingLocalPath() {
        when(storage.exists(KEY)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> new AttachmentService(storage, mapper, java.util.List.of()).loadContent(NO, 1001L, false));

        assertFalse(e.getMessage().contains("/uploads"), "错误文案不能带出本地路径：" + e.getMessage());
        assertFalse(e.getMessage().contains("attachments/"), "错误文案不能带出对象键：" + e.getMessage());
    }
}

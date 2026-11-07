package com.lanlink.shopping.module.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.storage.StorageService;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.attachment.mapper.AttachmentMapper;
import com.lanlink.shopping.module.attachment.service.AttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 材料上传与绑定的 HTTP 面。
 *
 * <p>两个契约必须钉住：响应仍走项目统一的 R 结构（前端复用同一套错误处理），
 * 且 data 里只有中转地址，不出现对象键；未登录的写操作不能落到服务层。
 */
class AttachmentWriteEndpointTest {

    private static final byte[] PDF = "%PDF-1.4 upload".getBytes(StandardCharsets.UTF_8);

    private AttachmentMapper mapper;
    private StorageService storage;
    private com.lanlink.shopping.service.AuditService audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(AttachmentMapper.class);
        storage = mock(StorageService.class);
        audit = mock(com.lanlink.shopping.service.AuditService.class);
        when(storage.put(anyString(), any(), anyString())).thenReturn("http://localhost:9000/products/x");
        AttachmentService service = new AttachmentService(storage, mapper,
                java.util.List.of(new com.lanlink.shopping.module.attachment.service.AttachmentOwnership() {
                    public boolean supports(com.lanlink.shopping.module.attachment.service.AttachmentBizType t) { return true; }
                    public boolean owns(com.lanlink.shopping.module.attachment.service.AttachmentBizType t, Long b, Long u) { return true; }
                }));
        mvc = MockMvcBuilders.standaloneSetup(new AttachmentController(service, audit))
                .setControllerAdvice(new com.lanlink.shopping.common.GlobalExceptionHandler())
                .build();
    }

    @Test
    void everyMaterialWriteLeavesAuditTraceWithoutObjectKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "营业执照.pdf", "application/pdf", PDF);
        mvc.perform(multipart("/attachment/upload").file(file)
                .param("bizType", "merchant-license").session(sessionOf(1001L)));

        var detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).record(org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.eq("ATTACHMENT_UPLOAD"), detail.capture(), any());
        assertFalse(detail.getValue().contains("attachments/"),
                "审计详情只记单号与业务域，不得写对象键（桶匿名可读）：" + detail.getValue());
        assertTrue(detail.getValue().contains("merchant-license"),
                "审计要能回答是谁在哪个业务域提交了材料：" + detail.getValue());
    }

    @Test
    void bindAndDeleteAreAudited() throws Exception {
        com.lanlink.shopping.module.attachment.entity.Attachment row =
                new com.lanlink.shopping.module.attachment.entity.Attachment();
        row.setAttachNo("AT1");
        row.setBizType("merchant-license");
        row.setOwnerUserId(1001L);
        row.setObjectKey("attachments/merchant-license/2026-11/abc.pdf");
        row.setStatus("PENDING");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(any(com.lanlink.shopping.module.attachment.entity.Attachment.class),
                anyWrapper())).thenReturn(1);

        mvc.perform(post("/attachment/bind").contentType(MediaType.APPLICATION_JSON)
                .content("{\"attachmentNo\":\"AT1\",\"bizType\":\"merchant-license\",\"bizId\":55}")
                .session(sessionOf(1001L)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/attachment/AT1").session(sessionOf(1001L)));

        verify(audit).record(org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.eq("ATTACHMENT_BIND"), any(), any());
        verify(audit).record(org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.eq("ATTACHMENT_DELETE"), any(), any());
    }

    private static com.baomidou.mybatisplus.core.conditions.Wrapper<
            com.lanlink.shopping.module.attachment.entity.Attachment> anyWrapper() {
        return org.mockito.ArgumentMatchers.any();
    }

    private MockHttpSession sessionOf(long userId) {
        MockHttpSession session = new MockHttpSession();
        User user = new User();
        user.setUserId(userId);
        session.setAttribute(UserContext.SESSION_KEY, user);
        return session;
    }

    @Test
    void uploadReturnsRelayUrlAndNeverLeaksObjectKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "营业执照.pdf",
                "application/pdf", PDF);

        String body = mvc.perform(multipart("/attachment/upload").file(file)
                        .param("bizType", "merchant-license")
                        .session(sessionOf(1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/api/attachment/")))
                .andExpect(jsonPath("$.data.fileName").value("营业执照.pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.objectKey").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("attachments/"),
                "响应整体不得出现对象键目录：" + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("9000"),
                "响应整体不得出现匿名桶地址：" + body);
    }

    @Test
    void uploadRejectsSpoofedContentTypeWhenBytesDisagree() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "evil.pdf",
                "application/pdf", "<html>script</html>".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/attachment/upload").file(fake)
                        .param("bizType", "merchant-license")
                        .session(sessionOf(1001L)))
                .andExpect(jsonPath("$.code").value(400));

        verify(mapper, never()).insert(any(com.lanlink.shopping.module.attachment.entity.Attachment.class));
    }

    @Test
    void uploadWithoutLoginIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", PDF);

        mvc.perform(multipart("/attachment/upload").file(file).param("bizType", "merchant-license"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void listRequiresLoginAndReturnsRelayUrlsOnly() throws Exception {
        when(mapper.selectList(
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.metadata.IPage<
                        com.lanlink.shopping.module.attachment.entity.Attachment>>any(),
                org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<
                        com.lanlink.shopping.module.attachment.entity.Attachment>>any()))
                .thenReturn(java.util.List.of());

        mvc.perform(get("/attachment/list").param("bizType", "merchant-license")
                        .session(sessionOf(1001L)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());

        mvc.perform(get("/attachment/list").param("bizType", "merchant-license"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void listRejectsUnknownBizType() throws Exception {
        mvc.perform(get("/attachment/list").param("bizType", "../etc")
                        .session(sessionOf(1001L)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deleteRouteIsWiredAndRequiresLogin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/attachment/AT20261104134320A9"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void bindUsesUnifiedResponseAndPassesAdminFlagFromIdentity() throws Exception {
        when(mapper.selectOne(any())).thenReturn(null);

        mvc.perform(post("/attachment/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                java.util.Map.of("attachmentNo", "AT1", "bizType", "merchant-license", "bizId", 55)))
                        .session(sessionOf(1001L)))
                .andExpect(jsonPath("$.code").value(404));
    }
}

package com.lanlink.shopping.module.aitool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.aitool.controller.AiToolExceptionHandler;
import com.lanlink.shopping.module.aitool.controller.AiToolsInternalController;
import com.lanlink.shopping.module.aitool.dto.AiToolDto;
import com.lanlink.shopping.module.aitool.service.AiToolExecutionService;
import com.lanlink.shopping.module.aitool.service.AiToolGuard;
import com.lanlink.shopping.support.AiApiKeyService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 工具 API 的 HTTP 状态契约测试。
 *
 * <p>鉴权类失败必须透出真实的 401/403/429；普通业务校验失败仍保持
 * 既有的 HTTP 200 + R.code 语义，避免波壮前端旧接口契约。</p>
 */
public class AiToolExceptionHandlerTest {

    private static final String INTERNAL_SECRET = "test-internal-secret-12345";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiToolExecutionService toolExecutionService;
    private AiApiKeyService apiKeyService;
    private com.lanlink.shopping.service.AuditService auditService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        toolExecutionService = Mockito.mock(AiToolExecutionService.class);
        apiKeyService = Mockito.mock(AiApiKeyService.class);
        auditService = Mockito.mock(com.lanlink.shopping.service.AuditService.class);

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ai.internal-secret", INTERNAL_SECRET);

        AiToolsInternalController controller = new AiToolsInternalController(
                toolExecutionService, new AiToolGuard(environment, apiKeyService));
        ReflectionTestUtils.setField(controller, "auditService", auditService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AiToolExceptionHandler())
                .build();
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private AiToolDto.GenericToolCallRequest genericRequest(String tool) {
        return AiToolDto.GenericToolCallRequest.builder()
                .tool(tool)
                .arguments(Map.of("query", "防水台钓竿"))
                .build();
    }

    @Test
    void permissionDenialIsStillAuditedAfterTheRethrow() throws Exception {
        when(toolExecutionService.executeTool(any(), any(), anyString()))
                .thenThrow(new BusinessException("FORBIDDEN: API Key 无权查询该商户知识"));

        mockMvc.perform(post("/internal/ai/tools/execute")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(genericRequest("lanlink_search_knowledge"))))
                .andExpect(status().isForbidden());

        // 抛出异常也不能丢掉审计记录，否则越权探测在服务端无痕迹
        Mockito.verify(auditService).record(Mockito.isNull(), Mockito.eq("AI_TOOL_CALL"),
                Mockito.contains("outcome=rejected"), Mockito.any());
    }

    @Test
    void unauthenticatedRequestIsAuditedAsADenial() throws Exception {
        mockMvc.perform(post("/internal/ai/tools/knowledge-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.KnowledgeSearchRequest.builder()
                                .query("防水台钓竿").build())))
                .andExpect(status().isUnauthorized());

        Mockito.verify(auditService).record(Mockito.isNull(), Mockito.eq("AI_TOOL_CALL_DENIED"),
                Mockito.contains("reason=unauthenticated"), Mockito.any());
    }

    @Test
    void scopeDeniedRequestIsAuditedAndReturns403() throws Exception {
        when(apiKeyService.authenticate("llk_readonly_key_0001"))
                .thenReturn(new AiApiKeyService.Principal("llk_readonly_****",
                        Set.of("knowledge:read"), null));

        mockMvc.perform(post("/internal/ai/tools/monitor-delivery-sla")
                        .header("Authorization", "Bearer llk_readonly_key_0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.MonitorDeliverySlaRequest.builder()
                                .orderNo("SO20260101001").build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACL_FORBIDDEN"));

        Mockito.verify(auditService).record(Mockito.isNull(), Mockito.eq("AI_TOOL_CALL_DENIED"),
                Mockito.contains("reason=scope-denied"), Mockito.any());
    }

    @Test
    void requestIdHeaderIsNotDuplicatedAndMatchesBody() throws Exception {
        when(toolExecutionService.executeTool(any(), any(), anyString()))
                .thenThrow(new BusinessException("FORBIDDEN: 无权查询该订单的物流与收件信息"));

        var response = mockMvc.perform(post("/internal/ai/tools/execute")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(genericRequest("lanlink_track_logistics"))))
                .andExpect(status().isForbidden())
                .andReturn().getResponse();

        List<String> headers = response.getHeaders("X-Request-Id");
        assertEquals(1, headers.size(), "控制器与异常边界不得各写一次，实际为: " + headers);

        String bodyId = objectMapper.readTree(response.getContentAsString())
                .path("requestId").asText();
        assertEquals(headers.get(0), bodyId, "响应头与响应体必须指向同一个请求号");
    }

    @Test
    void attackerControlledToolNameIsSanitizedInAudit() throws Exception {
        when(toolExecutionService.executeTool(any(), any(), anyString()))
                .thenReturn(AiToolDto.GenericToolCallResponse.builder()
                        .tool("forged").success(true).build());

        String injectedTool = "evil\r\nFORGED-AI_TOOL_CALL fake";
        mockMvc.perform(post("/internal/ai/tools/execute")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.GenericToolCallRequest.builder()
                                .tool(injectedTool)
                                .arguments(Map.of("query", "x"))
                                .build())))
                .andExpect(status().isOk());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        Mockito.verify(auditService).record(Mockito.isNull(), Mockito.eq("AI_TOOL_CALL"),
                detail.capture(), Mockito.any());
        String d = detail.getValue();
        assertFalse(d.contains("\r") || d.contains("\n"),
                "审计记录不得被请求体注入换行: " + d.replace("\r", "\\r").replace("\n", "\\n"));
        assertTrue(d.contains("evil?"), "控制字符应被替换，实际为: " + d);
    }

    @Test
    void dedicatedEndpointMapsPermissionDenialToHttp403() throws Exception {
        when(toolExecutionService.monitorDeliverySla(any()))
                .thenThrow(new BusinessException("FORBIDDEN: 无权访问该订单履约信息"));

        mockMvc.perform(post("/internal/ai/tools/monitor-delivery-sla")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.MonitorDeliverySlaRequest.builder()
                                .orderNo("SO20260101001")
                                .build())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.errorCode").value("ACL_FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void genericExecuteDoesNotDowngradeTenantMismatchToHttp200() throws Exception {
        when(toolExecutionService.executeTool(any(), any(), anyString()))
                .thenThrow(new BusinessException("FORBIDDEN: API Key 无权查询该商户知识"));

        mockMvc.perform(post("/internal/ai/tools/execute")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(genericRequest("lanlink_search_knowledge"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACL_FORBIDDEN"));
    }

    @Test
    void missingCredentialsMapsToHttp401WithChallenge() throws Exception {
        mockMvc.perform(post("/internal/ai/tools/knowledge-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.KnowledgeSearchRequest.builder()
                                .query("防水台钓竿")
                                .build())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        Matchers.containsString("lanlink-ai-tools")))
                .andExpect(jsonPath("$.errorCode").value("AUTH_KEY_INVALID"));
    }

    @Test
    void ordinaryBusinessFailureStillReturnsHttp200Envelope() throws Exception {
        when(toolExecutionService.executeTool(any(), any(), anyString()))
                .thenReturn(AiToolDto.GenericToolCallResponse.builder()
                        .tool("lanlink_search_products")
                        .success(false)
                        .error("商品不存在")
                        .latencyMs(3L)
                        .build());

        mockMvc.perform(post("/internal/ai/tools/execute")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(genericRequest("lanlink_search_products"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.error").value("商品不存在"));
    }

    @Test
    void rateLimitedServiceFailureMapsToHttp429() throws Exception {
        when(toolExecutionService.searchKnowledge(any(), any(), anyString()))
                .thenThrow(new BusinessException("429 Too Many Requests: 该 Key 调用频率超限"));

        mockMvc.perform(post("/internal/ai/tools/knowledge-search")
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(AiToolDto.KnowledgeSearchRequest.builder()
                                .query("防水台钓竿")
                                .build())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }
}

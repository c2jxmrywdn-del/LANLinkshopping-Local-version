package com.lanlink.shopping.module.aitool;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.aitool.controller.AiToolsInternalController;
import com.lanlink.shopping.module.aitool.dto.AiToolDto;
import com.lanlink.shopping.module.aitool.service.AiToolExecutionService;
import com.lanlink.shopping.module.aitool.service.AiToolGuard;
import com.lanlink.shopping.support.AiApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class AiToolsInternalControllerTest {

    private AiToolExecutionService toolExecutionService;
    private AiApiKeyService apiKeyService;
    private MockEnvironment environment;
    private AiToolGuard aiToolGuard;
    private AiToolsInternalController controller;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        toolExecutionService = Mockito.mock(AiToolExecutionService.class);
        apiKeyService = Mockito.mock(AiApiKeyService.class);

        environment = new MockEnvironment();
        environment.setProperty("ai.internal-secret", "test-internal-secret-12345");

        aiToolGuard = new AiToolGuard(environment, apiKeyService);
        controller = new AiToolsInternalController(toolExecutionService, aiToolGuard);
        response = Mockito.mock(HttpServletResponse.class);
    }

    @Test
    void testGetSpecs_ReturnsTenToolsForInternalSecret() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("X-Internal-Secret")).thenReturn("test-internal-secret-12345");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        R<List<Map<String, Object>>> specsResp = controller.getSpecs(request);
        assertNotNull(specsResp);
        assertEquals(200, specsResp.getCode());
        List<Map<String, Object>> tools = specsResp.getData();
        assertEquals(10, tools.size());

        assertTrue(tools.stream().anyMatch(t -> {
            Map<?, ?> fn = (Map<?, ?>) t.get("function");
            return "lanlink_search_products".equals(fn.get("name"));
        }));
        assertTrue(tools.stream().anyMatch(t -> {
            Map<?, ?> fn = (Map<?, ?>) t.get("function");
            return "lanlink_calculate_quotation".equals(fn.get("name"));
        }));
        assertTrue(tools.stream().anyMatch(t -> {
            Map<?, ?> fn = (Map<?, ?>) t.get("function");
            return "lanlink_monitor_delivery_sla".equals(fn.get("name"));
        }));
        assertTrue(tools.stream().anyMatch(t -> {
            Map<?, ?> fn = (Map<?, ?>) t.get("function");
            return "lanlink_search_knowledge".equals(fn.get("name"));
        }));
    }

    @Test
    void testUnauthorizedRequest_ReturnsHttp401() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        AiToolDto.SearchProductsRequest req = AiToolDto.SearchProductsRequest.builder()
                .keyword("螺纹钢")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.searchProducts(req, request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void testForbiddenScope_ReturnsHttp403() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("X-API-Key")).thenReturn("llk_agent_testkey999");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(apiKeyService.authenticate("llk_agent_testkey999"))
                .thenReturn(new AiApiKeyService.Principal("llk_agent_", Set.of("products:read"), null));

        AiToolDto.CreateCartDraftRequest req = AiToolDto.CreateCartDraftRequest.builder()
                .productId(101L)
                .quantity(50)
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createCartDraft(req, request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void testAuthorizedWithInternalSecret_Success() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("X-Internal-Secret")).thenReturn("test-internal-secret-12345");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        AiToolDto.SearchProductsRequest req = AiToolDto.SearchProductsRequest.builder()
                .keyword("螺纹钢")
                .build();

        AiToolDto.SearchProductsResponse mockResp = AiToolDto.SearchProductsResponse.builder()
                .total(1L)
                .items(List.of())
                .keyword("螺纹钢")
                .build();
        when(toolExecutionService.searchProducts(any())).thenReturn(mockResp);

        R<AiToolDto.SearchProductsResponse> r = controller.searchProducts(req, request);
        assertNotNull(r);
        assertEquals(200, r.getCode());
        assertEquals("螺纹钢", r.getData().getKeyword());
    }

    @Test
    void testLegacyToolsCallApiKey_CanReadProducts() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer llk_agent_testkey999");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");
        when(apiKeyService.authenticate("llk_agent_testkey999"))
                .thenReturn(new AiApiKeyService.Principal("llk_agent_", Set.of("tools:call"), null));

        AiToolDto.CalculateQuotationRequest req = AiToolDto.CalculateQuotationRequest.builder()
                .productId(101L)
                .quantity(50)
                .build();

        AiToolDto.CalculateQuotationResponse mockResp = AiToolDto.CalculateQuotationResponse.builder()
                .prodId(101L)
                .quantity(50)
                .meetsMoq(true)
                .build();
        when(toolExecutionService.calculateQuotation(any())).thenReturn(mockResp);

        R<AiToolDto.CalculateQuotationResponse> r = controller.calculateQuotation(req, request);
        assertNotNull(r);
        assertEquals(200, r.getCode());
        assertEquals(101L, r.getData().getProdId());
    }

    @Test
    void testExecuteEndpoint_Success() {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(request.getHeader("X-Internal-Secret")).thenReturn("test-internal-secret-12345");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        AiToolDto.GenericToolCallRequest req = AiToolDto.GenericToolCallRequest.builder()
                .tool("lanlink_search_products")
                .arguments(Map.of("keyword", "阀门"))
                .build();

        AiToolDto.GenericToolCallResponse mockResp = AiToolDto.GenericToolCallResponse.builder()
                .tool("lanlink_search_products")
                .success(true)
                .data(Map.of("total", 5))
                .build();
        when(toolExecutionService.executeTool(any(), any(), anyString())).thenReturn(mockResp);

        R<AiToolDto.GenericToolCallResponse> r = controller.execute(req, request, response);
        assertNotNull(r);
        assertEquals(200, r.getCode());
        assertTrue(r.getData().getSuccess());
    }
}

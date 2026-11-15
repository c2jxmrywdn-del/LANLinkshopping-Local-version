package com.lanlink.shopping.support.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.support.SupportRequestGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音播报端点的契约测试：本地 HttpServer 冒充 AI 网关，不访问真实 TTS、不产生费用。
 */
class SupportTtsControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tts", exchange -> {
            hits.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(readBody(exchange));
            respond(exchange, 200,
                    "{\"audioBase64\":\"QUJD\",\"format\":\"mp3\",\"sampleRate\":24000,\"engine\":\"mimo\"}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void forwardsTextToGatewayAndMapsResponse() throws IOException {
        SupportTtsController controller = newController();

        R<SupportTtsController.TtsResult> result = controller.synthesize(
                new SupportTtsController.TtsRequest("你好，请问订单在哪里查？", null, null),
                authorizedRequest());

        assertEquals(200, result.getCode());
        assertEquals("QUJD", result.getData().audioBase64());
        assertEquals("mp3", result.getData().format());
        assertEquals(24000, result.getData().sampleRate());
        assertEquals("mimo", result.getData().engine());
        assertEquals("茉莉", result.getData().voice());

        assertEquals("Bearer test-key", authorization.get());
        JsonNode sent = JSON.readTree(requestBody.get());
        // 固定风格标签必须前置，且不能改变原文
        assertTrue(sent.path("text").asText().startsWith("(御姐音"));
        assertTrue(sent.path("text").asText().endsWith("你好，请问订单在哪里查？"));
        assertEquals("mp3", sent.path("format").asText());
        // 音色与风格由服务端固定，必须每次都发送
        assertEquals("茉莉", sent.path("voice").asText());
        assertFalse(sent.path("style").asText().isBlank());
    }

    @Test
    void ignoresClientSuppliedVoiceToKeepTimbreStable() throws IOException {
        SupportTtsController controller = newController();

        controller.synthesize(new SupportTtsController.TtsRequest("欢迎使用客服", "Chloe", "mp3"),
                authorizedRequest());

        JsonNode sent = JSON.readTree(requestBody.get());
        assertEquals("茉莉", sent.path("voice").asText());
        assertTrue(sent.path("voice").asText().equals("茉莉"));
    }

    @Test
    void reusesCachedSynthesisForIdenticalInput() {
        SupportTtsController controller = newController();

        controller.synthesize(new SupportTtsController.TtsRequest("欢迎使用客服", "default", "mp3"),
                authorizedRequest());
        controller.synthesize(new SupportTtsController.TtsRequest("欢迎使用客服", "default", "mp3"),
                authorizedRequest());

        assertEquals(1, hits.get());
    }

    @Test
    void rejectsTextLongerThanFiveHundredCharacters() {
        SupportTtsController controller = newController();
        String tooLong = "字".repeat(501);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.synthesize(new SupportTtsController.TtsRequest(tooLong, null, null),
                        authorizedRequest()));

        assertEquals(400, error.getCode());
        assertEquals(0, hits.get());
    }

    @Test
    void rejectsUnauthorizedRequests() {
        SupportTtsController controller = newController();
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/support/tts");

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.synthesize(new SupportTtsController.TtsRequest("你好", null, null), anonymous));

        assertEquals(403, error.getCode());
        assertTrue(hits.get() == 0);
    }

    private SupportTtsController newController() {
        MockEnvironment environment = new MockEnvironment().withProperty("support.proxy-secret", "test-secret");
        SupportRequestGuard guard = new SupportRequestGuard(environment);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new SupportTtsController(guard, base, "test-key");
    }

    private static MockHttpServletRequest authorizedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/support/tts");
        request.addHeader("X-Support-Proxy-Secret", "test-secret");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

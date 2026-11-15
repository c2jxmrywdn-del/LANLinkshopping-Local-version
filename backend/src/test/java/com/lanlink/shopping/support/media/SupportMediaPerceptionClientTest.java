package com.lanlink.shopping.support.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 感知客户端的契约映射测试：用本地 HttpServer 冒充 OCR/网关，不访问任何外部网络。
 */
class SupportMediaPerceptionClientTest {

    private static final byte[] IMAGE = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> ocrBody = new AtomicReference<>();
    private final AtomicReference<String> visionMimoStatus = new AtomicReference<>("200");
    private final AtomicReference<String> localVisionStatus = new AtomicReference<>("200");
    private final AtomicReference<String> localVisionBody = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            ocrBody.set(readBody(exchange));
            respond(exchange, 200,
                    "{\"text\":\"订单 SM001\",\"lines\":[],\"engine\":\"rapidocr\",\"elapsedMs\":8}");
        });
        server.createContext("/v1/vision-mimo", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("200".equals(visionMimoStatus.get())) {
                respond(exchange, 200,
                        "{\"text\":\"图片里是一张发货单\",\"engine\":\"mimo\",\"model\":\"mimo-vl\",\"usage\":{}}");
            } else {
                respond(exchange, Integer.parseInt(visionMimoStatus.get()),
                        "{\"error\":{\"message\":\"MiMo 上游返回 HTTP " + visionMimoStatus.get() + "\"}}");
            }
        });
        server.createContext("/v1/vision", exchange -> {
            respond(exchange, Integer.parseInt(localVisionStatus.get()), localVisionBody.get());
        });
        server.createContext("/v1/asr", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200,
                    "{\"text\":\"麻烦查一下订单\",\"engine\":\"mimo\",\"model\":\"mimo-audio\","
                            + "\"usage\":{\"seconds\":27.5}}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void mapsOcrVisionAndAsrResponses() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                base, base, base + "/v1", "test-key", false);

        assertEquals("订单 SM001", client.ocr(IMAGE, "image/png").orElseThrow());
        assertTrue(ocrBody.get().contains("\"lang\":\"ch\""));

        SupportMediaPerceptionClient.VisionResult vision =
                client.vision(IMAGE, "image/png", "描述图片").orElseThrow();
        assertEquals("图片里是一张发货单", vision.text());
        assertEquals("mimo", vision.engine());
        assertEquals("Bearer test-key", authorization.get());

        SupportMediaPerceptionClient.AsrResult asr = client.asr(IMAGE, "audio/wav").orElseThrow();
        assertEquals("麻烦查一下订单", asr.text());
        assertEquals("mimo", asr.engine());
        // usage.seconds 必须被解析并随结果返回（t_support_llm_usage.audio_seconds 的落账来源）
        assertEquals(0, asr.audioSeconds().compareTo(new java.math.BigDecimal("27.5")));
    }

    @Test
    void visionFallbackChainPrefersMimoThenLocalThenOcr() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // MiMo 主通道正常：直接返回，不触发本地腿与 OCR
        visionMimoStatus.set("200");
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                base, base, base + "/v1", "test-key", true);
        SupportMediaPerceptionClient.VisionResult primary =
                client.visionWithFallback(IMAGE, "image/png", "描述图片").orElseThrow();
        assertEquals("图片里是一张发货单", primary.text());
        assertEquals("mimo", primary.engine());
    }

    @Test
    void visionFallbackChainFallsToOcrWhenAllVisionLegsDown() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // MiMo 上游失败 + 本地腿开关关闭（单卡 5GB 口径默认形态）：visionWithFallback 落到 RapidOCR 兜底
        visionMimoStatus.set("502");
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                base, base, base + "/v1", "test-key", false);
        SupportMediaPerceptionClient.VisionResult ocr =
                client.visionWithFallback(IMAGE, "image/png", "描述图片").orElseThrow();
        assertEquals("订单 SM001", ocr.text());
        assertEquals("rapidocr", ocr.engine());
    }

    @Test
    void visionFallbackChainUsesLocalVlOnlyWhenEnabled() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // MiMo 失败 + 本地腿显式开启（仅多 GPU 部署）：走 /v1/vision 的 OpenAI 壳协议
        visionMimoStatus.set("502");
        localVisionStatus.set("200");
        localVisionBody.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + "本地图里是一张发票\"}}],\"lanlink\":{\"backend\":\"llamacpp:qwen2.5-vl\","
                + "\"vision_model\":\"qwen2.5-vl\"}}");
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                base, base, base + "/v1", "test-key", true);
        SupportMediaPerceptionClient.VisionResult local =
                client.visionWithFallback(IMAGE, "image/png", "描述图片").orElseThrow();
        assertEquals("本地图里是一张发票", local.text());
        assertEquals("local-vl", local.engine());
        assertEquals("qwen2.5-vl", local.model());
    }

    @Test
    void visionFallbackChainSkipsUnavailableLocalLegAndUsesOcr() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // MiMo 失败 + 本地腿开启但 backend=none（显存不足/排队超时语义）：跳过本地腿，落 OCR
        visionMimoStatus.set("502");
        localVisionStatus.set("200");
        localVisionBody.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + "当前 GPU 显存不足，视觉模型暂不可用\"}}],\"lanlink\":{\"backend\":\"none\"}}");
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                base, base, base + "/v1", "test-key", true);
        SupportMediaPerceptionClient.VisionResult ocr =
                client.visionWithFallback(IMAGE, "image/png", "描述图片").orElseThrow();
        assertEquals("订单 SM001", ocr.text());
        assertEquals("rapidocr", ocr.engine());
    }

    @Test
    void degradesToEmptyWhenServiceIsUnreachable() {
        // 未监听的端口：客户端应吞掉连接异常，返回 empty 交由消费端决定是否重试
        SupportMediaPerceptionClient client = new SupportMediaPerceptionClient(
                "http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1/v1", "test-key", false);

        assertTrue(client.ocr(IMAGE, "image/png").isEmpty());
        // 降级链：MiMo 与 OCR 全不可达时 visionWithFallback 返回 empty，交由消费端给出明确文案
        assertTrue(client.visionWithFallback(IMAGE, "image/png", "描述图片").isEmpty());
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

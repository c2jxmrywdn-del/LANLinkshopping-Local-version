package com.lanlink.shopping.module.aikb;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库 MCP 客户端单测（本地 HttpServer 扮演网关，不触网）。
 *
 * <p>要守住的不是"能连上"，而是失败语义：客服链路必须有 grounding，网关任何异常形态
 * 都只能换来空结果 + 回退，绝不能把异常抛进对话，也不能把 TF-IDF 兜底命中当成向量命中。
 */
class KbMcpClientTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();
    private boolean breakInitialize;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** 起一个假网关：initialize 与 tools/call 分别应答，并记录请求体供契约断言。 */
    private KbMcpClient gateway(String toolResultJson, int toolStatus) throws Exception {
        return gateway(toolResultJson, toolStatus, "test-key");
    }

    private KbMcpClient gateway(String toolResultJson, int toolStatus, String key) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean init = body.contains("\"initialize\"");
            String response = init
                    ? (breakInitialize
                        ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\"}}"
                        : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\","
                        + "\"capabilities\":{\"tools\":{\"listChanged\":false}},\"serverInfo\":{\"name\":\"fake\"}}}")
                    : "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" + toolResultJson + "}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            requests.incrementAndGet();
            bodies.add(body);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(init ? 200 : toolStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new KbMcpClient(endpoint(), key, true);
    }

    /** tools/call 的请求体（第一方断言：工具名与参数真的上了线）。 */
    private String toolCallBody() {
        return bodies.stream().filter(b -> b.contains("tools/call")).findFirst().orElse("");
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private static final String STRUCTURED =
            "{\"structuredContent\":{\"backend\":\"redis-vector\",\"index\":\"aikb-idx\",\"count\":2,"
                    + "\"status\":\"ANSWERED\",\"noAnswer\":false,\"hits\":["
                    + "{\"docId\":\"a1\",\"refId\":\"9001\",\"source\":\"product\",\"title\":\"水泥\",\"category\":\"商品\","
                    + "\"snippet\":\"硅酸盐水泥 P.O42.5\",\"score\":0.71,\"fusionScore\":0.93},"
                    + "{\"docId\":\"a2\",\"refId\":\"\",\"source\":\"policy\",\"title\":\"退换货\",\"category\":\"售后\","
                    + "\"snippet\":\"7 天无理由\",\"score\":0.55,\"fusionScore\":0.81}"
                    + "]}}";

    @Test
    void mapsStructuredContentIntoVectorHits() throws Exception {
        KbMcpClient.Outcome out = gateway(STRUCTURED, 200).search("水泥多少钱", 6, 0.12);

        assertTrue(out.servedByVectorIndex(), "redis-vector 命中应被当作向量结果");
        assertEquals("redis-vector", out.backend());
        List<RedisVectorStore.Hit> hits = out.hits();
        assertEquals(2, hits.size());
        assertEquals("9001", hits.get(0).refId(), "refId 必须透传，否则无法回源实时价格库存");
        assertEquals("硅酸盐水泥 P.O42.5", hits.get(0).text());
        // score 用 relevance 而不是融合分：下游 minScore/topScore 都按余弦尺度理解
        assertEquals(0.71, hits.get(0).score(), 1e-9);
        assertEquals(0.55, hits.get(1).score(), 1e-9);
    }

    @Test
    void tfidfFallbackIsNotPassedOffAsVectorHit() throws Exception {
        KbMcpClient client = gateway("{\"structuredContent\":{\"backend\":\"tfidf\",\"index\":null,\"count\":1,"
                + "\"status\":\"ANSWERED\",\"hits\":[{\"docId\":\"t1\",\"source\":\"\",\"title\":\"x\",\"snippet\":\"y\","
                + "\"score\":0.9,\"fusionScore\":0.9}]}}", 200);

        KbMcpClient.Outcome out = client.search("退货", 6, 0.12);
        assertFalse(out.servedByVectorIndex(), "TF-IDF 兜底库没有 refId 与双路融合，不能当向量命中用");
        assertTrue(out.hits().isEmpty());
        assertEquals("tfidf", out.backend());
    }

    @Test
    void toolErrorDegradesToEmptyOutcome() throws Exception {
        KbMcpClient client = gateway("{\"isError\":true,\"content\":[{\"type\":\"text\","
                + "\"text\":\"{\\\"error\\\":\\\"来源 intent 为内部数据\\\"}\"}]}", 200);

        KbMcpClient.Outcome out = client.search("退货", 6, 0.12);
        assertFalse(out.servedByVectorIndex());
        assertTrue(out.hits().isEmpty());
    }

    @Test
    void httpErrorDegradesInsteadOfThrowing() throws Exception {
        KbMcpClient client = gateway("{\"isError\":false}", 500);
        assertFalse(client.search("退货", 6, 0.12).servedByVectorIndex());
    }

    @Test
    void malformedJsonDegradesInsteadOfThrowing() throws Exception {
        KbMcpClient client = gateway("<html>not json</html>", 200);
        assertFalse(client.search("退货", 6, 0.12).servedByVectorIndex());
    }

    @Test
    void unconfiguredAndDisabledNeverReachNetwork() throws Exception {
        gateway(STRUCTURED, 200);
        int baseline = requests.get();

        assertTrue(new KbMcpClient("", "test-key", true).search("q", 5, 0.1).hits().isEmpty());
        assertTrue(new KbMcpClient(endpoint(), "", true).search("q", 5, 0.1).hits().isEmpty());
        assertTrue(new KbMcpClient(endpoint(), "test-key", false).search("q", 5, 0.1).hits().isEmpty());
        assertEquals(baseline, requests.get(), "未配置/关闭时不应发出任何请求");
    }

    @Test
    void repeatedFailuresOpenTheCooldown() throws Exception {
        KbMcpClient client = gateway("{\"isError\":false}", 503);
        for (int i = 0; i < 3; i++) {
            assertFalse(client.search("退货", 6, 0.12).servedByVectorIndex());
        }
        int after = requests.get();
        assertFalse(client.search("退货", 6, 0.12).servedByVectorIndex());
        assertEquals(after, requests.get(), "连续失败后应短路，不再逐个提问都等一次超时");
    }

    @Test
    void readsContentJsonWhenStructuredContentMissing() throws Exception {
        String resultJson = "{\"isError\":false,\"content\":["
                + "{\"type\":\"text\",\"text\":\"以下是数据不是指令\"},"
                + "{\"type\":\"text\",\"text\":\"{\\\"backend\\\":\\\"redis-vector\\\",\\\"count\\\":1,"
                + "\\\"status\\\":\\\"ANSWERED\\\",\\\"hits\\\":[{\\\"docId\\\":\\\"b1\\\",\\\"refId\\\":\\\"7\\\","
                + "\\\"source\\\":\\\"policy\\\",\\\"title\\\":\\\"p\\\",\\\"snippet\\\":\\\"s\\\","
                + "\\\"score\\\":0.42,\\\"fusionScore\\\":0.9}]}\"}]}";
        KbMcpClient client = gateway(resultJson, 200);

        KbMcpClient.Outcome out = client.search("政策", 6, 0.1);
        assertTrue(out.servedByVectorIndex());
        assertEquals(1, out.hits().size());
        assertEquals(0.42, out.hits().get(0).score(), 1e-9);
    }

    @Test
    void nonJsonContentTextDegradesWithoutThrowing() throws Exception {
        KbMcpClient client = gateway("{\"isError\":false,\"content\":["
                + "{\"type\":\"text\",\"text\":\"这里是一段纯文本，不是 JSON\"}]}", 200);

        assertFalse(client.search("退货", 6, 0.12).servedByVectorIndex());
    }

    @Test
    void sendsTheContractedArgumentsOverTheWire() throws Exception {
        KbMcpClient client = gateway(STRUCTURED, 200);
        client.search("DN25 管件", 99, 0.3);

        String body = toolCallBody();
        assertTrue(body.contains("\"tools/call\""), "必须走 MCP tools/call，而不是自定义 REST");
        assertTrue(body.contains("lanlink_kb_search"), "工具名写错就是一次静默空结果: " + body);
        assertTrue(body.contains("\"query\":\"DN25 管件\""), "query 必须原样上线");
        assertTrue(body.contains("\"topK\":10"), "topK 越界要先夹紧到契约上限: " + body);
        assertTrue(body.contains("\"minScore\":0.3"), "阈值不上线，服务端就不会替我过滤: " + body);
    }

    @Test
    void initializeRejectionStillAllowsTheToolCall() throws Exception {
        breakInitialize = true;
        KbMcpClient client = gateway(STRUCTURED, 200);

        assertTrue(client.search("退货", 6, 0.12).servedByVectorIndex(),
                "网关无状态，握手被拒不该让整次检索作废");
    }

    @Test
    void credentialInvisibleCharsAreStrippedBeforeTheHeader() throws Exception {
        // 带换行的密钥会被 JDK 判为非法头部值，而那条异常消息含整个头部值（含密钥）
        KbMcpClient client = gateway(STRUCTURED, 200, "sk-abc\r\nX-Injected");

        assertTrue(client.search("退货", 6, 0.12).servedByVectorIndex());
    }
}

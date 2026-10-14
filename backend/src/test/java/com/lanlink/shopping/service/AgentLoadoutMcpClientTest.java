package com.lanlink.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoadoutMcpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsMessageThroughAuthenticatedMcpSession() throws Exception {
        List<String> methods = new ArrayList<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> callPayload = new AtomicReference<>();
        AtomicReference<String> toolsCallSession = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            methods.add(method);

            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                writeJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
            } else if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            } else if ("tools/call".equals(method)) {
                toolsCallSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                callPayload.set(request);
                writeJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"queued\"}],\"isError\":false}}");
            } else {
                writeJson(exchange, 400, "{\"error\":\"unexpected method\"}");
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            AgentLoadoutMcpClient client = new AgentLoadoutMcpClient(endpoint, "test_api_key", "inb_test");
            client.sendMessage("person@example.com", "Test subject", "Test body", null, "stable-test-key");

            assertEquals("Bearer test_api_key", authorization.get());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/call"), methods);
            JsonNode rpc = callPayload.get();
            assertEquals("tools/call", rpc.path("method").asText());
            assertEquals("send_message", rpc.path("params").path("name").asText());
            JsonNode args = rpc.path("params").path("arguments");
            assertEquals("inb_test", args.path("inbox_id").asText());
            assertEquals("person@example.com", args.path("to").get(0).asText());
            assertEquals("stable-test-key", args.path("idempotency_key").asText());
            assertEquals("test-session", toolsCallSession.get());
        } finally {
            server.stop(0);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

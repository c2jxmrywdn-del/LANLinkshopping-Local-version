package com.lanlink.shopping.module.productreview.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanlink.shopping.support.OutboundModelConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本复核请求体契约单测：本地网关保留 use_kb/thinking 专用字段，外部渠道裁剪为标准 OpenAI 体；
 * 所有渠道强制 response_format=json_object，系统提示词约束 JSON 输出契约。
 */
class ProductReviewAiRequestTest {

    private final OutboundModelConfigService.ChannelConfig local =
            new OutboundModelConfigService.ChannelConfig("mimosa", "http://lanlink-ai-gateway:8090/v1", "k", "qwen2.5-7b");
    private final OutboundModelConfigService.ChannelConfig external =
            new OutboundModelConfigService.ChannelConfig("deepseek", "https://api.deepseek.com/v1", "k", "deepseek-chat");

    @Test
    void localGatewayBodyKeepsGatewayFieldsAndForcesJson() {
        ObjectNode body = ProductReviewAiService.buildRequestBody(local, "qwen2.5-7b", "Product data:{}");
        assertTrue(body.has("use_kb"), "本地网关应保留 use_kb");
        assertFalse(body.get("use_kb").asBoolean());
        assertTrue(body.has("thinking"), "本地网关应保留 thinking");
        assertFalse(body.get("thinking").asBoolean());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals(200, body.path("max_tokens").asInt());
    }

    @Test
    void externalChannelBodyOmitsGatewayOnlyFields() {
        ObjectNode body = ProductReviewAiService.buildRequestBody(external, "deepseek-chat", "Product data:{}");
        assertFalse(body.has("use_kb"), "外部渠道不应携带网关专用 use_kb（DeepSeek 官方 API 会 422）");
        assertFalse(body.has("thinking"), "外部渠道不应携带网关专用 thinking");
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("deepseek-chat", body.path("model").asText());
    }

    @Test
    void systemPromptEnforcesJsonContract() {
        ObjectNode body = ProductReviewAiService.buildRequestBody(local, "m", "data");
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        String system = body.path("messages").get(0).path("content").asText();
        assertTrue(system.contains("JSON"), "提示词应强制 JSON 输出");
        assertTrue(system.contains("decision"), "提示词应描述 decision 字段契约");
    }
}
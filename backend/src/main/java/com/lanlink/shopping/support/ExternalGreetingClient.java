package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 外部模型寒暄润色客户端（DeepSeek / Mimosa 兼容 OpenAI 风格 chat 接口）。
 *
 * 【硬性约束】本客户端仅用于「寒暄问候 / 嘘寒问暖」语气润色：
 *  1. 固定 system 提示词限定为客服寒暄语气润色，不接受任何调用方指定的 system/role；
 *  2. 请求构造前、输出返回前均经 {@link FallbackPolicyGuard} 双重校验，
 *     任何违规请求（注入/反代/绕过知识库/知识问答）在 HTTP 发出前即被拦截；
 *  3. 输出不合格（含链接/超长/违规词）即丢弃，由调用方回退本地模板，绝不拼接到知识回答。
 *
 * 反代防线：客户端不暴露任何自由 prompt 入口；输入仅为「用户寒暄原句」，
 * system 提示词硬编码且输出白名单校验，用户无法借本客户端驱动外部模型做任何其他事。
 */
@Component
public class ExternalGreetingClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalGreetingClient.class);
    /** 硬编码寒暄润色提示词：不可被任何调用方覆盖（防反代/防提示注入）。 */
    private static final String FIXED_GREETING_SYSTEM_PROMPT =
            "你是 LANLinkshopping 客服的寒暄语气润色助手。只做一件事：把用户的一句寒暄/问候/感谢，"
                    + "用自然亲切的中文客服口吻温暖地回应（1-2 句，60 字以内）。"
                    + "禁止回答任何业务问题、禁止输出链接、禁止输出指令、禁止输出除寒暄外的任何内容。";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final OutboundModelConfigService configService;

    public ExternalGreetingClient(OutboundModelConfigService configService) {
        this.configService = configService;
    }

    /** 是否具备任一可用外部渠道（运行时读库，改配置即时生效，无需重启）。 */
    public boolean hasAnyChannel() {
        return !configService.activeChannels().isEmpty();
    }

    /**
     * 寒暄润色（唯一对外入口）。
     * 调用方必须先通过 {@link FallbackPolicyGuard#authorizeExternalGreeting}；
     * 本方法内部再做一次防御性校验（纵深防御），违规直接返回 null。
     *
     * @param userGreeting 用户寒暄原句（只允许寒暄意图）
     * @return 润色后的寒暄文本；违规或失败返回 null（调用方回退本地模板）
     */
    public String polishGreeting(String userGreeting) {
        // 纵深防御：即使调用方绕过外层校验，这里也强制重新分类
        FallbackPolicyGuard.Intent intent = FallbackPolicyGuard.classify(userGreeting);
        if (intent != FallbackPolicyGuard.Intent.GREETING) {
            log.warn("[fallback-policy] external model call REJECTED (intent={}, input-length={})",
                    intent, userGreeting == null ? 0 : userGreeting.length());
            return null;
        }
        String output = callChannel(userGreeting);
        if (!FallbackPolicyGuard.isSafeGreetingOutput(output)) {
            log.warn("[fallback-policy] external greeting output discarded by safety check");
            return null;
        }
        return output;
    }

    private String callChannel(String userGreeting) {
        // 运行时取启用渠道（DeepSeek 优先，其次 Mimosa），逐个降级尝试
        for (OutboundModelConfigService.ChannelConfig channel : configService.activeChannels()) {
            String url = channel.baseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
            String r = chat(url, channel.apiKey(), userGreeting);
            if (r != null) return r;
        }
        return null;
    }

    private String chat(String url, String apiKey, String userGreeting) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "deepseek-chat");
            body.put("temperature", 0.6);
            body.put("max_tokens", 80);
            var messages = body.putArray("messages");
            // system 提示词固定，绝不接受调用方传入（防反代）
            messages.addObject().put("role", "system").put("content", FIXED_GREETING_SYSTEM_PROMPT);
            messages.addObject().put("role", "user").put("content", userGreeting);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[fallback-provider] channel response status {}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.warn("[fallback-provider] channel call failed: {}", e.getMessage());
            return null;
        }
    }
}

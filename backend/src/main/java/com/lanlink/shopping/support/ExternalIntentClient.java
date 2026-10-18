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
import java.util.List;
import java.util.Set;

/**
 * 外部模型意图分类客户端（可选增强；本地分类为主）。
 *
 * 【硬性约束】本客户端只做「意图标签分类」，绝不生成回答文本：
 *  1. 固定 system 提示词限定输出预定义意图标签之一，不接受任何调用方指定的 system/role；
 *  2. 输出经 {@link FallbackPolicyGuard#isSafeIntentLabel} 白名单校验，非白名单即丢弃回退本地；
 *  3. 调用前须通过 {@link FallbackPolicyGuard#authorizeExternalIntent}（开关 + 意图 + 违规信号校验）。
 *
 * 反代防线：不暴露任何自由 prompt 入口；输入仅为「用户问题原句」，输出只能是标签。
 */
@Component
public class ExternalIntentClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalIntentClient.class);

    /** 预定义意图标签白名单（与 IntentUnderstandingService 规则 code 对齐） */
    static final Set<String> ALLOWED_LABELS = Set.of(
            "account_access", "merchant_onboarding", "product_browse", "cart_order",
            "wallet_credit", "membership_activity", "message_privacy",
            "logistics_tracking", "refund_aftersale", "payment_invoice", "complaint_human", "other");

    /** 固定分类提示词：输出唯一标签，不可被调用方覆盖（防反代/防提示注入） */
    private static final String FIXED_INTENT_SYSTEM_PROMPT =
            "你是意图分类器。只输出一个英文标签，不要输出任何其他文字、解释或标点。"
                    + "可选标签：account_access（账号注册登录）、merchant_onboarding（商户入驻）、"
                    + "product_browse（商品浏览）、cart_order（购物车订单）、wallet_credit（钱包账期）、"
                    + "membership_activity（会员活动）、message_privacy（消息隐私）、"
                    + "logistics_tracking（物流追踪）、refund_aftersale（退款售后）、"
                    + "payment_invoice（支付发票）、complaint_human（人工客服与投诉）、other（其他）。";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final OutboundModelConfigService configService;

    public ExternalIntentClient(OutboundModelConfigService configService) {
        this.configService = configService;
    }

    /**
     * 意图分类（唯一对外入口）。
     * 调用方必须先通过 {@link FallbackPolicyGuard#authorizeExternalIntent}；
     * 本方法内部再做一次防御性校验（纵深防御）。
     *
     * @return 意图标签（白名单内）；违规/失败返回 null（调用方回退本地分类）
     */
    public String classify(String question, FallbackPolicyGuard.Intent guardIntent) {
        // 纵深防御：即使调用方绕过外层校验，这里也强制重新校验
        String denial = FallbackPolicyGuard.authorizeExternalIntent(guardIntent, true);
        if (denial != null) {
            log.warn("[intent-policy] external intent call REJECTED: {}", denial);
            return null;
        }
        String raw = callChannels(question);
        String label = raw == null ? null : raw.trim().replaceAll("[^a-z_]", "").toLowerCase();
        if (!FallbackPolicyGuard.isSafeIntentLabel(label, ALLOWED_LABELS)) {
            log.warn("[intent-policy] external intent output discarded (not in whitelist): {}",
                    raw == null ? "null" : raw.length() + " chars");
            return null;
        }
        return label;
    }

    private String callChannels(String question) {
        for (OutboundModelConfigService.ChannelConfig channel : configService.activeChannels()) {
            String url = channel.baseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
            String r = chat(url, channel.apiKey(), channel.modelName(), question);
            if (r != null) return r;
        }
        return null;
    }

    private String chat(String url, String apiKey, String model, String question) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model != null && !model.isBlank() ? model : "deepseek-chat");
            body.put("temperature", 0);
            body.put("max_tokens", 16);
            var messages = body.putArray("messages");
            // system 提示词固定，绝不接受调用方传入（防反代）
            messages.addObject().put("role", "system").put("content", FIXED_INTENT_SYSTEM_PROMPT);
            messages.addObject().put("role", "user").put("content", question);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[intent-provider] channel response status {}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.warn("[intent-provider] channel call failed: {}", e.getMessage());
            return null;
        }
    }

    /** 意图标签全集（供统计/文档对齐校验） */
    public static List<String> allLabels() {
        return List.copyOf(ALLOWED_LABELS);
    }
}

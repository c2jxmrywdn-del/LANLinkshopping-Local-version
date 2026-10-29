package com.lanlink.shopping.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 智能客服的大模型应答链路：把安全护栏内的用户问题与平台侧 grounding 交给本地 AI 网关。
 *
 * <p>网关调用使用 use_kb=false + 自定义 messages（system 里携带平台资料），确保回答严格基于
 * 平台事实；回答返回前会去掉模型自行附带的"来源：《…》"/"知识库"等元信息，客服界面只展示
 * 自然客服话术。开启思考模式（SUPPORT_AI_THINKING，默认 true）时，网关内部先做一轮推理再生成
 * 最终答案，推理内容不返回给用户，用于提升多条件问题的准确性。
 *
 * <p>任何异常/超时/无有效返回都返回 {@link Optional#empty()}，由调用方回退到本地知识库精确串，
 * 保证客服永不因模型不可用而中断。失败会短时熔断，避免每次请求都等待。
 */
@Service
public class SupportAiAnswerService {

    private static final Logger log = LoggerFactory.getLogger(SupportAiAnswerService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final int TIMEOUT_SECONDS = 25;
    /** 连续失败后的熔断时长：期间直接走知识库模板，避免每条消息都阻塞等待模型。 */
    private static final long BREAKER_MS = 3 * 60 * 1000L;

    private final OutboundModelConfigService configService;
    /** 思考模式：模型先在网关内部推理，最终回答不显示思考过程/来源（SUPPORT_AI_THINKING=false 可关）。 */
    @Value("${SUPPORT_AI_THINKING:true}")
    private boolean thinkingEnabled = true;
    private volatile long disabledUntil = 0;

    public SupportAiAnswerService(OutboundModelConfigService configService) {
        this.configService = configService;
    }

    public boolean available() {
        return System.currentTimeMillis() >= disabledUntil && !channels().isEmpty();
    }

    /**
     * 基于本地知识库标准答案做"接地润色"：把已命中的 KB 事实作为上下文交给模型，
     * 让它组织成自然、亲切、专业的客服表达（use_kb=false，不依赖网关自带、可能不匹配的知识库），
     * 既保证事实正确又摆脱模板腔。失败/超时返回空，由调用方回退到知识库精确串。
     */
    public Optional<String> answer(String question, String grounding) {
        if (!StringUtils.hasText(question)) return Optional.empty();
        boolean english = !containsCjk(question);
        String system = english
                ? "You are the intelligent customer-service assistant of LANLinkshopping, an aggregated B2B e-commerce platform. "
                    + "Answer ONLY from the 【Reference Answer】 below. "
                    + "You MUST write your entire answer in English, regardless of the language of the reference. "
                    + "Be clear, warm and professional (within 150 words). "
                    + "You may add steps or notes, but never invent facts beyond the reference, and never mention 'reference answer' or 'knowledge base'."
                : "你是 LANLinkshopping 聚合型 B2B 电商平台的智能客服。请只依据【参考答案】作答，"
                    + "用简洁、亲切、专业的中文把答案组织成一段自然流畅的回复（150 字以内），"
                    + "可以补充操作步骤或注意事项，但不得编造参考答案之外的事实，也不要出现\"知识库\"\"参考答案\"等字样。";
        String userContent = english
                ? "【Reference Answer】" + (StringUtils.hasText(grounding) ? grounding.trim() : "(none; politely describe what you can help with)")
                    + "\n【Question】" + question.trim()
                    + "\n\nIMPORTANT: Reply ONLY in English. Do not use any Chinese characters."
                : "【参考答案】" + (StringUtils.hasText(grounding) ? grounding.trim() : "（无，请礼貌说明可咨询的具体范围）")
                    + "\n【用户问题】" + question.trim();
        return generate(system, userContent);
    }

    /**
     * 向量 RAG 接地应答：把从本站知识库检索到的真实资料（商品/会员/政策/物流等快照）作为唯一事实来源，
     * 交给本地 qwen2.5-vl 组织成客服表达。资料不足时引导可提交人工工单，不臆造。回答语言跟随提问语言。
     * 失败/超时/熔断返回空，由调用方回退到知识库兜底串。
     */
    public Optional<String> answerRag(String question, String context) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(context)) return Optional.empty();
        boolean english = !containsCjk(question);
        String system = english
                ? "You are the intelligent customer-service assistant of LANLinkshopping, an aggregated B2B e-commerce platform. "
                    + "Answer ONLY from the 【Platform Data】 below. "
                    + "You MUST write your entire answer in English, regardless of the language of the platform data. "
                    + "Be clear, warm and professional (within 150 words). "
                    + "You may cite the specific numbers in the data (prices, thresholds, discounts, categories, partners). "
                    + "If the data does not cover the question, say so honestly and suggest opening a ticket under Settings > Human Support. "
                    + "Do not fabricate facts beyond the data, and do not mention 'platform data' or 'knowledge base'."
                : "你是 LANLinkshopping 聚合型 B2B 电商平台的智能客服。请只依据【平台资料】作答，"
                    + "用简洁、亲切、专业的中文（150 字以内）回答，可引用资料中的具体数字（如价格、门槛、折扣、分类、合作方）。"
                    + "资料未涵盖或不足以确认时，如实说明并提示可在『设置 → 人工客服』提交工单由人工跟进；"
                    + "不得编造资料之外的事实，也不要出现\"资料\"\"知识库\"等字样。";
        String userContent = english
                ? "【Platform Data】\n" + context.trim() + "\n【Question】" + question.trim()
                    + "\n\nIMPORTANT: Reply ONLY in English. Do not use any Chinese characters."
                : "【平台资料】\n" + context.trim() + "\n【用户问题】" + question.trim();
        return generate(system, userContent);
    }

    /** 遍历可用渠道调用本地网关生成；use_kb=false（事实由平台侧检索提供）。全失败触发短时熔断。 */
    private Optional<String> generate(String systemPrompt, String userContent) {
        if (System.currentTimeMillis() < disabledUntil) return Optional.empty();
        for (OutboundModelConfigService.ChannelConfig ch : channels()) {
            try {
                String url = OutboundModelConfigService.chatCompletionsEndpoint(ch.baseUrl());
                String model = StringUtils.hasText(ch.modelName())
                        ? ch.modelName() : OutboundModelConfigService.defaultModelFor(ch.channel());
                ObjectNode body = JSON.createObjectNode();
                body.put("model", model);
                body.put("temperature", 0.3);
                body.put("max_tokens", 1000);
                // 思考模式：网关内部推理（<reasoning> 会在网关侧剥离，不返回思考过程）；
                // 最终回答只保留客服话术，不出现"来源/知识库/参考资料"等元信息。
                body.put("thinking", thinkingEnabled);
                body.put("include_reasoning", false);
                // 已把权威事实作为上下文提供，关闭网关自带 RAG，避免检索到不相关文档导致"超出范围"。
                body.put("use_kb", false);
                var messages = body.putArray("messages");
                messages.addObject().put("role", "system").put("content", systemPrompt);
                messages.addObject().put("role", "user").put("content", userContent);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + ch.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    log.warn("[support-ai] provider {} HTTP {}", ch.channel(), resp.statusCode());
                    continue;
                }
                JsonNode root = JSON.readTree(resp.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                if (!StringUtils.hasText(content)) {
                    content = root.path("choices").path(0).path("message").path("reasoning_content").asText("");
                }
                if (StringUtils.hasText(content)) {
                    disabledUntil = 0;
                    return Optional.of(cleanAnswer(content.trim()));
                }
            } catch (Exception e) {
                log.warn("[support-ai] provider {} failed: {}", ch.channel(), e.getMessage());
            }
        }
        disabledUntil = System.currentTimeMillis() + BREAKER_MS;
        return Optional.empty();
    }

    /** 本地网关（mimosa）优先，其次任一启用渠道。 */
    private List<OutboundModelConfigService.ChannelConfig> channels() {
        List<OutboundModelConfigService.ChannelConfig> list =
                new ArrayList<>(configService.activeChannels());
        list.sort((a, b) -> Integer.compare(
                "mimosa".equalsIgnoreCase(a.channel()) ? 0 : 1,
                "mimosa".equalsIgnoreCase(b.channel()) ? 0 : 1));
        return list;
    }

    /** 去掉模型在回答尾部自行附带的"来源：《…》"、Source 以及"知识库/参考资料"等元话术。 */
    public static String cleanAnswer(String text) {
        if (!StringUtils.hasText(text)) return text;
        String out = text
                // 尾部/独立行的中文来源
                .replaceAll("(?m)^\\s*(?:来源|出处|参考资料)\\s*[:：].*$", "")
                .replaceAll("\\s*(?:来源|出处)\\s*[:：]\\s*《[^》]*》\\s*[。；;，,]?", "")
                // 英文 Source: ...
                .replaceAll("(?mi)^\\s*source\\s*:.*$", "")
                .replaceAll("\\s*source\\s*:\\s*\"?[^\\n\"]*\"?\\s*$", "")
                // 元话术（"根据知识库…"等），避免客服回答暴露检索过程
                .replaceAll("(?:根据|依据|参考)?(?:平台)?(?:知识库|平台资料|参考资料|参考答案)(?:显示|表明|记载|指出)?[，,：:]?", "")
                // 去掉尾部残留的引用标题（如 "、《平台政策 · 商户入驻与开店》"）
                .replaceAll("(?s)\\s*[、,，;；]?\\s*《[^》]{1,80}》\\s*[。；;]?\\s*$", "")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[、,，;；\\s]+$", "")
                .trim();
        return StringUtils.hasText(out) ? out : text.trim();
    }

    /** 是否含中日韩文字：据此让回答语言跟随提问语言（含 CJK→中文，否则英文）。 */
    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff)      // 日文假名
                    || (c >= 0x4e00 && c <= 0x9fff)   // 汉字
                    || (c >= 0xac00 && c <= 0xd7af)   // 韩文
                    || (c >= 0x3400 && c <= 0x4dbf)) return true;  // 扩展 A
        }
        return false;
    }
}

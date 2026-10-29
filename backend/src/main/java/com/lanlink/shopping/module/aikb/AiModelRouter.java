package com.lanlink.shopping.module.aikb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地模型路由器（文本 = qwen2.5-7B / 视觉 = qwen2.5-vl，均由 ai-gateway 暴露）。
 *
 * 文本目标（TEXT）：embeddingService.chatText() —— ai-gateway 的本地 qwen2.5-7B 优先，
 *   失败自动回退 Ollama 轻量文本模型（AI_TEXT_MODEL，默认 0.5b）。
 * 视觉目标（VL）：embeddingService.chatViaGatewayVl() —— 显式 AI_GATEWAY_VL_MODEL（默认 qwen2.5-vl）；
 *   失败回退文本链路（qwen2.5-7B → Ollama）。
 *
 * 意图路由（decideTarget）：
 *   - 显式指定（vl/text）优先；
 *   - auto：仅当问题含视觉意图词（看图/图纸/识别图…）才走 VL；其余一律 TEXT。
 *     默认 TEXT 的原因：短文本问答/客服/SKU 分析等绝大多数请求不需要视觉模型，
 *     若 auto 默认 VL 会与 7B 反复来回切换，实测并发/跨功能切换单请求可达 40s。
 *
 * 注意：本地 7B 由宿主机 llama.cpp 经 ai-gateway 提供（model 名 qwen2.5-7b），
 * Ollama 侧不再默认载入 7B（AI_LOCAL_CHAT_MODEL=qwen2.5:7b 未安装），因此不再做
 * “VL/7B 互斥卸载”，两条链路可同时在线。
 */
@Service
public class AiModelRouter {

    public static final String VL = "vl";
    public static final String TEXT = "text";
    public static final String AUTO = "auto";

    /** 视觉意图词（命中才倾向 VL 模型；auto 默认文本模型） */
    private static final List<String> VISION_INTENT_WORDS =
            List.of("看图", "图片", "图像", "识别图", "图纸", "效果图", "实拍", "照片", "视频");

    private final EmbeddingService embeddingService;
    /** 保留配置项（长上下文统一走文本模型；阈值不再影响 auto 默认值，仅作兼容/展示） */
    private final long longContextChars;
    private final int textNumCtx;
    private final String textKeepAlive;
    /** 当前路由目标（状态展示用；auto 默认文本模型） */
    private volatile String current = TEXT;

    public AiModelRouter(EmbeddingService embeddingService,
                         @Value("${AI_LONG_CONTEXT_CHARS:1200}") long longContextChars,
                         @Value("${AI_TEXT_NUM_CTX:16384}") int textNumCtx,
                         @Value("${AI_TEXT_KEEP_ALIVE:5m}") String textKeepAlive) {
        this.embeddingService = embeddingService;
        this.longContextChars = longContextChars;
        this.textNumCtx = textNumCtx;
        this.textKeepAlive = textKeepAlive;
    }

    public String getCurrent() { return current; }
    public int getTextNumCtx() { return textNumCtx; }

    /**
     * 意图路由判定（纯函数可测）：hint 显式指定优先；auto 仅在含视觉意图词时走 VL，
     * 其余（含长文本/长上下文）默认 TEXT，避免文本请求误触发 VL 与 7B 切换。
     */
    public String decideTarget(String question, String context, String hint) {
        if (TEXT.equals(hint) || VL.equals(hint)) return hint;
        if (question != null) {
            for (String w : VISION_INTENT_WORDS) {
                if (question.contains(w)) return VL;
            }
        }
        return TEXT;
    }

    /**
     * 生成：路由到目标模型，失败串行降级。
     *   text：ai-gateway qwen2.5-7B（chatText 内部失败回退 Ollama 轻量文本模型）；
     *   vl  ：ai-gateway 显式 AI_GATEWAY_VL_MODEL（默认 qwen2.5-vl），失败回退文本链路。
     * @return {answer, backend, target}；answer=null 表示两侧都不可达
     */
    public Map<String, Object> generate(String target, String system, String user) {
        String t = TEXT.equals(target) ? TEXT : VL;
        String answer;
        String backend;
        if (TEXT.equals(t)) {
            current = TEXT;
            answer = embeddingService.chatText(system, user);
            backend = textBackendLabel(answer);
        } else {
            current = VL;
            answer = embeddingService.chatViaGatewayVl(system, user);
            backend = "gateway:" + embeddingService.getGatewayVlModel();
            if (answer == null) { // VL 不可达 → 文本链路（qwen2.5-7B → Ollama 文本模型）
                answer = embeddingService.chatText(system, user);
                backend = textBackendLabel(answer);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answer);
        out.put("backend", backend);
        out.put("target", t);
        return out;
    }

    /** 文本链路的实际后端标签（按最近一次对话命中的后端判定 gateway:xxx / ollama:xxx） */
    private String textBackendLabel(String answer) {
        if (answer == null) return "none";
        String last = embeddingService.getLastChatBackend();
        if (last != null && last.startsWith("ollama:")) {
            return "ollama:" + embeddingService.getTextModel();
        }
        if (last != null && last.startsWith("gateway:")) {
            return "gateway:" + embeddingService.getGatewayTextModel();
        }
        return "gateway:" + embeddingService.getGatewayTextModel();
    }

    /** Ollama 当前已加载模型（状态展示） */
    public List<String> loadedModels() {
        return embeddingService == null ? List.of() : embeddingService.ollamaLoadedModels();
    }

    /**
     * 模型状态（管理端展示，字段名固定）：
     * gatewayModel / gatewayVlModel / gatewayTextModel / textModel / ollamaLoaded / current
     */
    public Map<String, Object> modelStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (embeddingService == null) {
            m.put("gatewayModel", null);
            m.put("gatewayVlModel", null);
            m.put("gatewayTextModel", null);
            m.put("textModel", null);
            m.put("ollamaLoaded", List.of());
        } else {
            m.put("gatewayModel", embeddingService.getGatewayModel());
            m.put("gatewayVlModel", embeddingService.getGatewayVlModel());
            m.put("gatewayTextModel", embeddingService.getGatewayTextModel());
            m.put("textModel", embeddingService.getTextModel());
            m.put("ollamaLoaded", embeddingService.ollamaLoadedModels());
        }
        m.put("current", current);
        return m;
    }
}

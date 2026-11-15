package com.lanlink.shopping.support.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 智能客服复杂度与意图分流仲裁器（三层漏斗分流决策引擎）。
 *
 * <p>决策分级：
 * <ul>
 *   <li>Layer 1 (TIER_1_EXACT_KB): 高频静态知识库 / 权威规则直接命中，耗时 &lt; 50ms，零模型调用成本；</li>
 *   <li>Layer 2 (TIER_2_LOCAL_RAG): 常规单品咨询、规格查询、基础平台操作，走本地 Qwen2.5-7B + MCP 只读工具；</li>
 *   <li>Layer 3 (TIER_3_EXTERNAL_EMBEDDED): 复杂多模态研判、跨商品深度矩阵对比、复杂维权合规纠纷，
 *       经 PII 脱敏与预算门禁后分流至外部 MiMo (mimo-v2.6-flash / mimo-v2.6-pro) 深度嵌入解答。</li>
 * </ul>
 */
@Component
public class SupportComplexityRouter {

    private static final Logger log = LoggerFactory.getLogger(SupportComplexityRouter.class);

    public enum Tier {
        TIER_1_EXACT_KB("本地规则与权威知识库精确直答"),
        TIER_2_LOCAL_RAG("本地 Qwen2.5-7B 模型与 RAG 接地"),
        TIER_3_EXTERNAL_EMBEDDED("外部 MiMo 深度嵌入大模型");

        private final String description;

        Tier(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public record ComplexityDecision(
            Tier tier,
            String reason,
            String recommendedModel,
            boolean isComplexDiverted
    ) {
        public static ComplexityDecision exactKb(String matchedEntryId) {
            return new ComplexityDecision(Tier.TIER_1_EXACT_KB, "权威知识库精确命中: entryId=" + matchedEntryId, "local:kb", false);
        }

        public static ComplexityDecision localRag(String reason) {
            return new ComplexityDecision(Tier.TIER_2_LOCAL_RAG, reason, "local:qwen2.5-7b", false);
        }

        public static ComplexityDecision externalEmbedded(String reason, String model) {
            return new ComplexityDecision(Tier.TIER_3_EXTERNAL_EMBEDDED, reason, model, true);
        }
    }

    // 复杂商品横向比对信号
    private static final List<String> COMPARISON_SIGNALS = List.of(
            "对比", "区别", "哪款更", "哪个好", "横向比较", "参数对比", "哪种划算", "差异在哪", "如何选"
    );

    // 深度维权、争议仲裁与特殊售后信号（优先分配 mimo-v2.6-pro 深度推理）
    private static final List<String> DISPUTE_SIGNALS = List.of(
            "投诉", "维权", "消协", "拒退", "运费险不赔", "不给退", "欺诈", "假货", "索赔", "工单未解决", "工商局", "起诉"
    );

    // 多模态瑕疵研判关键词
    private static final List<String> DEFECT_SIGNALS = List.of(
            "破损", "变形", "开裂", "瑕疵", "残次", "损坏", "真假", "防伪", "开箱"
    );

    /**
     * 仲裁当前用户提问与上下文的多模态复杂度。
     *
     * @param question 用户输入问题
     * @param mediaIds 关联的媒体 ID 列表
     * @param mediaText 媒体接地解析文本
     * @param substantive 意图是否具有实质性业务含义
     * @param kbMatchedEntryId 知识库精确命中的条目 ID (未命中为空)
     * @return 复杂度决策结果
     */
    public ComplexityDecision evaluate(String question,
                                      List<String> mediaIds,
                                      String mediaText,
                                      boolean substantive,
                                      String kbMatchedEntryId) {
        String q = (question == null) ? "" : question.trim();
        String lowerQ = q.toLowerCase(Locale.ROOT);
        boolean hasMedia = mediaIds != null && !mediaIds.isEmpty();
        int mediaCount = hasMedia ? mediaIds.size() : 0;
        String mText = (mediaText == null) ? "" : mediaText;

        // 1. Layer 1 检查：权威知识库精确命中且无任何媒体附件时，直接走极速直答
        if (StringUtils.hasText(kbMatchedEntryId) && !hasMedia) {
            ComplexityDecision decision = ComplexityDecision.exactKb(kbMatchedEntryId);
            log.debug("[complexity-router] 命中 Layer 1: {}", decision.reason());
            return decision;
        }

        // 2. Layer 3 检查：多模态输入复杂度研判
        if (hasMedia) {
            // (a) 多图场景 (>= 2 张图片): 触发跨图比对
            if (mediaCount >= 2) {
                ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                        "多图片跨图综合研判 (" + mediaCount + " 张附件)", "mimo-v2.6-flash");
                log.info("[complexity-router] 触发 Layer 3 多模态分流: {}", decision.reason());
                return decision;
            }
            // (b) 包含视频或复杂文档标志
            if (mText.contains("【视频附件】") || mText.contains("视频摘要") || mText.contains("视频转写")) {
                ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                        "动态视频音画综合理解与故障定位", "mimo-v2.6-flash");
                log.info("[complexity-router] 触发 Layer 3 视频分流: {}", decision.reason());
                return decision;
            }
            // (c) 媒体文本命中瑕疵、外观破损等深度研判信号
            for (String defect : DEFECT_SIGNALS) {
                if (lowerQ.contains(defect) || mText.contains(defect)) {
                    ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                            "商品外观破损/瑕疵研判: 命中特征 [" + defect + "]", "mimo-v2.6-flash");
                    log.info("[complexity-router] 触发 Layer 3 瑕疵分流: {}", decision.reason());
                    return decision;
                }
            }
            // (d) 长篇复杂文档附件 (> 1500 字符)
            if (mText.length() > 1500) {
                ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                        "长篇文档深度切片理解 (长 " + mText.length() + " 字符)", "mimo-v2.6-flash");
                log.info("[complexity-router] 触发 Layer 3 文档分流: {}", decision.reason());
                return decision;
            }
        }

        // 3. Layer 3 检查：文本语义复杂度研判
        // (a) 深度维权与纠纷仲裁（选用 mimo-v2.6-pro）
        for (String dispute : DISPUTE_SIGNALS) {
            if (lowerQ.contains(dispute)) {
                ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                        "复杂售后争议维权与合规推演: 命中关键词 [" + dispute + "]", "mimo-v2.6-pro");
                log.info("[complexity-router] 触发 Layer 3 维权分流: {}", decision.reason());
                return decision;
            }
        }
        // (b) 复杂横向比对
        for (String comp : COMPARISON_SIGNALS) {
            if (lowerQ.contains(comp)) {
                ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                        "跨品类/多规格横向矩阵比对: 命中关键词 [" + comp + "]", "mimo-v2.6-flash");
                log.info("[complexity-router] 触发 Layer 3 对比分流: {}", decision.reason());
                return decision;
            }
        }
        // (c) 长文本多重疑问复杂问题 (>= 120 字符且包含复合意图)
        if (q.length() >= 120 && (lowerQ.contains("？") || lowerQ.contains("?") || lowerQ.contains("但是"))) {
            ComplexityDecision decision = ComplexityDecision.externalEmbedded(
                    "多轮复合长提问推理 (" + q.length() + " 字符)", "mimo-v2.6-flash");
            log.info("[complexity-router] 触发 Layer 3 复合问答分流: {}", decision.reason());
            return decision;
        }

        // 4. Layer 2 默认归属：常规单品咨询、基础流程指引，由本地 Qwen2.5-7B 模型与 RAG 接地回答
        ComplexityDecision decision = ComplexityDecision.localRag("常规单品参数咨询或基础业务操作指引");
        log.debug("[complexity-router] 归入 Layer 2 本地处理: {}", decision.reason());
        return decision;
    }
}

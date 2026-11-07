package com.lanlink.shopping.module.techservice.service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AI 回答的合规后置判定（输出侧硬闸）。
 *
 * <p>为什么必须有：知识库里的服务目录条目会写「成果评价材料辅导」这类能力描述，
 * 模型很容易把它复述成「平台可以出具评价报告」。2026-11-04 容器实测即出现该越界回答，
 * 而 system prompt 里已经写了边界条款——说明纯提示词约束不足以兜底，返回前必须再判一次。
 *
 * <p>判定刻意收紧到「平台自居为结论/背书主体」这类明确越界，宁可漏判也不误伤合规回答；
 * 否定式表述（不构成结论、不代办、由机构出具）先摘掉再匹配，避免把免责声明读成承诺。
 */
public final class TechAnswerBoundary {

    private static final List<Pattern> VIOLATIONS = List.of(
            // 平台自居为评价/鉴定结论的出具主体
            Pattern.compile("(平台|我们|本公司)[^。；\\n]{0,12}(可以|能够|可|提供|出具|负责|进行)[^。；\\n]{0,10}(评价报告|鉴定报告|评价结论|鉴定结论|评估结论)"),
            // 政府背书与指定
            Pattern.compile("(国家级|政府指定|政府认定|官方唯一|官方指定|官方背书|科技部指定)"),
            // 成交与获批担保
            Pattern.compile("(包授权|包下证|包成交|保证成交|承诺成交|100%成交|保证收益|保证获批)"),
            // 平台自称代办持牌业务或代收资金
            Pattern.compile("(我们|平台)[^。；\\n]{0,8}(代办)[^。；\\n]{0,8}(专利|申请|复审|无效)"),
            Pattern.compile("(平台|我们)[^。；\\n]{0,10}(代收|归集|托管)[^。；\\n]{0,8}(交易款|资金|本金)"));

    /** 出现即视为在否定该能力，不构成越界 */
    private static final List<Pattern> NEGATIONS = List.of(
            Pattern.compile("不(构成|属于)[^。；\\n]{0,6}(评估|评价|鉴定)?结论"),
            Pattern.compile("(不|未)(可以|能|会)[^。；\\n]{0,8}(出具|提供|办理)"),
            Pattern.compile("(不|未)(归集|代收|托管|宣称|指定|认可|代办|出具|从事)[^。；\\n]{0,12}"),
            Pattern.compile("不是[^。；\\n]{0,8}(交易场所|持牌机构|评估机构)"),
            Pattern.compile("(由|须由|需由)[^。；\\n]{0,16}(机构|第三方|主管部门)[^。；\\n]{0,10}(出具|办理|答复)"),
            Pattern.compile("(不代办|不作为|不做|不涉及|不承担)[^。；\\n]{0,12}"),
            Pattern.compile("(须|需|应)[^。；\\n]{0,10}(由|经)[^。；\\n]{0,16}办理"));

    private static final String BOUNDARY_ANSWER =
            "该问题涉及平台的业务边界，我不能按此口径作答：平台只是技术信息的发布与协作工具，"
                    + "不是交易场所；专利代理（申请、复审、无效、著录变更、许可备案）须由持牌代理机构承办；"
                    + "科技成果评价或鉴定结论须由具备相应能力的第三方机构出具，平台仅提供材料组织、"
                    + "流程跟踪与快筛参考，快筛结果不构成评估结论；平台不归集或代收交易资金，"
                    + "也不宣称政府背书、指定或认可。建议由技术经纪人对接相应持牌机构办理。";

    private TechAnswerBoundary() {
    }

    /** 命中返回该越界片段；无越界返回 empty。 */
    public static Optional<String> violationOf(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        String scannable = stripNegations(answer);
        for (Pattern p : VIOLATIONS) {
            var m = p.matcher(scannable);
            if (m.find()) return Optional.of(m.group());
        }
        return Optional.empty();
    }

    /** 越界时替换成边界答复合成文本 */
    public static String enforce(String answer) {
        return violationOf(answer).isPresent() ? BOUNDARY_ANSWER : answer;
    }

    public static String boundaryAnswer() {
        return BOUNDARY_ANSWER;
    }

    private static String stripNegations(String answer) {
        String out = answer;
        for (Pattern n : NEGATIONS) {
            out = n.matcher(out).replaceAll("");
        }
        return out;
    }
}

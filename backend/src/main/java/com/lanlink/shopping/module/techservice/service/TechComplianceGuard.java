package com.lanlink.shopping.module.techservice.service;

import com.lanlink.shopping.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技术服务业务边界守卫。
 *
 * <p>依据公开监管要求固化「平台自营 / 需合作方 / 禁止」三类边界：
 * <ul>
 *   <li>科技成果评价：2016 年《科学技术成果鉴定办法》废止后转为社会化评价，
 *       平台不得以「鉴定机构」名义自出结论，评价结论须由具备能力的第三方机构出具；</li>
 *   <li>专利代理：申请、复审、无效、著录项目变更、许可备案须由持证专利代理机构承办；</li>
 *   <li>技术交易：技术合同认定登记由登记机构办理，交易鉴证/结算由交易场所或持牌机构办理；</li>
 *   <li>资金：平台不得归集交易本金形成资金池（二清风险），只收服务费；</li>
 *   <li>宣传：《广告法》第 9 条禁止使用「国家级」「政府指定」等用语。</li>
 * </ul>
 *
 * <p>本守卫对用户提交的自由文本做前置校验，拦截明显越界的承诺性表述，
 * 同时为前端提供统一的边界清单，避免运营侧在不同页面写出不一致的话术。
 */
@Component
public class TechComplianceGuard {

    /** 违规宣传/承诺性表述（命中即拒绝写入，避免平台承担无法兑现的背书责任） */
    private static final List<String> FORBIDDEN_CLAIMS = List.of(
            // 覆盖《广告法》第 9 条明令禁止的「国家级」表述（含「国家级平台」「国家级认定」等组合）
            "国家级", "政府指定", "政府认定", "官方唯一", "官方指定",
            "官方背书", "科技部指定", "包授权", "包下证", "包成交", "保证成交",
            "承诺成交", "100%成交", "保证收益", "保证获批");

    /**
     * 违法服务与黑产能力词。清单刻意保持窄口径：只收「在合规技术交易里没有正当用途」的表述，
     * 不收单字、不收行业通用词，避免把正常工程描述误判成违规。
     */
    private static final List<String> SENSITIVE_TERMS = List.of(
            "枪支", "弹药", "毒品", "易制毒", "管制刀具", "赌博平台", "洗钱", "跑分套现",
            "接码平台", "虚开发票", "假发票", "私刻印章", "代办证件", "窃听器材", "跟踪定位器",
            "拖库", "撞库", "黑客服务", "刷单", "网络水军");

    /**
     * 匹配前先摘掉的合规语境短语。理由：「反洗钱」「洗钱监测」是风控行业的正当技术方向，
     * 与黑产词面重合；不摘掉就会把这类真实需求整条拒掉。
     */
    private static final List<String> SENSITIVE_ALLOW_CONTEXT = List.of(
            "反洗钱", "洗钱监测", "洗钱风险", "洗钱追踪", "打击洗钱", "洗钱犯罪");

    /**
     * 校验用户提交文本：先拦承诺性违规表述，再拦违法服务/黑产能力。
     * 命中即拒绝写入；调用点已覆盖需求/帖子/专家简介/托管范围等全部自由文本写入。
     *
     * @param field 字段中文名（用于错误提示）
     * @param text  待校验文本（可为 null）
     */
    public void validate(String field, String text) {
        if (text == null || text.isBlank()) return;
        for (String claim : FORBIDDEN_CLAIMS) {
            if (text.contains(claim)) {
                throw new BusinessException(400,
                        field + "包含不合规表述「" + claim + "」。平台不得使用政府背书、成交担保类承诺，请改为客观描述服务内容。");
            }
        }
        String scannable = stripAllowedContext(text);
        for (String term : SENSITIVE_TERMS) {
            if (scannable.contains(term)) {
                throw new BusinessException(400,
                        field + "包含不允许的内容「" + term + "」。平台不得发布违法违规服务或黑产能力，请删除后重新提交。");
            }
        }
    }

    private static String stripAllowedContext(String text) {
        String out = text;
        for (String allowed : SENSITIVE_ALLOW_CONTEXT) {
            out = out.replace(allowed, "");
        }
        return out;
    }

    /** 对外暴露的边界清单（前端「合规边界」区块与后台审核提示共用同一数据源） */
    public Map<String, Object> boundary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("forbiddenClaims", FORBIDDEN_CLAIMS);
        out.put("sensitiveTerms", SENSITIVE_TERMS);
        out.put("items", List.of(
                boundaryItem("技术交流", "活动策划、报名、内容运营、供需墙",
                        "以政府名义主办、承诺评奖或立项、收取背书费",
                        "大型活动按属地要求报批", "政府/园区/协会/交易场所"),
                boundaryItem("专业技术咨询", "技术诊断、方案建议、专家咨询、报告编制",
                        "出具鉴定或评价结论；无资质从事专利代理、资产评估",
                        "咨询报告注明建议性", "学会/协会/高校专家/评估机构"),
                boundaryItem("技术供需对接", "需求发布、画像匹配、撮合谈判、合同起草辅导",
                        "收双向暗佣、承诺成交、代收交易款",
                        "技术经纪人培训与备案（非强制持证）", "交易场所/技术转移机构"),
                boundaryItem("科技成果评价", "材料组织、专家邀约、会议组织、过程留痕、进度跟踪",
                        "以平台名义出具评价结论、自称国家认可评价机构",
                        "无全国统一评价资质许可", "科技评估中心/学会协会/资产评估机构"),
                boundaryItem("沉睡专利盘活", "盘点分级、技术画像、转化方案、买家匹配、路演",
                        "伪造专利数据；未经授权披露企业商业秘密",
                        "数据分类分级与权限审计", "高校技术转移中心/专利盘活系统"),
                boundaryItem("知识产权服务机构托管", "台账、期限提醒、年费测算、机构比选、质量监督",
                        "以「专利代理」名义办理申请、复审、无效、著录变更、许可备案",
                        "专利代理机构执业许可证（代理业务）", "持牌专利代理机构"),
                boundaryItem("SaaS 工具型托管", "专利/合同/需求/评价流程工具、数据看板、预警",
                        "无证提供支付结算或金融产品",
                        "ICP/EDI（如涉及第三方入驻）、等保", "云服务商/持牌支付机构"),
                boundaryItem("技术收储-托管-交易", "非国有专利收储、受托运营、挂牌推荐、交易服务",
                        "私下买断国有专利、绕开进场程序、自建交易场所、归集交易资金",
                        "交易场所须经批准；收储需尽调与减值机制", "上海技术交易所等交易场所")
        ));
        out.put("notice", "以上边界依据公开政策与监管要求整理，具体合作以书面协议及属地主管部门要求为准；"
                + "平台不替代持牌机构出具法定结论，也不承诺交易结果。");
        return out;
    }

    private Map<String, Object> boundaryItem(String module, String allowed, String forbidden,
                                             String qualification, String partner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", module);
        m.put("allowed", allowed);
        m.put("forbidden", forbidden);
        m.put("qualification", qualification);
        m.put("partner", partner);
        return m;
    }

    /**
     * AI 助手的边界条款（中/英）。必须在构建 system prompt 时**恒定注入**，
     * 不能只作为可选召回的知识块：知识块是否进入上下文取决于检索命中，
     * 一旦没命中，模型就会把「评价结论」「资金监管」这类事项当成平台自身能力来答。
     *
     * <p>与 {@link #boundary()} 同源的是口径，不是同一段文字——这里是给模型的短指令。
     */
    public static String aiBoundaryClauseZh() {
        return "【技术服务业务边界】平台仅是技术信息的发布与协作工具，不是交易场所，"
                + "不承办专利代理（申请/复审/无效/著录变更/许可备案），不以平台名义出具科技成果评价或鉴定结论，"
                + "不归集或代收交易资金，不宣称政府背书、指定或认可。"
                + "涉及上述事项时，只能回答「须由具备资质的机构办理」，"
                + "并使用「拟合作/生态参考」表述，禁止「国家级」「官方指定」「包授权」「保证成交」等说法。"
                + "标注为演示数据、未核验或未签约的内容不得作为事实、资质、合作方或政策依据引用；"
                + "若只有这类内容可引，应回答「该信息为演示样例，暂无可核实依据」。";
    }

    public static String aiBoundaryClauseEn() {
        return "[Technology-service boundary] The platform is only an information and collaboration tool. "
                + "It is not a trading venue, does not act as a patent agency, does not issue technology "
                + "appraisal or verification conclusions in its own name, does not pool or collect "
                + "transaction funds, and never claims government endorsement or designation. For such "
                + "matters answer only that a licensed institution must handle them, describe institutions "
                + "as intended partners or ecosystem references, and never use wording such as "
                + "national-level, officially designated, guaranteed grant or guaranteed deal. "
                + "Content marked as demo, unverified or unsigned must not be cited as fact, credential, "
                + "partner or policy basis; if it is all that matched, say it is demo data with no "
                + "verifiable basis.";
    }
}

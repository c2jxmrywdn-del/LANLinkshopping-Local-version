package com.lanlink.shopping.module.techservice.service;

import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 技术服务知识块组合器（纯文本拼装，不碰向量库）。
 *
 * <p>为什么单独成类：技术服务有 7 类可被 AI 引用的实体，字段到自然语言的映射规则
 * 需要独立可测；而入库、幂等、权重属于 aikb 侧关注点，不应混进这里。
 */
@Component
public class TechKnowledgeComposer {

    /** source 即检索与权限粒度：每类一源，便于「只查专家」这类限定检索与按类重灌 */
    public static final String SOURCE_EXPERT = "tech-expert";
    public static final String SOURCE_ORGANIZATION = "tech-org";
    public static final String SOURCE_SERVICE = "tech-service";
    public static final String SOURCE_DEMAND = "tech-demand";
    public static final String SOURCE_ACHIEVEMENT = "tech-achievement";
    public static final String SOURCE_POLICY = "tech-policy";
    public static final String SOURCE_POST = "tech-post";
    /** 机制说明型语料：实体画像答不了「怎么办」，这类块专供流程/边界问题召回 */
    public static final String SOURCE_MANUAL = "tech-manual";

    /** aikb 侧 CHUNK_MAX=1200；在这里就收口，避免下游 clip 掉尾部可信标记 */
    static final int MAX_CHUNK_CHARS = 1180;

    /** 易变数值不入向量文本：价格、费率、评分、浏览量都会变，写进去等于让模型报过期数字 */
    private static final String LIVE_DATA_SUFFIX = "｜价格、费率、评分与响应时长等为实时数据，命中后请回源详情页核对";

    public record Chunk(String source, String refId, String title, String text) {
    }

    private final TechComplianceGuard complianceGuard;

    /** 无参构造保持既有调用与测试不变；服务线块需要一个无状态的边界数据源 */
    public TechKnowledgeComposer() {
        this(new TechComplianceGuard());
    }

    public TechKnowledgeComposer(TechComplianceGuard complianceGuard) {
        this.complianceGuard = complianceGuard;
    }

    /**
     * 机制说明块。内容对应代码里真实存在的链路（申请/邀请→审核→上架、
     * 里程碑推进、快筛实现于 {@code TechAchievementAssessor}），写成文字是为了让模型能回答
     * 「怎么引入专家」「陪跑包含哪些节点」这类问题——实体画像块答不了。
     */
    public List<Chunk> manualChunks() {
        List<Chunk> out = new java.util.ArrayList<>();
        out.add(manual("manual:expert-onboarding", "技术服务专家与技术经纪人引入机制",
                "【平台运营机制，非任何资质或背书声明】专家与技术经纪人进入平台资源池有两条路径："
                        + "一是平台定向邀请，受邀记录直接以已核验状态上架；二是由用户提交入驻申请，"
                        + "携带姓名、职称、所属单位、擅长行业、技术领域、个人简介与资质材料，进入待审核状态。"
                        + "平台对申请材料做形式核验后给出审核结论：通过则上架并标记已核验；"
                        + "驳回必须写明原因并写入审核留痕，申请人可补正后重新提交，记录保留可追溯。"
                        + "平台只做形式核验与流程管理，不代为评定职称、不认证任何官方头衔，"
                        + "也不因某位专家入驻而宣称该专家受政府或国家级机构指定。"
                        + "用户可在专家目录浏览已上架的专家与技术经纪人，并发起咨询或对接请求。"));
        out.add(manual("manual:accompaniment", "全流程陪跑项目的阶段与里程碑",
                "【平台运营机制】需求发布、专业咨询、成果评价、机构托管、技术交易等申请一旦提交，"
                        + "平台会自动生成一个陪跑项目并初始化四个交付里程碑，典型节点为需求诊断、资源匹配、"
                        + "对接洽谈、签约转化；项目阶段按受理、需求诊断、资源匹配、尽职调查、合同签署、"
                        + "交付执行、验收、归档推进，进度百分比等于已完成里程碑数除以里程碑总数。"
                        + "运营可指派技术经纪人作为项目负责人并指定责任专家；每个里程碑需回填交付物与完成时间，"
                        + "由项目负责人与用户双方确认后进入下一节点。平台侧记录仅作过程留痕，"
                        + "不代表对技术结果或成交作出任何保证。"));
        out.add(manual("manual:screening-vs-appraisal", "AI 快筛与正式成果评价的分工",
                "【平台运营机制】平台对成果与专利提供的是确定性规则快筛：从法律状态、技术成熟度、"
                        + "经济价值与转化成熟度四个维度加权打分，给出综合分与分级、理由，并附标准参考说明。"
                        + "该快筛方法参考 GB/T 44731-2024《科技成果评估规范》与 GB/T 42748-2023《专利评估指引》"
                        + "的方法框架，但**不构成评估结论、定价依据或投资建议**；每次返回都会附带免责声明。"
                        + "正式评价须由具备相应能力的第三方评价机构按其程序出具报告，平台只负责组织材料、"
                        + "邀约专家、组织会议、过程留痕与进度跟踪，报告和结论署名为该机构，平台不在结论上署名。"));
        out.add(manual("manual:escrow-cycle", "技术收储—托管—交易闭环中的角色边界",
                "【平台运营机制】在收储、托管与交易链路中，平台提供专利盘点分级、技术画像、许可或转让方案设计、"
                        + "买家匹配、流程与合同模板辅导、以及交易进度的可视化跟踪。"
                        + "法定与持牌环节由外部机构承担：技术合同认定登记由登记机构办理，"
                        + "交易鉴证与资金结算由技术交易场所或持牌支付机构办理，专利权转让登记与质押登记由国家知识产权局办理，"
                        + "专利代理业务由持牌代理机构承办。平台不代收或归集交易本金，只提供服务费发票与流程记录；"
                        + "交易证据摘要（含单号、标的、金额、双方与时间）以哈希形式留存用于纠纷举证，"
                        + "该哈希由平台算法计算，本身不具备司法效力，出具鉴定意见须委托有资质机构。"));
        return out;
    }

    /**
     * 八条服务线的边界块：与运营台展示的是同一份 {@link TechComplianceGuard#boundary()} 数据，
     * 避免「AI 说的」和「后台审核提示的」不一致。每块同时带上能做与不可做，
     * 否则模型只记得能力、不记得边界。
     */
    @SuppressWarnings("unchecked")
    public List<Chunk> boundaryChunks() {
        List<Map<String, Object>> items = (List<Map<String, Object>>) complianceGuard.boundary().get("items");
        List<Chunk> out = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            String module = String.valueOf(item.get("module"));
            StringBuilder sb = new StringBuilder("【技术服务业务边界，平台自我约束条款】");
            append(sb, "服务线", module);
            append(sb, "平台可提供", str(item.get("allowed")));
            append(sb, "平台不得涉及", str(item.get("forbidden")));
            append(sb, "资质与合规注意", str(item.get("qualification")));
            append(sb, "对应合作对象", str(item.get("partner")));
            out.add(manual("line:" + module, "业务边界 " + module, sb.toString()));
        }
        return out;
    }

    private Chunk manual(String refId, String title, String text) {
        String clipped = text.length() <= MAX_CHUNK_CHARS ? text : text.substring(0, MAX_CHUNK_CHARS - 1) + "…";
        return new Chunk(SOURCE_MANUAL, refId, title, clipped);
    }

    public List<Chunk> fromExpert(TechExpert e) {
        if (e == null || e.getExpertId() == null) return List.of();
        StringBuilder sb = new StringBuilder(trustOfExpert(e));
        append(sb, "姓名", e.getName());
        append(sb, "身份", "BROKER".equals(e.getExpertType()) ? "技术经纪人" : "技术专家");
        append(sb, "职称", e.getTitle());
        append(sb, "单位", e.getOrganization());
        append(sb, "覆盖行业", e.getIndustries());
        append(sb, "服务领域", e.getDomains());
        append(sb, "可提供服务", "技术诊断、供需撮合、成果评价材料组织、政策申报辅导");
        append(sb, "资质与履历", e.getCredentials());
        append(sb, "简介", e.getBio());
        return single(SOURCE_EXPERT, String.valueOf(e.getExpertId()),
                "技术服务专家 " + safe(e.getName()), sb + LIVE_DATA_SUFFIX);
    }

    public List<Chunk> fromOrganization(TechOrganization o) {
        if (o == null || o.getOrgId() == null) return List.of();
        StringBuilder sb = new StringBuilder(trustOfOrg(o));
        append(sb, "机构名称", o.getName());
        append(sb, "机构类型", orgTypeLabel(o.getOrgType()));
        append(sb, "持有资质", o.getQualifications());
        append(sb, "所在地区", o.getRegion());
        append(sb, "业务简介", o.getIntro());
        // 证照号是平台核验材料，不是可召回知识；是否已签约只以 partnershipStatus 表述
        return single(SOURCE_ORGANIZATION, String.valueOf(o.getOrgId()),
                "专业服务机构 " + safe(o.getName()), sb.toString());
    }

    public List<Chunk> fromServiceProduct(TechServiceProduct p) {
        if (p == null || p.getServiceId() == null) return List.of();
        StringBuilder sb = new StringBuilder("【平台服务目录】");
        append(sb, "服务名称", p.getTitle());
        append(sb, "一句话说明", p.getSubtitle());
        append(sb, "服务类别", p.getCategory());
        append(sb, "提供方类型", "EXPERT".equals(p.getProviderType()) ? "专家/经纪人" : "专业机构");
        append(sb, "服务内容", p.getDescription());
        append(sb, "交付物", p.getDeliverables());
        append(sb, "标签", p.getTags());
        append(sb, "亮点", p.getHighlight());
        append(sb, "边界", "服务定价与周期以详情页为准；平台不代办专利代理、不出具评价结论、不归集交易资金");
        return single(SOURCE_SERVICE, String.valueOf(p.getServiceId()),
                "技术服务产品 " + safe(p.getTitle()), sb + LIVE_DATA_SUFFIX);
    }

    public List<Chunk> fromDemand(TechDemand d) {
        if (d == null || d.getDemandId() == null) return List.of();
        StringBuilder sb = new StringBuilder("【企业公开技术需求，平台未对内容真实性负责】");
        append(sb, "需求编号", d.getDemandNo());
        append(sb, "需求标题", d.getTitle());
        append(sb, "行业", d.getIndustry());
        append(sb, "技术领域", d.getTechField());
        append(sb, "问题描述", d.getDescription());
        append(sb, "痛点", d.getPainPoints());
        append(sb, "期望成果", d.getExpectedOutcome());
        append(sb, "当前状态", demandStatusLabel(d.getStatus()));
        // 预算区间与截止时间易变且涉及商业敏感，不进向量文本
        return single(SOURCE_DEMAND, String.valueOf(d.getDemandId()),
                "技术需求 " + safe(d.getTitle()), sb.toString());
    }

    public List<Chunk> fromAchievement(TechAchievement a) {
        if (a == null || a.getAchievementId() == null) return List.of();
        StringBuilder sb = new StringBuilder(trustOfAchievement(a));
        append(sb, "成果名称", a.getTitle());
        append(sb, "成果类型", a.getAchievementType());
        append(sb, "专利号", a.getPatentNo());
        append(sb, "专利类型", a.getPatentType());
        append(sb, "法律状态", a.getLegalStatus());
        append(sb, "权利人", a.getOwners());
        append(sb, "行业", a.getIndustry());
        append(sb, "技术领域", a.getTechField());
        append(sb, "成熟度", a.getMaturityLevel() == null ? null : "TRL " + a.getMaturityLevel());
        append(sb, "拟转化方式", a.getLicensingMode());
        append(sb, "应用场景", a.getApplicationScenarios());
        append(sb, "技术亮点", a.getHighlights());
        append(sb, "摘要", a.getSummary());
        append(sb, "沉睡状态", Integer.valueOf(1).equals(a.getDormant()) ? "长期未转化（沉睡专利）" : "在转化中");
        append(sb, "边界", "本条为平台整理的成果画像，不含评价结论；正式评价须由具备能力的第三方机构出具报告");
        return single(SOURCE_ACHIEVEMENT, String.valueOf(a.getAchievementId()),
                "技术成果 " + safe(a.getTitle()), sb + LIVE_DATA_SUFFIX);
    }

    public List<Chunk> fromPolicy(com.lanlink.shopping.module.techservice.entity.TechPolicy p) {
        if (p == null || p.getPolicyId() == null) return List.of();
        StringBuilder sb = new StringBuilder("【政策公开信息，平台仅整理；具体申报与补助以主管部门答复为准，不构成任何获批承诺】");
        append(sb, "政策名称", p.getTitle());
        append(sb, "发布机关", p.getIssuer());
        append(sb, "文号", p.getDocNo());
        append(sb, "适用地区", p.getRegion());
        append(sb, "政策类别", p.getCategory());
        append(sb, "发布日期", p.getPublishDate() == null ? null : p.getPublishDate().toString());
        append(sb, "要点", p.getSummary());
        append(sb, "与本平台的关联", p.getHighlight());
        append(sb, "原文", p.getUrl());
        return single(SOURCE_POLICY, String.valueOf(p.getPolicyId()),
                "政策 " + safe(p.getTitle()), sb.toString());
    }

    public List<Chunk> fromPost(TechPost post) {
        if (post == null || post.getPostId() == null) return List.of();
        StringBuilder sb = new StringBuilder("【社区交流内容，作者个人观点，不代表平台立场或承诺】");
        append(sb, "内容类型", postLabel(post.getPostType()));
        append(sb, "标题", post.getTitle());
        append(sb, "行业", post.getIndustry());
        append(sb, "标签", post.getTags());
        append(sb, "作者", post.getAuthorName());
        append(sb, "作者身份", post.getAuthorTitle());
        append(sb, "正文", post.getContent());
        return single(SOURCE_POST, String.valueOf(post.getPostId()),
                "技术社区帖 " + safe(post.getTitle()), sb.toString());
    }

    // ==================== 可信标记 ====================

    private static String trustOfExpert(TechExpert e) {
        return Integer.valueOf(1).equals(e.getVerified())
                ? "【平台形式核验专家，可对外展示】"
                : "【未核验/演示档案，不可作为资质或合作事实引用】";
    }

    private static String trustOfOrg(TechOrganization o) {
        String status = o.getPartnershipStatus() == null ? "" : o.getPartnershipStatus().trim().toUpperCase();
        return switch (status) {
            case "PARTNER" -> "【已签约合作机构】";
            case "REFERENCE" -> "【公开资料参考，尚未签约】";
            default -> "【意向/演示机构，未签约，不可作为平台合作方事实引用】";
        };
    }

    private static String trustOfAchievement(TechAchievement a) {
        return Integer.valueOf(1).equals(a.getVerified())
                ? "【平台已核对权属材料】"
                : "【未核验成果，权属与法律状态需回源确认】";
    }

    // ==================== 文本装配 ====================

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append('｜');
        sb.append(label).append(": ").append(value.trim());
    }

    private static List<Chunk> single(String source, String refId, String title, String text) {
        String clipped = text.length() <= MAX_CHUNK_CHARS ? text : text.substring(0, MAX_CHUNK_CHARS - 1) + "…";
        return List.of(new Chunk(source, refId, title, clipped));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未命名" : value.trim();
    }

    private static String orgTypeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "AGENCY" -> "知识产权代理机构";
            case "EVALUATION" -> "科技成果评价机构";
            case "EXCHANGE" -> "技术交易场所";
            case "UNIVERSITY" -> "高校/院所";
            default -> type;
        };
    }

    private static String demandStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "MATCHING" -> "匹配中";
            case "IN_PROGRESS" -> "对接中";
            case "CLOSED" -> "已关闭";
            default -> status;
        };
    }

    private static String postLabel(String type) {
        return switch (type == null ? "" : type) {
            case "QUESTION" -> "提问";
            case "CASE" -> "案例分享";
            case "INSIGHT" -> "观点";
            case "DEMAND" -> "需求线索";
            default -> "交流";
        };
    }
}

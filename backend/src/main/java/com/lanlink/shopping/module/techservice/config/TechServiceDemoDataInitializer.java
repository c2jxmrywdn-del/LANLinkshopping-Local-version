package com.lanlink.shopping.module.techservice.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPolicy;
import com.lanlink.shopping.module.techservice.entity.TechPost;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechServiceProduct;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechConsultationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechHostingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechMilestoneMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPolicyMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPostMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(2)
public class TechServiceDemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TechServiceDemoDataInitializer.class);

    @Value("${lanlink.demo-data.enabled:false}")
    private boolean enabled;

    private final UserMapper userMapper;
    private final TechExpertMapper expertMapper;
    private final TechOrganizationMapper organizationMapper;
    private final TechServiceProductMapper serviceProductMapper;
    private final TechDemandMapper demandMapper;
    private final TechAchievementMapper achievementMapper;
    private final TechProjectMapper projectMapper;
    private final TechMilestoneMapper milestoneMapper;
    private final TechAppraisalMapper appraisalMapper;
    private final TechHostingMapper hostingMapper;
    private final TechDealMapper dealMapper;
    private final TechConsultationMapper consultationMapper;
    private final TechPolicyMapper policyMapper;
    private final TechPostMapper postMapper;

    public TechServiceDemoDataInitializer(UserMapper userMapper,
                                          TechExpertMapper expertMapper,
                                          TechOrganizationMapper organizationMapper,
                                          TechServiceProductMapper serviceProductMapper,
                                          TechDemandMapper demandMapper,
                                          TechAchievementMapper achievementMapper,
                                          TechProjectMapper projectMapper,
                                          TechMilestoneMapper milestoneMapper,
                                          TechAppraisalMapper appraisalMapper,
                                          TechHostingMapper hostingMapper,
                                          TechDealMapper dealMapper,
                                          TechConsultationMapper consultationMapper,
                                          TechPolicyMapper policyMapper,
                                          TechPostMapper postMapper) {
        this.userMapper = userMapper;
        this.expertMapper = expertMapper;
        this.organizationMapper = organizationMapper;
        this.serviceProductMapper = serviceProductMapper;
        this.demandMapper = demandMapper;
        this.achievementMapper = achievementMapper;
        this.projectMapper = projectMapper;
        this.milestoneMapper = milestoneMapper;
        this.appraisalMapper = appraisalMapper;
        this.hostingMapper = hostingMapper;
        this.dealMapper = dealMapper;
        this.consultationMapper = consultationMapper;
        this.policyMapper = policyMapper;
        this.postMapper = postMapper;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        if (expertMapper.selectCount(null) > 0) {
            log.info("技术服务演示数据已存在，跳过初始化");
            return;
        }
        log.info("==== 初始化技术服务演示数据 ====");
        Long buyerId = userId("13900000001", 1L);
        Long ownerId = userId("13700000002", 2L);
        Long adminId = userId("13800000000", 3L);

        List<TechExpert> experts = seedExperts();
        List<TechOrganization> orgs = seedOrganizations();
        seedServices(experts, orgs);
        seedPolicies();
        seedPosts(buyerId);
        List<TechAchievement> achievements = seedAchievements(ownerId);
        seedDemands(buyerId);

        TechProject p1 = seedProject(buyerId, "MATCH", "寻找高精度机器人视觉标定技术", "MATCHING", "ACTIVE",
                50, experts.get(0).getExpertId(), experts.get(3).getExpertId(), new BigDecimal("800000"),
                new BigDecimal("15.00"), "等待完成：对接洽谈",
                new String[]{"需求诊断", "资源匹配", "对接洽谈", "签约转化"},
                new String[]{"DONE", "DONE", "IN_PROGRESS", "PENDING"});
        TechProject p2 = seedProject(buyerId, "APPRAISAL", "高导热石墨烯复合散热膜成果评价", "MATCHING", "ACTIVE",
                25, null, experts.get(3).getExpertId(), BigDecimal.ZERO, BigDecimal.ZERO,
                "等待完成：专家评审",
                new String[]{"材料预审", "专家评审", "报告出具", "异议处理"},
                new String[]{"DONE", "IN_PROGRESS", "PENDING", "PENDING"});
        TechProject p3 = seedProject(buyerId, "HOSTING", "沉睡专利盘活托管 · 石墨烯散热膜", "EXECUTION", "ACTIVE",
                75, null, experts.get(5).getExpertId(), new BigDecimal("2999"), new BigDecimal("20.00"),
                "等待完成：阶段复盘",
                new String[]{"权属核查", "托管协议", "运营计划", "阶段复盘"},
                new String[]{"DONE", "DONE", "DONE", "IN_PROGRESS"});
        TechProject p4 = seedProject(buyerId, "TRANSACTION", "技术交易 · 工业设备多源振动故障诊断系统", "CONTRACT", "ACTIVE",
                50, experts.get(0).getExpertId(), experts.get(4).getExpertId(), new BigDecimal("600000"),
                new BigDecimal("15.00"), "等待完成：合同签署",
                new String[]{"意向确认", "尽职调查", "合同签署", "资金监管与交割"},
                new String[]{"DONE", "DONE", "IN_PROGRESS", "PENDING"});

        seedAppraisals(buyerId, achievements, orgs, p2);
        seedHostings(buyerId, achievements, orgs, p3);
        seedDeals(buyerId, ownerId, achievements, p4);
        seedConsultations(buyerId, experts);
        log.info("技术服务演示数据初始化完成：专家 {}、机构 {}、需求 {}、成果 {}、项目 {}",
                experts.size(), orgs.size(), 6, achievements.size(), 4);
    }

    private Long userId(String phone, Long fallback) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getPhone, phone).last("LIMIT 1"));
        return user == null ? fallback : user.getUserId();
    }

    private List<TechExpert> seedExperts() {
        List<TechExpert> out = new ArrayList<>();
        out.add(expert("EXPERT", "周启明", "教授 / 博士生导师", "上海交通大学 机械与动力工程学院",
                "智能制造,机器人", "机器视觉,精密装配,工业机器人",
                "长期从事机器人视觉引导与精密装配研究，主持多项产学研合作项目，擅长把实验室算法转化为产线可用的工艺方案。",
                "国家重点研发计划课题负责人；授权发明专利 30 余项；曾为多家汽车零部件企业提供技术诊断。",
                1, "ACTIVE", "4.90", 68, "1200", 6, 100));
        out.add(expert("EXPERT", "林嘉怡", "副教授", "华东理工大学 材料科学与工程学院",
                "新材料,绿色低碳", "高分子复合材料,导热材料,功能膜",
                "专注高分子功能材料与界面工程，具备从配方设计到中试放大的完整经验，关注专利技术的产业化可行性。",
                "主持省部级项目 4 项；授权发明专利 20 余项；多项成果完成企业许可。",
                1, "ACTIVE", "4.80", 42, "1000", 12, 90));
        out.add(expert("EXPERT", "陈立衡", "首席技术官", "芯衡半导体设备（示例）",
                "电子信息,智能制造", "半导体设备,光学检测,缺陷识别",
                "十五年半导体设备与光学检测研发经验，熟悉晶圆厂导入流程与技术验收标准。",
                "曾主导多台套检测设备量产导入；授权发明专利 15 项。",
                1, "ACTIVE", "4.85", 36, "1500", 8, 80));
        out.add(expert("BROKER", "王思远", "高级技术经纪人", "沪联技术转移中心（示例）",
                "智能制造,新材料", "需求诊断,技术尽调,交易结构",
                "累计服务 60 余家企业技术需求，擅长把“企业语言”翻译成“技术语言”，并组织高校团队完成概念验证。",
                "国家技术转移专业人员能力等级培训认证；促成技术交易 20 余项。",
                1, "ACTIVE", "4.90", 96, "800", 4, 95));
        out.add(expert("BROKER", "赵敏", "技术经纪人", "杭州科创服务联盟（示例）",
                "新能源,数字经济", "成果转化,开放许可,政策申报",
                "熟悉长三角技术转移与专利开放许可政策，擅长为中小企业设计“先使用后付费”的许可方案。",
                "参与多项地方专利转化项目；服务企业 40 余家。",
                1, "ACTIVE", "4.75", 58, "600", 6, 85));
        out.add(expert("BROKER", "孙博", "知识产权运营顾问", "知产运营工作室（示例）",
                "新材料,电子信息", "专利池,分级盘点,许可谈判",
                "专注专利资产分级、沉睡专利盘活与许可谈判，擅长构建专利组合与收益分配方案。",
                "服务高校院所 10 余家；参与专利池运营项目 5 项。",
                1, "ACTIVE", "4.82", 51, "900", 10, 75));
        return out;
    }

    private TechExpert expert(String type, String name, String title, String org, String industries,
                              String domains, String bio, String credentials, int verified, String invite,
                              String rating, int serviceCount, String fee, int responseHours, int sort) {
        TechExpert e = new TechExpert();
        e.setExpertType(type);
        e.setName(name);
        e.setTitle(title);
        e.setOrganization(org);
        e.setIndustries(industries);
        e.setDomains(domains);
        e.setBio(bio);
        e.setCredentials(credentials);
        e.setVerified(verified);
        e.setInviteStatus(invite);
        e.setRating(new BigDecimal(rating));
        e.setServiceCount(serviceCount);
        e.setConsultFee(new BigDecimal(fee));
        e.setResponseHours(responseHours);
        e.setSort(sort);
        e.setStatus(1);
        expertMapper.insert(e);
        return e;
    }

    private List<TechOrganization> seedOrganizations() {
        List<TechOrganization> out = new ArrayList<>();
        out.add(org("上海技术交易所（生态参考）", "EXCHANGE", "公开资料可查",
                "国家级技术交易场所（公开资料）；平台拟以会员/经纪机构方式对接，具体合作以书面协议为准。",
                "上技", "上海", "提供技术交易、成果转化与知识产权运营的公开规则参考。",
                "https://www.stte.com", 0, "POTENTIAL", 100));
        out.add(org("中科合创科技成果评价中心（示例）", "EVALUATION", "演示脱敏",
                "科技成果评价合作机构示例；平台仅提供材料辅导与流程跟踪，评价结论由机构出具。",
                "中科", "北京", "面向科技成果评价、项目验收与高企申报等场景提供第三方评价服务。",
                "", 0, "POTENTIAL", 90));
        out.add(org("杭州技术转移转化中心（生态参考）", "OTHER", "公开资料可查",
                "地方技术转移转化服务节点（公开资料）；合作以书面协议为准。",
                "杭转", "杭州", "围绕技术转移、概念验证与技术经理人队伍提供公共服务。",
                "https://kj.hangzhou.gov.cn", 0, "POTENTIAL", 80));
        out.add(org("沪知联知识产权服务（示例）", "AGENCY", "演示脱敏",
                "知识产权服务机构示例；平台展示其服务能力，具体代理业务由持证机构办理。",
                "沪知", "上海", "提供专利检索、申请、年费管理与维权支持。",
                "", 0, "POTENTIAL", 70));
        out.add(org("智专云专利监控（示例）", "SAAS", "演示脱敏",
                "SaaS 工具服务商示例；订阅开通与数据服务以正式协议为准。",
                "智专", "杭州", "提供专利状态监控、竞争对手动态与年费提醒。",
                "", 0, "POTENTIAL", 60));
        out.add(org("申城高校技术转移中心（示例）", "UNIVERSITY", "演示脱敏",
                "高校技术转移机构示例；职务成果处置按高校及主管部门规定执行。",
                "申城", "上海", "负责高校存量专利盘点、披露与转化对接。",
                "", 0, "POTENTIAL", 50));
        return out;
    }

    private TechOrganization org(String name, String type, String license, String qualifications,
                                 String logo, String region, String intro, String website,
                                 int verified, String partnership, int sort) {
        TechOrganization o = new TechOrganization();
        o.setName(name);
        o.setOrgType(type);
        o.setLicenseNo(license);
        o.setQualifications(qualifications);
        o.setLogoText(logo);
        o.setRegion(region);
        o.setIntro(intro);
        o.setWebsite(website);
        o.setVerified(verified);
        o.setPartnershipStatus(partnership);
        o.setSort(sort);
        o.setStatus(1);
        organizationMapper.insert(o);
        return o;
    }

    private void seedServices(List<TechExpert> experts, List<TechOrganization> orgs) {
        service("PLATFORM", null, "CONSULT", "专家一对一技术诊断",
                "把模糊需求拆成可执行的技术问题", "由平台匹配专家，完成需求澄清、技术可行性与初步路径判断。",
                "诊断纪要、技术问题清单、下一步建议", "FIXED", "1200", "0", 7,
                "技术诊断,专家咨询", "Stethoscope", "1 对 1", 100);
        service("PLATFORM", null, "CONSULT", "技术经纪人全程陪跑",
                "从需求受理到成果落地的项目化服务", "技术经纪人负责需求诊断、资源匹配、尽调组织、合同辅导与节点复盘。",
                "陪跑计划、对接纪要、里程碑报告", "COMMISSION", "0", "15", 90,
                "技术经纪,项目陪跑", "Route", "结果导向", 95);
        service("PLATFORM", null, "MATCHING", "供需精准匹配与对接",
                "AI 快筛 + 人工深谈", "基于行业、技术领域与成熟度进行匹配，技术经纪人组织双方深度对接。",
                "匹配清单、对接安排、会议纪要", "COMMISSION", "0", "15", 60,
                "供需对接,技术匹配", "Handshake", "AI + 人工", 90);
        service("ORGANIZATION", orgs.get(1).getOrgId(), "APPRAISAL", "科技成果评价材料辅导",
                "按国家标准准备材料，由合作机构出具结论", "平台协助梳理技术报告、查新与知识产权材料，评价由合作机构独立完成。",
                "材料清单、预审意见、机构评价报告（机构出具）", "QUOTE", "0", "0", 30,
                "成果评价,材料辅导", "FileCheck2", "机构出具", 85);
        service("PLATFORM", null, "APPRAISAL", "专利价值快筛与分级",
                "法律—技术—经济三维快筛", "依据公开标准的方法框架，对专利进行快速分级，辅助决定是否进入正式评估。",
                "快筛评分、分级建议、风险提示", "FIXED", "999", "0", 3,
                "专利评估,分级盘点", "Gauge", "3 个工作日", 80);
        service("PLATFORM", null, "PATENT_HOSTING", "沉睡专利盘活托管",
                "零门槛托管，成交后分成", "完成权属与年费核查、技术画像、许可/转让方案设计与买家匹配。",
                "托管协议、运营计划、月度进展报告", "COMMISSION", "0", "20", 180,
                "沉睡专利,专利托管", "ArchiveRestore", "不成交不收费", 100);
        service("ORGANIZATION", orgs.get(3).getOrgId(), "AGENCY_HOSTING", "知识产权服务机构托管",
                "严选机构 + 里程碑验收", "平台对服务机构进行资质与履约跟踪，企业按里程碑验收服务成果。",
                "服务清单、SLA、阶段验收单", "COMMISSION", "0", "15", 365,
                "机构托管,知识产权", "Building2", "服务可追踪", 75);
        service("ORGANIZATION", orgs.get(4).getOrgId(), "SAAS", "专利监控 SaaS 基础版",
                "年费、状态与竞品动态监控", "订阅式工具服务，提供专利状态提醒、年费预警与竞品动态。",
                "账号开通、监控报表、预警通知", "SUBSCRIPTION", "399", "0", 30,
                "SaaS,专利监控", "Radar", "按月订阅", 70);
        service("PLATFORM", null, "TRANSACTION", "技术收储-托管-交易全链路",
                "收储意向、尽调、交易与交割", "平台提供流程辅导与信息协作；交易鉴证、资金监管由合作机构办理。",
                "尽调清单、交易结构建议、交割材料清单", "COMMISSION", "0", "15", 120,
                "技术收储,交易闭环", "ArrowLeftRight", "全链路", 90);
        service("PLATFORM", null, "SAAS", "FTO 检索与预警",
                "技术实施自由实施（FTO）初筛", "面向拟上市或新产品导入企业，提供 FTO 检索范围梳理与预警建议。",
                "检索范围说明、风险清单（不构成法律意见）", "QUOTE", "0", "0", 20,
                "FTO,专利检索", "ScanSearch", "按需报价", 65);
    }

    private void service(String providerType, Long providerId, String category, String title, String subtitle,
                         String description, String deliverables, String priceMode, String price,
                         String commissionRate, int durationDays, String tags, String icon,
                         String highlight, int sort) {
        TechServiceProduct p = new TechServiceProduct();
        p.setProviderType(providerType);
        p.setProviderId(providerId);
        p.setCategory(category);
        p.setTitle(title);
        p.setSubtitle(subtitle);
        p.setDescription(description);
        p.setDeliverables(deliverables);
        p.setPriceMode(priceMode);
        p.setPrice(new BigDecimal(price));
        p.setCommissionRate(new BigDecimal(commissionRate));
        p.setDurationDays(durationDays);
        p.setTags(tags);
        p.setIcon(icon);
        p.setHighlight(highlight);
        p.setSort(sort);
        p.setStatus(1);
        serviceProductMapper.insert(p);
    }

    private void seedPolicies() {
        policy("专利转化运用专项行动方案（2023—2025年）", "国务院办公厅", "国办发〔2023〕37号",
                "全国", "专项行动", LocalDate.of(2023, 10, 17),
                "推动高校和科研机构存量专利盘活，促进专利产业化与中小企业成长。平台以信息协作与流程辅导方式承接转化落地。",
                "https://www.gov.cn/zhengce/content/202310/content_6910329.htm", "存量专利盘活", 100);
        policy("知识产权保护和运用“十五五”规划", "国务院", "", "全国", "规划",
                LocalDate.of(2026, 8, 3),
                "提出全面实施专利开放许可、完善申请前评估、推进专利动态盘点和分级管理。",
                "http://politics.people.com.cn/GB/n1/2026/0803/c461001-40772498.html", "开放许可", 98);
        policy("科技成果评估规范 GB/T 44731-2024", "国家标准化管理委员会", "GB/T 44731-2024",
                "全国", "国家标准", LocalDate.of(2024, 9, 29),
                "为科技成果评估提供方法框架，平台 AI 快筛仅作参考，正式评价由专业机构按标准执行。",
                "https://openstd.samr.gov.cn/", "评估标准", 95);
        policy("专利评估指引 GB/T 42748-2023", "国家标准化管理委员会", "GB/T 42748-2023",
                "全国", "国家标准", LocalDate.of(2023, 9, 7),
                "从法律、技术、经济维度提供专利评估指引，平台快筛参考其方法框架。",
                "https://openstd.samr.gov.cn/", "专利评估", 94);
        policy("上海市技术交易场所管理细则", "上海市科学技术委员会", "沪科规〔2025〕2号",
                "上海", "地方规则", LocalDate.of(2025, 4, 1),
                "明确技术交易场所的设立与管理要求；平台不自行运营交易场所，相关交易鉴证与结算由合作机构办理。",
                "https://stcsm.sh.gov.cn/", "交易场所边界", 92);
        policy("上海市科技研发与创新服务业高质量发展行动方案", "上海市科学技术委员会", "",
                "上海", "地方政策", LocalDate.of(2026, 8, 26),
                "提出引育技术转移示范机构、建设技术交易服务节点与“AI+技术经理人”能力。",
                "https://stcsm.sh.gov.cn/zwgk/ghjh/20260826/dee9bf8c2d1641f98d6f89592c8fbdb5.html",
                "政府流量 + 平台转化", 90);
        policy("杭州技术转移转化中心建设与专利许可政策", "杭州市科学技术局", "杭政函〔2026〕107号",
                "杭州", "地方政策", LocalDate.of(2026, 10, 1),
                "提出建强技术转移转化中心、壮大技术经理人队伍，并对概念验证中心给予支持。",
                "https://kj.hangzhou.gov.cn/", "概念验证", 88);
        policy("专利代理条例", "国务院", "国务院令第 706 号", "全国", "行政法规",
                LocalDate.of(2018, 11, 6),
                "专利代理业务须由依法设立的专利代理机构办理；平台不直接从事专利代理，相关业务转介持证机构。",
                "https://www.gov.cn/gongbao/content/2018/content_5343739.htm", "资质边界", 86);
        policy("技术合同认定登记管理办法", "科学技术部等", "国科发政字〔2000〕063号",
                "全国", "部门规章", LocalDate.of(2000, 2, 16),
                "技术合同认定登记由合同当事人向登记机构申请；平台提供材料辅导与流程提醒，不替代登记机构。",
                "https://www.most.gov.cn/", "技术合同登记", 84);
    }

    private void policy(String title, String issuer, String docNo, String region, String category,
                        LocalDate publishDate, String summary, String url, String highlight, int sort) {
        TechPolicy p = new TechPolicy();
        p.setTitle(title);
        p.setIssuer(issuer);
        p.setDocNo(docNo);
        p.setRegion(region);
        p.setCategory(category);
        p.setPublishDate(publishDate);
        p.setSummary(summary);
        p.setUrl(url);
        p.setHighlight(highlight);
        p.setSort(sort);
        p.setStatus(1);
        policyMapper.insert(p);
    }

    private void seedPosts(Long buyerId) {
        post(buyerId, "ARTICLE", "从实验室到产线：智能制造成果转化的三个关键翻译",
                "很多高校成果卡在“技术很好、企业看不懂”。第一步是把指标翻译成产线语言，第二步是把工艺窗口翻译成良率与节拍，第三步是把专利权利要求翻译成可验收的交付边界。技术经纪人的价值，就是完成这三次翻译。",
                "成果转化,智能制造,技术经纪", "智能制造", "周启明", "平台认证专家", 328, 42, 6);
        post(buyerId, "QUESTION", "高校专利许可，企业最关心哪些权属问题？",
                "近期在对接一项高校专利许可，想请教：职务发明认定、共有权人同意、已许可/质押状态、年费缴纳情况，哪些必须做前置核查？有没有标准的尽调清单？",
                "专利许可,权属核查,尽职调查", "新材料", "采购经理老陈", "平台认证用户", 215, 18, 9);
        post(buyerId, "DISCUSSION", "开放许可 vs 独占许可：中小企业怎么选？",
                "开放许可门槛低、谈判快，但可能多家企业同时使用；独占许可更利于建立壁垒，但费用高。建议先明确产品差异化程度与市场窗口，再决定许可结构。",
                "开放许可,许可谈判,中小企业", "新能源", "王思远", "平台认证技术经纪人", 186, 31, 7);
        post(buyerId, "ARTICLE", "专项行动收官之后：专利转化的下一步是什么",
                "从“盘点”走向“运营”，关键是把专利分级、技术画像、概念验证与买家匹配连成一条线。单做信息撮合很难产生真实成交，平台需要把服务做深。",
                "专利转化,政策,运营", "全国", "平台编辑部", "平台官方", 402, 55, 12);
        post(buyerId, "QUESTION", "沉睡专利盘活，第一步应该做年费核查还是价值评估？",
                "手里有一批十年以上的专利，部分可能已经失效。应该先做法律状态与年费核查，还是直接做价值评估？预算有限，想听听实操建议。",
                "沉睡专利,年费,价值评估", "新材料", "林嘉怡", "平台认证专家", 173, 27, 5);
    }

    private void post(Long userId, String type, String title, String content, String tags,
                      String industry, String author, String authorTitle,
                      int views, int likes, int comments) {
        TechPost p = new TechPost();
        p.setUserId(userId);
        p.setPostType(type);
        p.setTitle(title);
        p.setContent(content);
        p.setTags(tags);
        p.setIndustry(industry);
        p.setAuthorName(author);
        p.setAuthorTitle(authorTitle);
        p.setViewCount(views);
        p.setLikeCount(likes);
        p.setCommentCount(comments);
        p.setStatus(1);
        postMapper.insert(p);
    }

    private List<TechAchievement> seedAchievements(Long ownerId) {
        List<TechAchievement> out = new ArrayList<>();
        out.add(achievement(ownerId, "面向精密装配的视觉引导机器人标定方法", "PATENT", "CN2024******1",
                "INVENTION", "有效", "上海某高校（演示脱敏）", "智能制造", "机器视觉",
                7, "通过多相机协同与误差补偿，提高精密装配场景下的机器人标定精度与节拍稳定性。",
                "已在汽车零部件装配线完成验证；标定时间缩短 40%。", "3C 精密装配、汽车零部件、医疗器械装配",
                "LICENSE", "850000", 0, 1));
        out.add(achievement(ownerId, "一种高导热石墨烯复合散热膜及其制备方法", "PATENT", "CN2023******8",
                "INVENTION", "有效", "华东某高校（演示脱敏）", "新材料", "高分子复合材料",
                6, "面向高功率器件的石墨烯复合散热膜，兼顾导热与绝缘性能，具备中试放大基础。",
                "导热系数较传统材料提升 2.3 倍；已完成 100L 中试。", "功率器件、动力电池、LED 封装",
                "ASSIGN", "1200000", 1, 1));
        out.add(achievement(ownerId, "工业设备多源振动信号故障诊断系统", "TECHNOLOGY", "",
                null, "有效", "某工业软件团队（演示脱敏）", "智能制造", "工业软件",
                8, "融合振动、电流与温度信号，对关键设备进行故障预警与寿命评估。",
                "已在 3 家工厂部署；误报率低于 3%。", "旋转设备、压缩机、风机、泵组",
                "LICENSE", "600000", 0, 1));
        out.add(achievement(ownerId, "退役动力电池梯次利用快速分选方法", "PATENT", "CN2022******5",
                "INVENTION", "有效", "某新能源研究院（演示脱敏）", "新能源", "电池回收",
                5, "基于电化学阻抗与机器学习的分选方法，缩短退役电池检测时间。",
                "分选效率提升 2 倍；已建成小试线。", "储能、低速车、电池回收",
                "OPEN_LICENSE", "900000", 1, 1));
        out.add(achievement(ownerId, "面向柔性显示的低温银浆配方", "PATENT", "CN2025******2",
                "INVENTION", "审中", "某电子材料实验室（演示脱敏）", "新材料", "电子材料",
                4, "低温固化银浆配方，适用于柔性显示与可穿戴器件互连。",
                "固化温度降低至 120℃；导电率接近进口对标产品。", "柔性显示、可穿戴、电子标签",
                "ASSIGN", "1500000", 1, 0));
        out.add(achievement(ownerId, "半导体晶圆缺陷检测的光学系统", "PATENT", "CN2021******9",
                "INVENTION", "有效", "某半导体设备公司（演示脱敏）", "电子信息", "半导体设备",
                7, "多波段照明与自适应光学结构，提升晶圆表面微缺陷检出率。",
                "检出率提升 18%；已进入客户验证。", "晶圆制造、先进封装、功率器件",
                "LICENSE", "2000000", 0, 1));
        out.add(achievement(ownerId, "一种工业废水重金属吸附材料", "PATENT", "CN2020******3",
                "UTILITY", "有效", "某环保材料团队（演示脱敏）", "绿色低碳", "环保材料",
                6, "低成本吸附材料，对铅、镉等重金属具有选择性吸附能力。",
                "吸附容量较活性炭提升 3 倍；可再生使用 5 次。", "电镀废水、矿山废水、园区治理",
                "OPEN_LICENSE", "300000", 1, 1));
        out.add(achievement(ownerId, "基于数字孪生的产线能耗优化平台", "TECHNOLOGY", "",
                null, "有效", "某智能制造团队（演示脱敏）", "智能制造", "工业软件",
                8, "构建产线数字孪生模型，结合实时数据给出能耗优化策略。",
                "试点产线能耗降低 12%；支持多品牌设备接入。", "离散制造、流程工业、园区能源管理",
                "LICENSE", "750000", 0, 1));
        return out;
    }

    private TechAchievement achievement(Long ownerId, String title, String type, String patentNo,
                                        String patentType, String legalStatus, String owners,
                                        String industry, String field, int trl, String summary,
                                        String highlights, String scenarios, String licensing,
                                        String price, int dormant, int verified) {
        TechAchievement a = new TechAchievement();
        a.setOwnerUserId(ownerId);
        a.setTitle(title);
        a.setAchievementType(type);
        a.setPatentNo(patentNo);
        a.setPatentType(patentType);
        a.setLegalStatus(legalStatus);
        a.setOwners(owners);
        a.setIndustry(industry);
        a.setTechField(field);
        a.setMaturityLevel(trl);
        a.setSummary(summary);
        a.setHighlights(highlights);
        a.setApplicationScenarios(scenarios);
        a.setLicensingMode(licensing);
        a.setExpectedPrice(new BigDecimal(price));
        a.setPriceMode("QUOTE");
        a.setDormant(dormant);
        a.setVerified(verified);
        a.setStatus("LISTED");
        a.setViewCount(0);
        achievementMapper.insert(a);
        return a;
    }

    private void seedDemands(Long buyerId) {
        demand(buyerId, "寻找高精度机器人视觉标定技术", "智能制造", "机器视觉",
                "现有装配线换型后标定耗时较长，希望引入更高精度、更快节拍的视觉引导标定方案。",
                "换型标定平均 4 小时，影响小批量多品种生产。", "标定时间控制在 1 小时内，精度提升 30%。",
                "500000", "1000000", 0, "MATCHING");
        demand(buyerId, "需要耐高温 200℃ 的导热绝缘材料", "新材料", "高分子复合材料",
                "功率器件封装需要兼顾导热与绝缘，现有材料在高温老化后性能衰减明显。",
                "高温环境下导热性能衰减，导致器件寿命下降。", "200℃ 长期老化后导热系数保持率 ≥ 85%。",
                "300000", "800000", 0, "MATCHING");
        demand(buyerId, "动力电池回收分选产线自动化改造", "新能源", "电池回收",
                "退役电池来料一致性差，人工检测效率低，希望引入快速分选与自动化上下料方案。",
                "人工检测节拍慢，分选一致性不足。", "分选节拍提升 2 倍，误判率低于 3%。",
                "1000000", "3000000", 1, "IN_PROGRESS");
        demand(buyerId, "晶圆表面缺陷 AI 检测方案", "电子信息", "半导体",
                "希望在不改造现有产线的前提下，增加晶圆表面微缺陷的在线检测能力。",
                "现有光学检测对小尺寸缺陷检出率不足。", "检出率提升 15% 以上，误报率可控。",
                "800000", "2000000", 0, "MATCHING");
        demand(buyerId, "工业废水重金属达标处理技术", "绿色低碳", "环保材料",
                "园区电镀废水重金属波动大，现有工艺药剂消耗高，希望寻找低成本吸附或膜处理方案。",
                "药剂成本高，污泥量大。", "出水稳定达标，综合成本下降 20%。",
                "200000", "600000", 0, "MATCHING");
        demand(buyerId, "产线数字孪生能耗优化", "智能制造", "工业软件",
                "希望把现有产线数据接入数字孪生平台，识别能耗瓶颈并给出优化策略。",
                "能耗数据分散，缺少统一的优化工具。", "试点产线能耗降低 10% 以上。",
                "400000", "1200000", 0, "MATCHING");
    }

    private void demand(Long userId, String title, String industry, String field, String description,
                        String pain, String expected, String min, String max, int anonymous, String status) {
        TechDemand d = new TechDemand();
        d.setDemandNo("TD-DEMO-" + System.nanoTime());
        d.setUserId(userId);
        d.setTitle(title);
        d.setIndustry(industry);
        d.setTechField(field);
        d.setDescription(description);
        d.setPainPoints(pain);
        d.setExpectedOutcome(expected);
        d.setBudgetMin(new BigDecimal(min));
        d.setBudgetMax(new BigDecimal(max));
        d.setDeadline(LocalDate.now().plusMonths(3));
        d.setAnonymous(anonymous);
        d.setStatus(status);
        d.setViewCount(0);
        d.setMatchCount(0);
        demandMapper.insert(d);
    }

    private TechProject seedProject(Long userId, String type, String title, String stage, String status,
                                    int progress, Long expertId, Long brokerId, BigDecimal amount,
                                    BigDecimal rate, String nextAction,
                                    String[] names, String[] statuses) {
        TechProject p = new TechProject();
        p.setProjectNo("TP-DEMO-" + System.nanoTime());
        p.setUserId(userId);
        p.setProjectType(type);
        p.setTitle(title);
        p.setStage(stage);
        p.setStatus(status);
        p.setProgress(progress);
        p.setOwnerExpertId(expertId);
        p.setBrokerId(brokerId);
        p.setAmount(amount);
        p.setCommissionRate(rate);
        p.setSourceType(type);
        p.setNextAction(nextAction);
        projectMapper.insert(p);
        for (int i = 0; i < names.length; i++) {
            TechMilestone m = new TechMilestone();
            m.setProjectId(p.getProjectId());
            m.setSeq(i + 1);
            m.setName(names[i]);
            m.setStatus(statuses[i]);
            m.setDueDate(LocalDate.now().plusDays(7L * (i + 1)));
            if ("DONE".equals(statuses[i])) {
                m.setCompletedAt(LocalDateTime.now().minusDays(names.length - i));
                m.setDeliverable(names[i] + "交付物（演示）");
            }
            milestoneMapper.insert(m);
        }
        return p;
    }

    private void seedAppraisals(Long buyerId, List<TechAchievement> achievements,
                                List<TechOrganization> orgs, TechProject project) {
        TechAppraisal a1 = new TechAppraisal();
        a1.setAppraisalNo("TA-DEMO-001");
        a1.setUserId(buyerId);
        a1.setAchievementId(achievements.get(1).getAchievementId());
        a1.setProjectId(project.getProjectId());
        a1.setInstitutionId(orgs.get(1).getOrgId());
        a1.setStandardCode("GB/T 44731-2024");
        a1.setTechScore(82);
        a1.setEconomicScore(75);
        a1.setLegalScore(90);
        a1.setMaturityScore(70);
        a1.setStatus("EXPERT_REVIEW");
        a1.setDisclaimer("平台不直接出具评价结论；评价报告由合作机构按约定标准与程序出具。");
        appraisalMapper.insert(a1);

        TechAppraisal a2 = new TechAppraisal();
        a2.setAppraisalNo("TA-DEMO-002");
        a2.setUserId(buyerId);
        a2.setAchievementId(achievements.get(2).getAchievementId());
        a2.setInstitutionId(orgs.get(1).getOrgId());
        a2.setStandardCode("GB/T 44731-2024");
        a2.setTechScore(86);
        a2.setEconomicScore(80);
        a2.setLegalScore(88);
        a2.setMaturityScore(85);
        a2.setConclusionLevel("演示结论：国内先进（非真实报告）");
        a2.setReportNo("DEMO-RPT-2026-001");
        a2.setStatus("REPORT_ISSUED");
        a2.setDisclaimer("报告编号与结论均为演示数据，不构成真实评价结论。");
        appraisalMapper.insert(a2);
    }

    private void seedHostings(Long buyerId, List<TechAchievement> achievements,
                              List<TechOrganization> orgs, TechProject project) {
        TechHosting h1 = new TechHosting();
        h1.setHostingNo("TH-DEMO-001");
        h1.setUserId(buyerId);
        h1.setProjectId(project.getProjectId());
        h1.setHostingType("PATENT");
        h1.setTargetId(achievements.get(1).getAchievementId());
        h1.setTargetName(achievements.get(1).getTitle());
        h1.setProviderOrgId(orgs.get(3).getOrgId());
        h1.setAgreementNo("DEMO-HOST-2026-001");
        h1.setScope("权属核查、年费管理、技术画像、许可/转让方案设计与买家匹配");
        h1.setStartDate(LocalDate.now().minusMonths(2));
        h1.setEndDate(LocalDate.now().plusMonths(10));
        h1.setFeeMode("COMMISSION");
        h1.setFeeAmount(new BigDecimal("2999"));
        h1.setCommissionRate(new BigDecimal("20.00"));
        h1.setStatus("ACTIVE");
        h1.setActivationPlan("先完成法律状态与年费核查，再围绕功率器件场景制作技术画像，优先对接 3 家潜在买家。");
        h1.setLastServiceAt(LocalDateTime.now().minusDays(6));
        hostingMapper.insert(h1);

        TechHosting h2 = new TechHosting();
        h2.setHostingNo("TH-DEMO-002");
        h2.setUserId(buyerId);
        h2.setHostingType("AGENCY");
        h2.setTargetName("企业知识产权年度托管");
        h2.setProviderOrgId(orgs.get(3).getOrgId());
        h2.setScope("专利年费监控、商标续展提醒、日常咨询");
        h2.setStartDate(LocalDate.now());
        h2.setEndDate(LocalDate.now().plusYears(1));
        h2.setFeeMode("ANNUAL");
        h2.setFeeAmount(new BigDecimal("12000"));
        h2.setCommissionRate(BigDecimal.ZERO);
        h2.setStatus("APPLIED");
        hostingMapper.insert(h2);

        TechHosting h3 = new TechHosting();
        h3.setHostingNo("TH-DEMO-003");
        h3.setUserId(buyerId);
        h3.setHostingType("SAAS");
        h3.setTargetId(achievements.get(5).getAchievementId());
        h3.setTargetName("专利监控 SaaS 基础版");
        h3.setProviderOrgId(orgs.get(4).getOrgId());
        h3.setScope("专利状态监控、年费预警、竞品动态");
        h3.setStartDate(LocalDate.now().minusMonths(1));
        h3.setEndDate(LocalDate.now().plusMonths(11));
        h3.setFeeMode("SUBSCRIPTION");
        h3.setFeeAmount(new BigDecimal("399"));
        h3.setCommissionRate(BigDecimal.ZERO);
        h3.setStatus("ACTIVE");
        h3.setLastServiceAt(LocalDateTime.now().minusDays(1));
        hostingMapper.insert(h3);
    }

    private void seedDeals(Long buyerId, Long sellerId, List<TechAchievement> achievements, TechProject project) {
        TechDeal d1 = new TechDeal();
        d1.setDealNo("TDL-DEMO-001");
        d1.setProjectId(project.getProjectId());
        d1.setAchievementId(achievements.get(2).getAchievementId());
        d1.setBuyerUserId(buyerId);
        d1.setSellerUserId(sellerId);
        d1.setDealType("LICENSE");
        d1.setAmount(new BigDecimal("600000"));
        d1.setCommissionRate(new BigDecimal("15.00"));
        d1.setCommissionAmount(new BigDecimal("90000.00"));
        d1.setPaymentMode("ESCROW");
        d1.setStatus("ESCROWED");
        d1.setContractNo("DEMO-CONTRACT-2026-001");
        d1.setEvidenceHash("demo-evidence-hash-001");
        d1.setEscrowRef("DEMO-ESCROW-2026-001");
        dealMapper.insert(d1);

        TechDeal d2 = new TechDeal();
        d2.setDealNo("TDL-DEMO-002");
        d2.setAchievementId(achievements.get(0).getAchievementId());
        d2.setBuyerUserId(buyerId);
        d2.setSellerUserId(sellerId);
        d2.setDealType("ASSIGN");
        d2.setAmount(new BigDecimal("850000"));
        d2.setCommissionRate(new BigDecimal("15.00"));
        d2.setCommissionAmount(new BigDecimal("127500.00"));
        d2.setPaymentMode("INSTALLMENT");
        d2.setStatus("COMPLETED");
        d2.setContractNo("DEMO-CONTRACT-2026-002");
        d2.setEvidenceHash("demo-evidence-hash-002");
        d2.setEscrowRef("DEMO-ESCROW-2026-002");
        dealMapper.insert(d2);
    }

    private void seedConsultations(Long buyerId, List<TechExpert> experts) {
        TechConsultation c1 = new TechConsultation();
        c1.setExpertId(experts.get(0).getExpertId());
        c1.setUserId(buyerId);
        c1.setTopic("机器人视觉标定方案的可行性判断");
        c1.setDescription("希望在正式立项前，请专家判断现有产线条件下是否适合引入视觉引导标定。");
        c1.setMode("VIDEO");
        c1.setScheduledAt(LocalDateTime.now().plusDays(2).withHour(14).withMinute(0));
        c1.setDurationMin(60);
        c1.setAmount(new BigDecimal("1200"));
        c1.setStatus("CONFIRMED");
        consultationMapper.insert(c1);

        TechConsultation c2 = new TechConsultation();
        c2.setExpertId(experts.get(3).getExpertId());
        c2.setUserId(buyerId);
        c2.setTopic("沉睡专利盘活的路径选择");
        c2.setDescription("希望了解开放许可、转让与作价入股三种路径的适用条件。");
        c2.setMode("PHONE");
        c2.setScheduledAt(LocalDateTime.now().minusDays(3).withHour(10).withMinute(30));
        c2.setDurationMin(45);
        c2.setAmount(new BigDecimal("800"));
        c2.setStatus("COMPLETED");
        consultationMapper.insert(c2);
    }
}

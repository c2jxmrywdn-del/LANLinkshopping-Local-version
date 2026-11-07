package com.lanlink.shopping.module.techservice.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * P0 专家池冷启动：在演示数据基础上补齐至 20 位专家 + 5 位技术经纪人。
 *
 * <p>按姓名幂等补齐，不覆盖已有记录；仅演示/验收环境启用（复用 demo-data 开关）。
 * 真实环境应通过后台定向邀请录入，不得自动灌入虚构专家。
 */
@Component
@Order(20)
public class TechP0SeedInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TechP0SeedInitializer.class);

    @Value("${lanlink.demo-data.enabled:false}")
    private boolean enabled;

    private final TechExpertMapper expertMapper;

    public TechP0SeedInitializer(TechExpertMapper expertMapper) {
        this.expertMapper = expertMapper;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        int addedExperts = ensure("EXPERT", 20, EXPERT_SEEDS);
        int addedBrokers = ensure("BROKER", 5, BROKER_SEEDS);
        if (addedExperts + addedBrokers > 0) {
            log.info("[tech-p0-seed] 专家池补齐完成：新增专家 {}、经纪人 {}", addedExperts, addedBrokers);
        }
    }

    private int ensure(String type, int target, List<Seed> seeds) {
        long current = count(type);
        if (current >= target) return 0;
        int added = 0;
        for (Seed seed : seeds) {
            if (count(type) >= target) break;
            if (exists(seed.name())) continue;
            TechExpert expert = new TechExpert();
            expert.setExpertType(type);
            expert.setName(seed.name());
            expert.setTitle(seed.title());
            expert.setOrganization(seed.org());
            expert.setIndustries(seed.industries());
            expert.setDomains(seed.domains());
            expert.setBio(seed.bio());
            expert.setCredentials(seed.credentials());
            expert.setVerified(1);
            expert.setInviteStatus("INVITED");
            expert.setRating(new BigDecimal("4.75"));
            expert.setServiceCount(0);
            expert.setConsultFee(new BigDecimal(seed.fee()));
            expert.setResponseHours(12);
            expert.setSort(40);
            expert.setStatus(1);
            expertMapper.insert(expert);
            added++;
        }
        return added;
    }

    private long count(String type) {
        Long value = expertMapper.selectCount(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getExpertType, type).eq(TechExpert::getStatus, 1));
        return value == null ? 0 : value;
    }

    private boolean exists(String name) {
        Long value = expertMapper.selectCount(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getName, name));
        return value != null && value > 0;
    }

    private record Seed(String name, String title, String org, String industries, String domains,
                        String bio, String credentials, String fee) {
    }

    private static final List<Seed> EXPERT_SEEDS = List.of(
            new Seed("吴靖", "研究员", "中国科学院微电子研究所（示例）", "电子信息,智能制造",
                    "半导体工艺,先进封装", "专注晶圆级封装与可靠性研究，具备产线导入经验。", "授权发明专利 25 项", "1500"),
            new Seed("何雨桐", "副教授", "同济大学 材料科学与工程学院", "新能源,新材料",
                    "固态电池,电解液", "长期从事固态电池界面材料研究，完成中试放大。", "主持国家级课题 2 项", "1200"),
            new Seed("沈铭", "首席架构师", "某工业软件企业（示例）", "智能制造,数字经济",
                    "工业软件,数字孪生", "主导数字孪生平台研发与工厂落地。", "授权发明专利 8 项", "1600"),
            new Seed("顾清", "教授", "复旦大学 生物医学工程", "生物医药,新材料",
                    "生物材料,医疗器械", "生物医用材料与器械注册经验丰富。", "完成 3 项三类器械注册", "1800"),
            new Seed("许志远", "总工程师", "某环保集团（示例）", "绿色低碳,新材料",
                    "废水处理,吸附材料", "工业废水治理工程与材料产业化经验。", "省部级科技奖 2 项", "1300"),
            new Seed("蒋文博", "教授级高工", "上海电气中央研究院（示例）", "智能制造,新能源",
                    "装备可靠性,智能运维", "大型装备可靠性设计与运维体系建设。", "授权发明专利 18 项", "1500"),
            new Seed("韩雪", "算法专家", "某人工智能企业（示例）", "电子信息,数字经济",
                    "机器视觉,缺陷检测", "工业视觉缺陷检测算法与边缘部署。", "落地 10+ 产线项目", "1400"),
            new Seed("邓浩", "研究员", "东华大学 材料学院", "新材料,绿色低碳",
                    "纤维材料,复合材料", "高性能纤维与复合材料成型工艺。", "授权发明专利 22 项", "1100"),
            new Seed("冯佳", "高级工程师", "某电子材料实验室（示例）", "新材料,电子信息",
                    "电子浆料,封装材料", "低温浆料与封装材料产业化。", "参与国家标准 1 项", "1000"),
            new Seed("曹宇", "副教授", "上海交通大学 机器人研究所", "智能制造,电子信息",
                    "协作机器人,力控", "协作机器人与力控装配技术。", "授权发明专利 15 项", "1400"),
            new Seed("彭蕾", "政策研究员", "某科技智库（示例）", "数字经济,绿色低碳",
                    "技术转移政策,科技计划", "科技政策与技术转移体系研究。", "参与省级规划 3 项", "800"),
            new Seed("袁野", "知识产权律师", "某律师事务所（示例）", "电子信息,新材料",
                    "专利诉讼,权属尽调", "专利无效与侵权诉讼代理经验。", "代理案件 60+ 件", "1600"),
            new Seed("唐宁", "标准化专家", "某标准化研究院（示例）", "智能制造,新材料",
                    "标准制定,检测认证", "科技成果与产品标准制定。", "牵头标准 5 项", "900"),
            new Seed("罗嘉", "中试平台负责人", "某概念验证中心（示例）", "新材料,新能源",
                    "中试放大,工艺验证", "概念验证与中试平台运营。", "服务项目 30+ 个", "1000"),
            new Seed("郑楠", "教授", "浙江大学 能源工程学院", "新能源,绿色低碳",
                    "储能系统,能量管理", "储能系统集成与能量管理算法。", "授权发明专利 12 项", "1300"),
            new Seed("秦朗", "高级工程师", "某检测认证机构（示例）", "智能制造,电子信息",
                    "可靠性测试,检测认证", "产品可靠性测试与认证服务。", "服务企业 80+ 家", "900"),
            new Seed("施文", "技术转移专家", "某高校技术转移中心（示例）", "新材料,生物医药",
                    "成果披露,作价入股", "高校职务成果披露与作价入股流程。", "完成转化项目 20+ 项", "1100")
    );

    private static final List<Seed> BROKER_SEEDS = List.of(
            new Seed("魏然", "高级技术经纪人", "长三角技术转移联盟（示例）", "智能制造,电子信息",
                    "需求诊断,交易结构", "服务企业 50 余家，擅长技术尽调与交易结构设计。", "促成交易 15 项", "1000"),
            new Seed("卢佳", "技术经纪人", "杭州科创服务联盟（示例）", "新材料,绿色低碳",
                    "成果转化,政策申报", "熟悉开放许可与先使用后付费方案设计。", "服务企业 30 余家", "800")
    );
}

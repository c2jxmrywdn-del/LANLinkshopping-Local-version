package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;

import java.math.BigDecimal;

/** 技术服务组合器测试用的最小真实形状样本（仅测试侧使用，不进生产代码）。 */
final class TechKnowledgeFixture {

    private TechKnowledgeFixture() {
    }

    static TechAchievement achievement() {
        TechAchievement a = new TechAchievement();
        a.setAchievementId(1042L);
        a.setTitle("高效液相色谱废溶剂回收装置");
        a.setAchievementType("PATENT");
        a.setPatentNo("ZL202320456789.1");
        a.setPatentType("实用新型");
        a.setLegalStatus("授权");
        a.setOwners("某高校材料学院");
        a.setIndustry("精细化工");
        a.setTechField("化工分离");
        a.setMaturityLevel(6);
        a.setLicensingMode("许可");
        a.setApplicationScenarios("制药与农化企业溶剂回收");
        a.setHighlights("回收率≥95%");
        a.setSummary("针对实验室与中试线废溶剂的处理装置");
        a.setDormant(1);
        a.setVerified(1);
        a.setStatus("LISTED");
        a.setExpectedPrice(new BigDecimal("850000"));
        return a;
    }

    static TechExpert expert() {
        TechExpert e = new TechExpert();
        e.setExpertId(208L);
        e.setExpertType("BROKER");
        e.setName("张伟");
        e.setTitle("高级工程师");
        e.setOrganization("省产业技术研究院");
        e.setIndustries("汽车装备,3C电子");
        e.setDomains("智能制造,工业机器人");
        e.setBio("从事产线自动化改造撮合十余年");
        e.setCredentials("注册技术经纪人");
        e.setVerified(1);
        e.setStatus(1);
        return e;
    }

    static TechOrganization organization() {
        TechOrganization o = new TechOrganization();
        o.setOrgId(11L);
        o.setName("某专利代理事务所");
        o.setOrgType("AGENCY");
        o.setQualifications("专利代理执业许可");
        o.setRegion("上海");
        o.setIntro("提供专利申请与年费管理服务");
        o.setVerified(0);
        o.setPartnershipStatus("POTENTIAL");
        o.setStatus(1);
        return o;
    }
}

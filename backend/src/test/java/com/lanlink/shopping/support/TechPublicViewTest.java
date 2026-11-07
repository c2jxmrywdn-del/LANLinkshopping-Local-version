package com.lanlink.shopping.support;

import com.lanlink.shopping.module.techservice.api.TechViews;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开响应的数据最小化：匿名可撤回、核验材料不外泄、内部字段不进公开契约。
 */
class TechPublicViewTest {

    private static TechDemand demand(Integer anonymous) {
        TechDemand d = new TechDemand();
        d.setDemandId(7L);
        d.setDemandNo("TD202611030007");
        d.setUserId(42L);
        d.setTitle("视觉分选产线改造");
        d.setAnonymous(anonymous);
        d.setDeleted(0);
        return d;
    }

    private static TechOrganization organization() {
        TechOrganization o = new TechOrganization();
        o.setOrgId(3L);
        o.setName("上海示例专利代理事务所");
        o.setOrgType("AGENCY");
        o.setLicenseNo("31200000456");
        o.setSort(10);
        o.setStatus(1);
        return o;
    }

    @Test
    void anonymousDemandHidesPublisherAccount() {
        TechViews.Demand view = TechViews.ofDemand(demand(1));
        assertNull(view.userId(), "匿名需求若仍回传 userId，匿名只是前端文案");
        assertEquals(7L, view.demandId(), "业务主键仍需保留，否则无法查看详情");
        assertEquals(1, view.anonymous());
    }

    @Test
    void namedDemandKeepsPublisherAccount() {
        assertEquals(42L, TechViews.ofDemand(demand(0)).userId());
    }

    @Test
    void publicViewNeverLeaksLogicDeleteFlag() {
        assertNull(TechViews.ofDemand(demand(0)).deleted());
        assertEquals(0, TechViews.ofDemandFull(demand(0)).deleted(), "后台仍保留完整字段用于排障");
    }

    @Test
    void publicOrganizationMasksLicenseAndDropsOpsFields() {
        TechViews.Organization view = TechViews.ofOrganization(organization());
        assertEquals("3120****56", view.licenseNo(), "执业许可证号是平台核验材料，不对匿名访问者全文出示");
        assertNull(view.sort());
        assertNull(view.status());
        assertEquals("上海示例专利代理事务所", view.name(), "机构名称等公开画像信息不受影响");
    }

    @Test
    void shortLicenseIsNotRenderedAsIfItWereVerified() {
        TechOrganization o = organization();
        o.setLicenseNo("312045");
        assertEquals("****", TechViews.ofOrganization(o).licenseNo());

        o.setLicenseNo(null);
        assertNull(TechViews.ofOrganization(o).licenseNo(), "无证与脱敏不能混为一谈");
    }

    @Test
    void adminOrganizationViewKeepsFullFields() {
        TechViews.Organization full = TechViews.ofOrganizationFull(organization());
        assertEquals("31200000456", full.licenseNo());
        assertEquals(10, full.sort());
        assertEquals(1, full.status());
    }

    /** 本批新收紧：专家与成果的账号标识此前对游客可见，可被用来枚举平台用户 */
    @Test
    void expertAndAchievementViewsDropInternalAccountIds() {
        TechExpert expert = new TechExpert();
        expert.setExpertId(7L);
        expert.setUserId(99L);
        expert.setName("周启明");
        assertNull(TechViews.ofExpert(expert).userId(), "公开专家卡片不需要知道它绑了哪个账号");
        assertEquals("周启明", TechViews.ofExpert(expert).name(), "姓名等公开画像不受影响");
        assertEquals(99L, TechViews.ofExpertFull(expert).userId(), "后台与本人仍需完整字段");

        TechAchievement achievement = new TechAchievement();
        achievement.setAchievementId(1L);
        achievement.setOwnerUserId(5L);
        achievement.setDeleted(0);
        achievement.setTitle("视觉引导机器人标定方法");
        assertNull(TechViews.ofAchievement(achievement).ownerUserId());
        assertNull(TechViews.ofAchievement(achievement).deleted());
        assertEquals(5L, TechViews.ofAchievementFull(achievement).ownerUserId());
        assertEquals(0, TechViews.ofAchievementFull(achievement).deleted());
    }
}

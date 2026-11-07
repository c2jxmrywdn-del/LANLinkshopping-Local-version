package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.dto.TechAppraisalCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechDemandCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechHostingCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import com.lanlink.shopping.module.techservice.mapper.TechHostingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.service.TechAppraisalService;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechDemandService;
import com.lanlink.shopping.module.techservice.service.TechHostingService;
import com.lanlink.shopping.module.techservice.service.TechHostingTargetResolver;
import com.lanlink.shopping.module.techservice.service.TechProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 聚合命令服务：合规校验、状态机合法性、事务后事件发布（消息不再在事务内直写）。
 */
class TechAggregateCommandServiceTest {

    private TechDemandMapper demandMapper;
    private TechAppraisalMapper appraisalMapper;
    private TechAchievementMapper achievementMapper;
    private TechHostingMapper hostingMapper;
    private TechOrganizationMapper organizationMapper;
    private TechProjectService projectService;
    private ApplicationEventPublisher publisher;
    private TechComplianceGuard guard;

    @BeforeEach
    void setUp() {
        demandMapper = mock(TechDemandMapper.class);
        appraisalMapper = mock(TechAppraisalMapper.class);
        achievementMapper = mock(TechAchievementMapper.class);
        hostingMapper = mock(TechHostingMapper.class);
        organizationMapper = mock(TechOrganizationMapper.class);
        projectService = mock(TechProjectService.class);
        publisher = mock(ApplicationEventPublisher.class);
        guard = new TechComplianceGuard();

        TechProject project = new TechProject();
        project.setProjectId(9L);
        project.setProjectNo("TP-009");
        when(projectService.createProject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(project);
    }

    private TechDemandCreateRequest demandRequest(String description) {
        TechDemandCreateRequest req = new TechDemandCreateRequest();
        req.setTitle("耐高温导热材料需求");
        req.setIndustry("新材料");
        req.setTechField("高分子复合材料");
        req.setDescription(description);
        return req;
    }

    @Test
    void createDemandRejectsForbiddenClaimBeforePersisting() {
        TechDemandService service = new TechDemandService(demandMapper, projectService, guard, publisher);
        assertThrows(BusinessException.class,
                () -> service.createDemand(1L, demandRequest("我们是政府指定平台，保证成交。")));
        verify(demandMapper, never()).insert(any(TechDemand.class));
        verify(projectService, never()).createProject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDemandPersistsProjectAndPublishesAfterCommitEvent() {
        TechDemandService service = new TechDemandService(demandMapper, projectService, guard, publisher);
        assertDoesNotThrow(() -> service.createDemand(1L, demandRequest("寻找耐高温导热材料，预算 30-80 万。")));
        verify(demandMapper).insert(any(TechDemand.class));
        verify(projectService).createProject(any(), eq("MATCH"), any(), eq("INTAKE"), any(), any(), any(),
                eq("DEMAND"), any(), any(), any(), any());
        verify(publisher).publishEvent(any(TechSideEffectEvent.class));
    }

    @Test
    void demandStatusRejectsUnsupportedValue() {
        TechDemandService service = new TechDemandService(demandMapper, projectService, guard, publisher);
        assertThrows(BusinessException.class, () -> service.updateStatus(1L, "DONE"));
    }

    @Test
    void appraisalStatusRejectsUnknownValueAndAllowsIssuedReport() {
        TechAppraisalService service = new TechAppraisalService(
                appraisalMapper, achievementMapper, organizationMapper, projectService, guard, publisher);
        assertThrows(BusinessException.class, () -> service.updateStatus(1L, "FINISHED", null, null));

        TechAppraisal appraisal = new TechAppraisal();
        appraisal.setAppraisalId(1L);
        appraisal.setStatus("SUBMITTED");
        when(appraisalMapper.selectById(1L)).thenReturn(appraisal);

        TechAppraisal updated = service.updateStatus(1L, "REPORT_ISSUED", "国内先进", "RPT-001");
        assertEquals("REPORT_ISSUED", updated.getStatus());
        assertEquals("国内先进", updated.getConclusionLevel());
        assertEquals("RPT-001", updated.getReportNo());
    }

    @Test
    void appraisalRejectsMissingInstitution() {
        TechAppraisalCreateRequest req = new TechAppraisalCreateRequest();
        req.setTitle("高导热石墨烯复合散热膜");
        req.setInstitutionId(5L);
        when(organizationMapper.selectById(5L)).thenReturn(null);

        TechAppraisalService service = new TechAppraisalService(
                appraisalMapper, achievementMapper, organizationMapper, projectService, guard, publisher);
        assertThrows(BusinessException.class, () -> service.createAppraisal(1L, req));
    }

    @Test
    void appraisalRequiresAnOwnedAchievement() {
        // 以前传任意 achievementId 也能建单：评价单、陪跑项目、对账全挂在空气上
        TechAppraisalCreateRequest req = new TechAppraisalCreateRequest();
        req.setTitle("高导热石墨烯复合散热膜");
        req.setInstitutionId(5L);
        req.setAchievementId(77L);
        TechOrganization institution = new TechOrganization();
        institution.setOrgId(5L);
        institution.setStatus(1);
        when(organizationMapper.selectById(5L)).thenReturn(institution);

        TechAppraisalService service = new TechAppraisalService(
                appraisalMapper, achievementMapper, organizationMapper, projectService, guard, publisher);

        when(achievementMapper.selectById(77L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.createAppraisal(1L, req));

        TechAchievement someoneElse = new TechAchievement();
        someoneElse.setAchievementId(77L);
        someoneElse.setOwnerUserId(999L);
        when(achievementMapper.selectById(77L)).thenReturn(someoneElse);
        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> service.createAppraisal(1L, req));
        assertEquals(404, forbidden.getCode().intValue(),
                "不属于本人也要回 404，用 403 会让人枚举谁的成果存在");

        TechAchievement owned = new TechAchievement();
        owned.setAchievementId(77L);
        owned.setOwnerUserId(1L);
        when(achievementMapper.selectById(77L)).thenReturn(owned);
        assertNotNull(service.createAppraisal(1L, req));
    }

    @Test
    void hostingResolvesTargetAndRejectsInvalidType() {
        TechHostingMapper hostingMapper = mock(TechHostingMapper.class);
        TechHostingTargetResolver resolver = new TechHostingTargetResolver(mock(com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper.class),
                mock(com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper.class));
        TechHostingService service = new TechHostingService(hostingMapper, resolver, organizationMapper,
                projectService, guard, publisher);

        TechHostingCreateRequest bad = new TechHostingCreateRequest();
        bad.setHostingType("UNKNOWN");
        bad.setScope("年度监控");
        assertThrows(BusinessException.class, () -> service.createHosting(1L, bad));

        TechHostingCreateRequest req = new TechHostingCreateRequest();
        req.setHostingType("AGENCY");
        req.setTargetName("企业知识产权年度托管");
        req.setScope("专利年费监控、商标续展提醒");
        req.setFeeMode("ANNUAL");
        req.setFeeAmount(new BigDecimal("12000"));
        TechHosting created = assertDoesNotThrow(() -> service.createHosting(1L, req));
        assertEquals("APPLIED", created.getStatus());
        assertNotNull(created.getHostingNo());
        verify(publisher).publishEvent(any(TechSideEffectEvent.class));
    }

    @Test
    void hostingRejectsForbiddenScopeClaim() {
        TechHostingService service = new TechHostingService(hostingMapper,
                new TechHostingTargetResolver(mock(com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper.class),
                        mock(com.lanlink.shopping.module.techservice.mapper.TechServiceProductMapper.class)),
                organizationMapper, projectService, guard, publisher);
        TechHostingCreateRequest req = new TechHostingCreateRequest();
        req.setHostingType("PATENT");
        req.setTargetName("某专利");
        req.setScope("官方背书，保证成交");
        assertThrows(BusinessException.class, () -> service.createHosting(1L, req));
        verify(hostingMapper, never()).insert(any(TechHosting.class));
    }
}

package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechMilestoneMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import com.lanlink.shopping.module.techservice.service.TechProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 陪跑项目聚合：归属校验显式化（用户 / 运营）、里程碑推进、团队指派合法性。
 */
class TechProjectServiceTest {

    private TechProjectMapper projectMapper;
    private TechMilestoneMapper milestoneMapper;
    private TechExpertMapper expertMapper;
    private TechProjectService service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(TechProjectMapper.class);
        milestoneMapper = mock(TechMilestoneMapper.class);
        expertMapper = mock(TechExpertMapper.class);
        service = new TechProjectService(projectMapper, milestoneMapper, expertMapper);
    }

    private TechProject project(Long ownerId) {
        TechProject p = new TechProject();
        p.setProjectId(1L);
        p.setProjectNo("TP-001");
        p.setUserId(ownerId);
        p.setProjectType("MATCH");
        p.setStatus("ACTIVE");
        p.setProgress(0);
        when(projectMapper.selectById(1L)).thenReturn(p);
        return p;
    }

    @Test
    void advanceRejectsNonOwnerWhenNotAdmin() {
        project(1L);
        assertThrows(BusinessException.class, () -> service.advanceProject(1L, 2L, false));
    }

    @Test
    void adminCanAdvanceWithoutOwnership() {
        TechProject p = project(1L);
        TechMilestone m1 = new TechMilestone();
        m1.setMilestoneId(11L);
        m1.setProjectId(1L);
        m1.setSeq(1);
        m1.setName("需求诊断");
        m1.setStatus("IN_PROGRESS");
        TechMilestone m2 = new TechMilestone();
        m2.setMilestoneId(12L);
        m2.setProjectId(1L);
        m2.setSeq(2);
        m2.setName("资源匹配");
        m2.setStatus("PENDING");
        when(milestoneMapper.selectList(any())).thenReturn(List.of(m1, m2));

        Map<String, Object> view = service.advanceProject(1L, null, true);
        assertTrue(view.containsKey("project"));
        assertTrue(view.containsKey("milestones"));
        assertEquals(50, p.getProgress());
        assertEquals("MATCHING", p.getStage());
        assertNotNull(m1.getCompletedAt());
        assertEquals("DONE", m1.getStatus());
        assertEquals("IN_PROGRESS", m2.getStatus());
        verify(projectMapper).updateById(p);
    }

    @Test
    void advanceRejectsCompletedProject() {
        TechProject p = project(1L);
        p.setStatus("COMPLETED");
        assertThrows(BusinessException.class, () -> service.advanceProject(1L, 1L, false));
    }

    @Test
    void projectDetailRejectsOtherViewer() {
        project(1L);
        assertThrows(BusinessException.class, () -> service.projectDetail(1L, 2L, false));
    }

    @Test
    void assignTeamRejectsNonBrokerExpert() {
        project(1L);
        TechExpert expert = new TechExpert();
        expert.setExpertId(2L);
        expert.setExpertType("EXPERT");
        when(expertMapper.selectById(2L)).thenReturn(expert);

        assertThrows(BusinessException.class, () -> service.assignTeam(1L, 2L, null),
                "技术经纪人岗位只能指派 BROKER 类型");

        TechExpert broker = new TechExpert();
        broker.setExpertId(3L);
        broker.setExpertType("BROKER");
        when(expertMapper.selectById(3L)).thenReturn(broker);
        TechProject assigned = service.assignTeam(1L, 3L, null);
        assertEquals(3L, assigned.getBrokerId());
    }

    @Test
    void createProjectInitializesMilestonesSequentially() {
        TechProject created = service.createProject(1L, "MATCH", "测试项目", "INTAKE", "等待诊断",
                new BigDecimal("100"), BigDecimal.ZERO, "DEMAND", 5L, null, null,
                new String[]{"需求诊断", "资源匹配"});
        assertNotNull(created.getProjectNo());
        verify(milestoneMapper, times(2)).insert(any(TechMilestone.class));
        verify(projectMapper).insert(any(TechProject.class));
    }

    @Test
    void stagePolicyCoversTransactionAndAppraisal() {
        assertEquals("DUE_DILIGENCE", com.lanlink.shopping.module.techservice.domain.TechProjectStagePolicy
                .stageFor("TRANSACTION", 25));
        assertEquals("CONTRACT", com.lanlink.shopping.module.techservice.domain.TechProjectStagePolicy
                .stageFor("TRANSACTION", 50));
        assertEquals("ACCEPTANCE", com.lanlink.shopping.module.techservice.domain.TechProjectStagePolicy
                .stageFor("APPRAISAL", 75));
        assertEquals("CLOSED", com.lanlink.shopping.module.techservice.domain.TechProjectStagePolicy
                .stageFor("MATCH", 100));
    }
}

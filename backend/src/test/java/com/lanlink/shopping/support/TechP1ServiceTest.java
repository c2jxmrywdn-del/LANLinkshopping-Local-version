package com.lanlink.shopping.support;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechOrgTask;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechPilotBooking;
import com.lanlink.shopping.module.techservice.entity.TechPilotResource;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.entity.TechReview;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrgTaskMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPilotBookingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechPilotResourceMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import com.lanlink.shopping.module.techservice.mapper.TechReviewMapper;
import com.lanlink.shopping.module.techservice.service.TechOrgTaskService;
import com.lanlink.shopping.module.techservice.service.TechPilotService;
import com.lanlink.shopping.module.techservice.service.TechReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** P1：机构任务、履约评价、中试资源与预约。 */
class TechP1ServiceTest {

    private TechOrgTaskMapper taskMapper;
    private TechOrganizationMapper organizationMapper;
    private TechProjectMapper projectMapper;
    private UserMapper userMapper;
    private TechReviewMapper reviewMapper;
    private TechExpertMapper expertMapper;
    private TechPilotResourceMapper resourceMapper;
    private TechPilotBookingMapper bookingMapper;

    private TechOrgTaskService orgTaskService;
    private TechReviewService reviewService;
    private TechPilotService pilotService;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TechOrgTaskMapper.class);
        organizationMapper = mock(TechOrganizationMapper.class);
        projectMapper = mock(TechProjectMapper.class);
        userMapper = mock(UserMapper.class);
        reviewMapper = mock(TechReviewMapper.class);
        expertMapper = mock(TechExpertMapper.class);
        resourceMapper = mock(TechPilotResourceMapper.class);
        bookingMapper = mock(TechPilotBookingMapper.class);
        orgTaskService = new TechOrgTaskService(taskMapper, organizationMapper, projectMapper, userMapper);
        reviewService = new TechReviewService(reviewMapper, projectMapper, expertMapper, organizationMapper);
        pilotService = new TechPilotService(resourceMapper, bookingMapper, projectMapper);
    }

    private TechProject project(Long ownerId, String status) {
        TechProject p = new TechProject();
        p.setProjectId(1L);
        p.setProjectNo("TP-001");
        p.setUserId(ownerId);
        p.setTitle("技术需求陪跑");
        p.setStatus(status);
        return p;
    }

    private TechExpert expert(String type) {
        TechExpert e = new TechExpert();
        e.setExpertId(10L);
        e.setExpertType(type);
        e.setName("周启明");
        e.setStatus(1);
        return e;
    }

    private TechOrgTask task(Long assignee, String status) {
        TechOrgTask t = new TechOrgTask();
        t.setTaskId(1L);
        t.setTaskNo("TOT-001");
        t.setOrgId(2L);
        t.setAssigneeUserId(assignee);
        t.setTitle("评价协作");
        t.setStatus(status);
        return t;
    }

    // ---------- 机构任务 ----------

    @Test
    void createOrgTaskValidatesOrgTypeAndAssignee() {
        when(organizationMapper.selectById(2L)).thenReturn(new TechOrganization());
        assertThrows(BusinessException.class, () -> orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, null, null, "UNKNOWN", "任务", null, null)));
        assertThrows(BusinessException.class, () -> orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, null, null, "EVALUATION", " ", null, null)));
        assertThrows(BusinessException.class, () -> orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, null, 99L, "EVALUATION", "任务", null, null)));

        User user = new User();
        user.setUserId(7L);
        when(userMapper.selectById(7L)).thenReturn(user);
        TechOrgTask created = orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, null, 7L, "EVALUATION", " 验评任务 ", null, null));
        assertEquals("ASSIGNED", created.getStatus());
        assertEquals("验评任务", created.getTitle());
        assertNotNull(created.getTaskNo());
        verify(taskMapper).insert(any(TechOrgTask.class));
    }

    @Test
    void createOrgTaskRejectsUnknownOrgAndProject() {
        assertThrows(BusinessException.class, () -> orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, null, null, "EVALUATION", "任务", null, null)));
        when(organizationMapper.selectById(2L)).thenReturn(new TechOrganization());
        assertThrows(BusinessException.class, () -> orgTaskService.adminCreate(1L,
                new TechP1Requests.OrgTaskCreate(2L, 99L, null, "EVALUATION", "任务", null, null)));
    }

    @Test
    void submitOrgTaskRequiresAssigneeAndProducesEvidence() {
        when(taskMapper.selectOne(any())).thenReturn(task(1L, "ASSIGNED"));
        TechOrgTask submitted = orgTaskService.submit(1L, "TOT-001",
                new TechP1Requests.OrgTaskSubmit("材料清单、过程记录与结论建议"));
        assertEquals("SUBMITTED", submitted.getStatus());
        assertNotNull(submitted.getDeliverableHash());
        assertNotNull(submitted.getSubmittedAt());
        verify(taskMapper).updateById(submitted);

        when(taskMapper.selectOne(any())).thenReturn(task(2L, "ASSIGNED"));
        assertThrows(BusinessException.class, () -> orgTaskService.submit(1L, "TOT-001",
                new TechP1Requests.OrgTaskSubmit("无权限")));
    }

    @Test
    void submitOrgTaskRejectsEmptyAndInvalidStatus() {
        when(taskMapper.selectOne(any())).thenReturn(task(1L, "SUBMITTED"));
        assertThrows(BusinessException.class, () -> orgTaskService.submit(1L, "TOT-001",
                new TechP1Requests.OrgTaskSubmit("再次提交")));
        when(taskMapper.selectOne(any())).thenReturn(task(1L, "ASSIGNED"));
        assertThrows(BusinessException.class, () -> orgTaskService.submit(1L, "TOT-001",
                new TechP1Requests.OrgTaskSubmit("  ")));
    }

    @Test
    void orgTaskReviewOnlyAcceptsSubmittedAndKnownDecision() {
        when(taskMapper.selectOne(any())).thenReturn(task(1L, "ASSIGNED"));
        assertThrows(BusinessException.class, () -> orgTaskService.adminReview("TOT-001",
                new TechP1Requests.OrgTaskReview("ACCEPTED", null)));

        TechOrgTask submitted = task(1L, "SUBMITTED");
        when(taskMapper.selectOne(any())).thenReturn(submitted);
        assertThrows(BusinessException.class, () -> orgTaskService.adminReview("TOT-001",
                new TechP1Requests.OrgTaskReview("MAYBE", null)));

        TechOrgTask accepted = orgTaskService.adminReview("TOT-001",
                new TechP1Requests.OrgTaskReview("ACCEPTED", "材料齐全"));
        assertEquals("ACCEPTED", accepted.getStatus());
        assertNotNull(accepted.getReviewedAt());
        assertEquals("材料齐全", accepted.getReviewNote());
    }

    // ---------- 履约评价 ----------

    @Test
    void reviewRequiresCompletedProjectAndOwnership() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "ACTIVE"));
        TechP1Requests.ReviewCreate req = new TechP1Requests.ReviewCreate(
                "EXPERT", 10L, 5, 5, 5, 5, 5, "很好");
        assertThrows(BusinessException.class, () -> reviewService.submit(1L, 1L, req));

        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        assertThrows(BusinessException.class, () -> reviewService.submit(2L, 1L, req));
    }

    @Test
    void reviewValidatesTargetTypeAndScores() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        when(expertMapper.selectById(10L)).thenReturn(expert("BROKER"));
        assertThrows(BusinessException.class, () -> reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("EXPERT", 10L, 5, 5, 5, 5, 5, null)));
        assertThrows(BusinessException.class, () -> reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("EXPERT", null, 5, 5, 5, 5, 5, null)));
        when(expertMapper.selectById(10L)).thenReturn(expert("EXPERT"));
        assertThrows(BusinessException.class, () -> reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("EXPERT", 10L, 6, 5, 5, 5, 5, null)));
    }

    @Test
    void reviewRejectsDuplicateAndComputesOverallRating() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        when(expertMapper.selectById(10L)).thenReturn(expert("EXPERT"));
        when(reviewMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("EXPERT", 10L, 5, 5, 5, 5, 5, null)));
        verify(reviewMapper, never()).insert(any(TechReview.class));
    }

    @Test
    void reviewInsertUpdatesExpertRating() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        when(expertMapper.selectById(10L)).thenReturn(expert("EXPERT"));
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.insert(any(TechReview.class))).thenAnswer(inv -> 1);
        when(reviewMapper.selectList(any())).thenAnswer(inv -> {
            TechReview saved = new TechReview();
            saved.setOverall(new BigDecimal("4.40"));
            return List.of(saved);
        });

        TechReview review = reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("EXPERT", 10L, 5, 4, 4, 5, 4, "交付稳定"));
        assertEquals(new BigDecimal("4.40"), review.getOverall());
        assertEquals("周启明", review.getTargetName());
        assertEquals("技术需求陪跑", review.getProjectTitle());
        ArgumentCaptor<TechExpert> captor = ArgumentCaptor.forClass(TechExpert.class);
        verify(expertMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("4.40"), captor.getValue().getRating());
    }

    @Test
    void reviewForOrganizationUpdatesOrgRating() {
        TechOrganization org = new TechOrganization();
        org.setOrgId(20L);
        org.setName("沪联技术转移中心");
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        when(organizationMapper.selectById(20L)).thenReturn(org);
        when(reviewMapper.selectCount(any())).thenReturn(0L);
        when(reviewMapper.insert(any(TechReview.class))).thenAnswer(inv -> 1);
        when(reviewMapper.selectList(any())).thenAnswer(inv -> {
            TechReview saved = new TechReview();
            saved.setOverall(new BigDecimal("4.60"));
            return List.of(saved);
        });

        TechReview review = reviewService.submit(1L, 1L,
                new TechP1Requests.ReviewCreate("ORG", 20L, 5, 4, 5, 4, 5, null));
        assertEquals("沪联技术转移中心", review.getTargetName());
        ArgumentCaptor<TechOrganization> captor = ArgumentCaptor.forClass(TechOrganization.class);
        verify(organizationMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("4.60"), captor.getValue().getRating());
    }

    @Test
    void myProjectReviewsProtectsOwnership() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L, "COMPLETED"));
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        assertDoesNotThrow(() -> reviewService.listMineByProject(1L, 1L));
        assertThrows(BusinessException.class, () -> reviewService.listMineByProject(2L, 1L));
    }

    // ---------- 中试资源与预约 ----------

    @Test
    void resourceUpsertValidatesTypePriceAndUpdatesExisting() {
        assertThrows(BusinessException.class, () -> pilotService.upsertResource(
                new TechP1Requests.PilotResourceUpsert(null, " ", "机构", null, "PILOT",
                        null, null, "QUOTE", null, null, 1, 0)));
        assertThrows(BusinessException.class, () -> pilotService.upsertResource(
                new TechP1Requests.PilotResourceUpsert(null, "中试线", "机构", null, "UNKNOWN",
                        null, null, "QUOTE", null, null, 1, 0)));
        assertThrows(BusinessException.class, () -> pilotService.upsertResource(
                new TechP1Requests.PilotResourceUpsert(null, "中试线", "机构", null, "PILOT",
                        null, null, "QUOTE", new BigDecimal("-1"), null, 1, 0)));

        TechPilotResource created = pilotService.upsertResource(
                new TechP1Requests.PilotResourceUpsert(null, "石墨烯中试线", "某中试基地", "上海", "PILOT",
                        "涂布与固化", "涂布线", "FIXED", new BigDecimal("80000"), "联系人", 1, 10));
        assertEquals("PILOT", created.getResourceType());
        assertEquals(1, created.getStatus());
        verify(resourceMapper).insert(any(TechPilotResource.class));

        TechPilotResource existing = new TechPilotResource();
        existing.setResourceId(5L);
        existing.setName("旧名称");
        when(resourceMapper.selectById(5L)).thenReturn(existing);
        TechPilotResource updated = pilotService.upsertResource(
                new TechP1Requests.PilotResourceUpsert(5L, "新名称", "机构", "杭州", "LAB",
                        null, null, "NEGOTIABLE", null, null, 0, 3));
        assertEquals("新名称", updated.getName());
        assertEquals(0, updated.getStatus());
        verify(resourceMapper).updateById(existing);
    }

    @Test
    void bookingRequiresListedResourceAndOwnProject() {
        TechPilotResource resource = new TechPilotResource();
        resource.setResourceId(5L);
        resource.setName("中试线");
        resource.setStatus(1);
        when(resourceMapper.selectById(5L)).thenReturn(resource);
        when(projectMapper.selectById(1L)).thenReturn(project(2L, "ACTIVE"));

        assertThrows(BusinessException.class, () -> pilotService.createBooking(1L,
                new TechP1Requests.PilotBookingCreate(5L, 1L, "需求", LocalDate.now())));
        assertThrows(BusinessException.class, () -> pilotService.createBooking(1L,
                new TechP1Requests.PilotBookingCreate(5L, null, " ", null)));
        assertThrows(BusinessException.class, () -> pilotService.createBooking(1L,
                new TechP1Requests.PilotBookingCreate(5L, null, "需求", LocalDate.now().minusDays(1))));

        resource.setStatus(0);
        assertThrows(BusinessException.class, () -> pilotService.createBooking(1L,
                new TechP1Requests.PilotBookingCreate(5L, null, "需求", null)));
    }

    @Test
    void bookingCreatedAndStatusValidated() {
        TechPilotResource resource = new TechPilotResource();
        resource.setResourceId(5L);
        resource.setName("中试线");
        resource.setStatus(1);
        when(resourceMapper.selectById(5L)).thenReturn(resource);

        TechPilotBooking booking = pilotService.createBooking(1L,
                new TechP1Requests.PilotBookingCreate(5L, null, "验证导热膜", LocalDate.now().plusDays(3)));
        assertEquals("APPLIED", booking.getStatus());
        assertEquals("中试线", booking.getResourceName());
        verify(bookingMapper).insert(any(TechPilotBooking.class));

        TechPilotBooking stored = new TechPilotBooking();
        stored.setBookingNo("PB-001");
        stored.setResourceId(5L);
        stored.setStatus("APPLIED");
        when(bookingMapper.selectOne(any())).thenReturn(stored);
        assertThrows(BusinessException.class, () -> pilotService.updateBookingStatus("PB-001",
                new TechP1Requests.PilotBookingStatus("UNKNOWN", null)));
        TechPilotBooking updated = pilotService.updateBookingStatus("PB-001",
                new TechP1Requests.PilotBookingStatus("accepted", "已排期"));
        assertEquals("ACCEPTED", updated.getStatus());
        assertEquals("已排期", updated.getAdminNote());
        verify(bookingMapper).updateById(stored);
    }

    // ---------- 开放许可材料包 ----------

    @Test
    void openLicenseKitCarriesMaterialsAndBoundary() {
        var kit = TechP1Views.ofOpenLicenseKit(new com.lanlink.shopping.module.techservice.entity.TechAchievement());
        assertEquals(5, kit.documents().size());
        assertEquals(5, kit.process().size());
        assertTrue(kit.complianceNote().contains("不出具备案证明"));
    }
}

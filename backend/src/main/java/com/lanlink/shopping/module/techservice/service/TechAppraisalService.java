package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechAppraisalCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechAchievement;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechAchievementMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 科技成果评价受理聚合。
 *
 * <p>平台只做材料辅导与流程跟踪，评价结论由合作机构按标准出具；
 * 后台更新进度时同样只允许回填机构已确认的结论与报告编号。
 */
@Service
public class TechAppraisalService {

    private static final Set<String> APPRAISAL_STATUSES = Set.of(
            "SUBMITTED", "MATERIAL_REVIEW", "EXPERT_REVIEW", "REPORT_ISSUED", "REJECTED");

    private final TechAppraisalMapper appraisalMapper;
    private final TechAchievementMapper achievementMapper;
    private final TechOrganizationMapper organizationMapper;
    private final TechProjectService projectService;
    private final TechComplianceGuard complianceGuard;
    private final ApplicationEventPublisher eventPublisher;

    public TechAppraisalService(TechAppraisalMapper appraisalMapper,
                                TechAchievementMapper achievementMapper,
                                TechOrganizationMapper organizationMapper,
                                TechProjectService projectService,
                                TechComplianceGuard complianceGuard,
                                ApplicationEventPublisher eventPublisher) {
        this.appraisalMapper = appraisalMapper;
        this.achievementMapper = achievementMapper;
        this.organizationMapper = organizationMapper;
        this.projectService = projectService;
        this.complianceGuard = complianceGuard;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechAppraisal createAppraisal(Long userId, TechAppraisalCreateRequest req) {
        complianceGuard.validate("成果名称", req.getTitle());
        TechOrganization institution = organizationMapper.selectById(req.getInstitutionId());
        if (institution == null || !Integer.valueOf(1).equals(institution.getStatus())) {
            throw new BusinessException(404, "合作评价机构不存在");
        }
        // 不校验就会凭一个不存在的 id 建出评价单与陪跑项目，后续材料、报告、对账全挂在空气上。
        // 不存在与不属于本人都回 404：403 会让别人拿返回值枚举谁的成果存在。
        TechAchievement achievement = achievementMapper.selectById(req.getAchievementId());
        if (achievement == null || !Objects.equals(achievement.getOwnerUserId(), userId)) {
            throw new BusinessException(404, "成果不存在");
        }
        TechAppraisal appraisal = new TechAppraisal();
        appraisal.setAppraisalNo(TechNoGenerator.next("TA"));
        appraisal.setUserId(userId);
        appraisal.setAchievementId(req.getAchievementId());
        appraisal.setInstitutionId(institution.getOrgId());
        appraisal.setStandardCode("GB/T 44731-2024");
        appraisal.setStatus("SUBMITTED");
        appraisal.setDisclaimer("平台不直接出具评价结论；评价报告由合作机构按约定标准与程序出具，平台提供材料辅导与流程跟踪。");
        appraisalMapper.insert(appraisal);

        var project = projectService.createProject(userId, "APPRAISAL", "成果评价 · " + req.getTitle(), "INTAKE",
                "等待机构完成材料预审", BigDecimal.ZERO, BigDecimal.ZERO,
                "APPRAISAL", appraisal.getAppraisalId(), null, null,
                new String[]{"材料预审", "专家评审", "报告出具", "异议处理"});
        appraisal.setProjectId(project.getProjectId());
        appraisalMapper.updateById(appraisal);

        eventPublisher.publishEvent(new TechSideEffectEvent(userId, "成果评价申请已提交",
                "「" + req.getTitle() + "」已提交至 " + institution.getName()
                        + "，陪跑项目 " + project.getProjectNo() + " 已创建，平台将协助完成材料清单与进度跟踪。",
                appraisal.getAppraisalNo()));
        return appraisal;
    }

    public List<TechAppraisal> myAppraisals(Long userId) {
        return appraisalMapper.selectList(Wrappers.<TechAppraisal>lambdaQuery()
                .eq(TechAppraisal::getUserId, userId).orderByDesc(TechAppraisal::getCreateTime));
    }

    /** 运营侧更新受理进度（结论与报告编号由合作机构回填） */
    @Transactional
    public TechAppraisal updateStatus(Long appraisalId, String status, String conclusionLevel, String reportNo) {
        if (!APPRAISAL_STATUSES.contains(status)) {
            throw new BusinessException(400, "评价状态不合法");
        }
        TechAppraisal appraisal = appraisalMapper.selectById(appraisalId);
        if (appraisal == null) {
            throw new BusinessException(404, "评价申请不存在");
        }
        appraisal.setStatus(status);
        if (conclusionLevel != null && !conclusionLevel.isBlank()) {
            appraisal.setConclusionLevel(conclusionLevel.trim());
        }
        if (reportNo != null && !reportNo.isBlank()) {
            appraisal.setReportNo(reportNo.trim());
        }
        appraisalMapper.updateById(appraisal);
        return appraisal;
    }
}

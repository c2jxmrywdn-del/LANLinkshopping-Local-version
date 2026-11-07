package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechHostingCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechHostingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 托管聚合：专利盘活 / 机构托管 / SaaS 工具托管。 */
@Service
public class TechHostingService {

    private static final Set<String> HOSTING_TYPES = Set.of("PATENT", "AGENCY", "SAAS");
    private static final Set<String> HOSTING_STATUSES = Set.of(
            "APPLIED", "ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED");
    /** 计费方式白名单，与 t_tech_hosting.fee_mode 注释同一口径；不校验会把任意字符串写进台账 */
    private static final Set<String> FEE_MODES = Set.of("ANNUAL", "COMMISSION", "SUBSCRIPTION");

    private final TechHostingMapper hostingMapper;
    private final TechHostingTargetResolver targetResolver;
    private final TechOrganizationMapper organizationMapper;
    private final TechProjectService projectService;
    private final TechComplianceGuard complianceGuard;
    private final ApplicationEventPublisher eventPublisher;

    public TechHostingService(TechHostingMapper hostingMapper,
                              TechHostingTargetResolver targetResolver,
                              TechOrganizationMapper organizationMapper,
                              TechProjectService projectService,
                              TechComplianceGuard complianceGuard,
                              ApplicationEventPublisher eventPublisher) {
        this.hostingMapper = hostingMapper;
        this.targetResolver = targetResolver;
        this.organizationMapper = organizationMapper;
        this.projectService = projectService;
        this.complianceGuard = complianceGuard;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechHosting createHosting(Long userId, TechHostingCreateRequest req) {
        String type = req.getHostingType() == null ? "" : req.getHostingType().trim();
        if (!HOSTING_TYPES.contains(type)) {
            throw new BusinessException(400, "不支持的托管类型");
        }
        complianceGuard.validate("托管范围", req.getScope());
        complianceGuard.validate("激活计划", req.getActivationPlan());
        String targetName = targetResolver.resolve(type, req.getTargetId(), req.getTargetName());

        if (req.getProviderOrgId() != null) {
            TechOrganization org = organizationMapper.selectById(req.getProviderOrgId());
            if (org == null || !Integer.valueOf(1).equals(org.getStatus())) {
                throw new BusinessException(404, "服务机构不存在");
            }
        }
        LocalDate start = req.getStartDate() == null ? LocalDate.now() : req.getStartDate();
        LocalDate end = req.getEndDate() == null ? start.plusYears(1) : req.getEndDate();
        if (end.isBefore(start)) {
            throw new BusinessException(400, "托管结束日期不能早于开始日期");
        }
        TechHosting hosting = new TechHosting();
        hosting.setHostingNo(TechNoGenerator.next("TH"));
        hosting.setUserId(userId);
        hosting.setHostingType(type);
        hosting.setTargetId(req.getTargetId());
        hosting.setTargetName(targetName);
        hosting.setProviderOrgId(req.getProviderOrgId());
        hosting.setScope(req.getScope().trim());
        hosting.setStartDate(start);
        hosting.setEndDate(end);
        String feeMode = req.getFeeMode() == null || req.getFeeMode().isBlank() ? "ANNUAL" : req.getFeeMode().trim();
        if (!FEE_MODES.contains(feeMode)) {
            throw new BusinessException(400, "计费方式只能是年费、分成或订阅");
        }
        hosting.setFeeMode(feeMode);
        hosting.setFeeAmount(req.getFeeAmount() == null ? BigDecimal.ZERO : req.getFeeAmount());
        hosting.setCommissionRate(req.getCommissionRate() == null ? new BigDecimal("15.00") : req.getCommissionRate());
        hosting.setStatus("APPLIED");
        hosting.setActivationPlan(req.getActivationPlan());
        hostingMapper.insert(hosting);

        var project = projectService.createProject(userId, "HOSTING", "托管服务 · " + hosting.getTargetName(), "INTAKE",
                "等待平台完成权属与材料核查", hosting.getFeeAmount(), hosting.getCommissionRate(),
                "HOSTING", hosting.getHostingId(), null, null,
                new String[]{"权属核查", "托管协议", "运营计划", "阶段复盘"});
        hosting.setProjectId(project.getProjectId());
        hostingMapper.updateById(hosting);

        eventPublisher.publishEvent(new TechSideEffectEvent(userId, "托管申请已提交",
                "「" + hosting.getTargetName() + "」托管申请已受理，陪跑项目 " + project.getProjectNo()
                        + " 已创建。平台将先完成权属与材料核查，再与您确认运营计划。", hosting.getHostingNo()));
        return hosting;
    }

    public List<TechHosting> myHostings(Long userId) {
        return hostingMapper.selectList(Wrappers.<TechHosting>lambdaQuery()
                .eq(TechHosting::getUserId, userId).orderByDesc(TechHosting::getCreateTime));
    }

    /** 运营侧更新托管状态 */
    @Transactional
    public TechHosting updateStatus(Long hostingId, String status) {
        if (!HOSTING_STATUSES.contains(status)) {
            throw new BusinessException(400, "托管状态不合法");
        }
        TechHosting hosting = hostingMapper.selectById(hostingId);
        if (hosting == null) {
            throw new BusinessException(404, "托管申请不存在");
        }
        hosting.setStatus(status);
        hostingMapper.updateById(hosting);
        return hosting;
    }
}

package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechDemandCreateRequest;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.event.TechSideEffectEvent;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 技术需求聚合：发布需求、状态流转与我的需求。
 *
 * <p>跨聚合编排（需求 → 陪跑项目）在此完成；站内消息改为事务提交后的事件，事务内不写消息表。
 */
@Service
public class TechDemandService {

    private static final Set<String> DEMAND_STATUSES = Set.of("MATCHING", "IN_PROGRESS", "CLOSED");

    private final TechDemandMapper demandMapper;
    private final TechProjectService projectService;
    private final TechComplianceGuard complianceGuard;
    private final ApplicationEventPublisher eventPublisher;

    public TechDemandService(TechDemandMapper demandMapper,
                             TechProjectService projectService,
                             TechComplianceGuard complianceGuard,
                             ApplicationEventPublisher eventPublisher) {
        this.demandMapper = demandMapper;
        this.projectService = projectService;
        this.complianceGuard = complianceGuard;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TechDemand createDemand(Long userId, TechDemandCreateRequest req) {
        if (req.getBudgetMin() != null && req.getBudgetMax() != null
                && req.getBudgetMin().compareTo(req.getBudgetMax()) > 0) {
            throw new BusinessException(400, "预算下限不能高于预算上限");
        }
        complianceGuard.validate("需求标题", req.getTitle());
        complianceGuard.validate("需求描述", req.getDescription());
        complianceGuard.validate("预期成果", req.getExpectedOutcome());

        TechDemand demand = new TechDemand();
        demand.setDemandNo(TechNoGenerator.next("TD"));
        demand.setUserId(userId);
        demand.setTitle(req.getTitle().trim());
        demand.setIndustry(req.getIndustry().trim());
        demand.setTechField(req.getTechField().trim());
        demand.setDescription(req.getDescription().trim());
        demand.setPainPoints(req.getPainPoints());
        demand.setExpectedOutcome(req.getExpectedOutcome());
        demand.setBudgetMin(req.getBudgetMin());
        demand.setBudgetMax(req.getBudgetMax());
        demand.setDeadline(req.getDeadline());
        demand.setAnonymous(Boolean.TRUE.equals(req.getAnonymous()) ? 1 : 0);
        demand.setStatus("MATCHING");
        demand.setViewCount(0);
        demand.setMatchCount(1);
        demandMapper.insert(demand);

        var project = projectService.createProject(userId, "MATCH", demand.getTitle(), "INTAKE",
                "技术经纪人将在 1 个工作日内完成需求诊断",
                req.getBudgetMax() == null ? BigDecimal.ZERO : req.getBudgetMax(), BigDecimal.ZERO,
                "DEMAND", demand.getDemandId(), null, null,
                new String[]{"需求诊断", "资源匹配", "对接洽谈", "签约转化"});

        eventPublisher.publishEvent(new TechSideEffectEvent(userId, "技术需求已发布",
                "需求「" + demand.getTitle() + "」已进入匹配队列，陪跑项目 " + project.getProjectNo()
                        + " 已创建，技术经纪人将在 1 个工作日内联系您。", demand.getDemandNo()));
        return demand;
    }

    public List<TechDemand> myDemands(Long userId) {
        return demandMapper.selectList(Wrappers.<TechDemand>lambdaQuery()
                .eq(TechDemand::getUserId, userId).orderByDesc(TechDemand::getCreateTime));
    }

    /** 运营侧需求状态流转 */
    @Transactional
    public TechDemand updateStatus(Long demandId, String status) {
        if (!DEMAND_STATUSES.contains(status)) {
            throw new BusinessException(400, "需求状态不合法");
        }
        TechDemand demand = demandMapper.selectById(demandId);
        if (demand == null) {
            throw new BusinessException(404, "需求不存在");
        }
        demand.setStatus(status);
        demandMapper.updateById(demand);
        return demand;
    }
}

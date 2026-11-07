package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.domain.TechProjectStagePolicy;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechMilestone;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechMilestoneMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 陪跑项目聚合（项目 + 里程碑）的唯一写入口。
 *
 * <p>归属校验显式化：{@link #advanceProject(Long, Long, boolean)} 通过 actorId + admin 标记判断，
 * 不再向后台暴露「跳过校验」的包级私有方法。
 */
@Service
public class TechProjectService {

    private final TechProjectMapper projectMapper;
    private final TechMilestoneMapper milestoneMapper;
    private final TechExpertMapper expertMapper;

    public TechProjectService(TechProjectMapper projectMapper,
                              TechMilestoneMapper milestoneMapper,
                              TechExpertMapper expertMapper) {
        this.projectMapper = projectMapper;
        this.milestoneMapper = milestoneMapper;
        this.expertMapper = expertMapper;
    }

    /**
     * 创建陪跑项目并初始化里程碑。仅供模块内其他聚合（需求/咨询/评价/托管/交易）编排调用。
     */
    @Transactional
    public TechProject createProject(Long userId, String type, String title, String stage, String nextAction,
                                     BigDecimal amount, BigDecimal commissionRate,
                                     String sourceType, Long sourceId,
                                     Long expertId, Long brokerId, String[] milestoneNames) {
        TechProject project = new TechProject();
        project.setProjectNo(TechNoGenerator.next("TP"));
        project.setUserId(userId);
        project.setProjectType(type);
        project.setTitle(title == null ? "" : (title.length() > 160 ? title.substring(0, 160) : title));
        project.setStage(stage);
        project.setStatus("ACTIVE");
        project.setProgress(0);
        project.setOwnerExpertId(expertId);
        project.setBrokerId(brokerId);
        project.setAmount(amount == null ? BigDecimal.ZERO : amount);
        project.setCommissionRate(commissionRate == null ? BigDecimal.ZERO : commissionRate);
        project.setSourceType(sourceType);
        project.setSourceId(sourceId);
        project.setNextAction(nextAction);
        projectMapper.insert(project);

        int seq = 1;
        for (String name : milestoneNames) {
            TechMilestone milestone = new TechMilestone();
            milestone.setProjectId(project.getProjectId());
            milestone.setSeq(seq);
            milestone.setName(name);
            milestone.setStatus(seq == 1 ? "IN_PROGRESS" : "PENDING");
            milestone.setDueDate(LocalDate.now().plusDays(7L * seq));
            milestoneMapper.insert(milestone);
            seq++;
        }
        return project;
    }

    /**
     * 推进到下一个未完成里程碑。
     *
     * @param actorUserId 操作人；admin=true 时不再校验归属（/admin/** 已在 Web 层鉴权）
     */
    @Transactional
    public Map<String, Object> advanceProject(Long projectId, Long actorUserId, boolean admin) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (!admin && !Objects.equals(project.getUserId(), actorUserId)) {
            throw new BusinessException(403, "无权操作该项目");
        }
        return advanceCore(project);
    }

    private Map<String, Object> advanceCore(TechProject project) {
        if ("COMPLETED".equals(project.getStatus())) {
            throw new BusinessException(400, "项目已完成");
        }
        List<TechMilestone> milestones = milestoneMapper.selectList(Wrappers.<TechMilestone>lambdaQuery()
                .eq(TechMilestone::getProjectId, project.getProjectId()).orderByAsc(TechMilestone::getSeq));
        TechMilestone current = milestones.stream()
                .filter(m -> !"DONE".equals(m.getStatus())).findFirst().orElse(null);
        if (current == null) {
            project.setStatus("COMPLETED");
            project.setStage("CLOSED");
            project.setProgress(100);
            project.setNextAction("项目已归档，可发起服务评价");
            projectMapper.updateById(project);
            return view(project);
        }
        current.setStatus("DONE");
        current.setCompletedAt(LocalDateTime.now());
        milestoneMapper.updateById(current);

        long done = milestones.stream().filter(m -> "DONE".equals(m.getStatus())).count();
        int progress = (int) Math.round(done * 100.0 / milestones.size());
        TechMilestone next = milestones.stream()
                .filter(m -> !"DONE".equals(m.getStatus())).findFirst().orElse(null);
        if (next != null) {
            next.setStatus("IN_PROGRESS");
            milestoneMapper.updateById(next);
        }
        project.setProgress(progress);
        project.setStage(TechProjectStagePolicy.stageFor(project.getProjectType(), progress));
        project.setStatus(next == null ? "COMPLETED" : "ACTIVE");
        project.setNextAction(next == null ? "项目已归档，可发起服务评价" : "等待完成：" + next.getName());
        projectMapper.updateById(project);
        return view(project);
    }

    /** 指派陪跑团队：技术经纪人 + 责任专家（运营操作） */
    @Transactional
    public TechProject assignTeam(Long projectId, Long brokerId, Long ownerExpertId) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (brokerId != null) {
            TechExpert broker = expertMapper.selectById(brokerId);
            if (broker == null || !"BROKER".equals(broker.getExpertType())) {
                throw new BusinessException(400, "请选择有效的技术经纪人");
            }
            project.setBrokerId(brokerId);
        }
        if (ownerExpertId != null) {
            TechExpert expert = expertMapper.selectById(ownerExpertId);
            if (expert == null) {
                throw new BusinessException(400, "请选择有效的责任专家");
            }
            project.setOwnerExpertId(ownerExpertId);
        }
        projectMapper.updateById(project);
        return project;
    }

    /**
     * 验收通过后归档项目：全部里程碑置完成，项目状态 COMPLETED、进度 100%。
     * 仅用于验收服务内部编排；归属校验由调用方先行完成。
     */
    @Transactional
    public Map<String, Object> completeProject(Long projectId, Long actorUserId, boolean admin) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (!admin && !Objects.equals(project.getUserId(), actorUserId)) {
            throw new BusinessException(403, "无权操作该项目");
        }
        if ("COMPLETED".equals(project.getStatus())) {
            return view(project);
        }
        List<TechMilestone> milestones = milestoneMapper.selectList(Wrappers.<TechMilestone>lambdaQuery()
                .eq(TechMilestone::getProjectId, projectId));
        for (TechMilestone milestone : milestones) {
            if (!"DONE".equals(milestone.getStatus())) {
                milestone.setStatus("DONE");
                milestone.setCompletedAt(LocalDateTime.now());
                milestoneMapper.updateById(milestone);
            }
        }
        project.setStatus("COMPLETED");
        project.setStage("CLOSED");
        project.setProgress(100);
        project.setNextAction("项目已验收归档，可发起复购或新需求");
        projectMapper.updateById(project);
        return view(project);
    }

    /** 项目详情（含里程碑与陪跑团队），归属校验显式化 */
    public Map<String, Object> projectDetail(Long projectId, Long viewerUserId, boolean admin) {
        TechProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(404, "陪跑项目不存在");
        }
        if (!admin && !Objects.equals(project.getUserId(), viewerUserId)) {
            throw new BusinessException(403, "无权查看该项目");
        }
        return view(project);
    }

    public List<TechProject> myProjects(Long userId) {
        return projectMapper.selectList(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getUserId, userId).orderByDesc(TechProject::getCreateTime));
    }

    private Map<String, Object> view(TechProject project) {
        List<TechMilestone> milestones = milestoneMapper.selectList(Wrappers.<TechMilestone>lambdaQuery()
                .eq(TechMilestone::getProjectId, project.getProjectId()).orderByAsc(TechMilestone::getSeq));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project);
        out.put("milestones", milestones);
        if (project.getOwnerExpertId() != null) {
            out.put("expert", expertMapper.selectById(project.getOwnerExpertId()));
        }
        if (project.getBrokerId() != null) {
            out.put("broker", expertMapper.selectById(project.getBrokerId()));
        }
        return out;
    }
}

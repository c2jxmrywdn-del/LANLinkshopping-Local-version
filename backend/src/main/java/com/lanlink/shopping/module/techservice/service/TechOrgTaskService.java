package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.mapper.UserMapper;
import com.lanlink.shopping.module.techservice.domain.TechEvidenceHasher;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.entity.TechOrgTask;
import com.lanlink.shopping.module.techservice.entity.TechOrganization;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechOrgTaskMapper;
import com.lanlink.shopping.module.techservice.mapper.TechOrganizationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 机构交付任务聚合（P1 机构工作台）：平台派单 → 机构提交交付物 → 平台验收。
 *
 * <p>机构只提交过程材料与结论建议，评价结论仍由具备资质的评价机构出具；
 * 平台不代替机构出具报告，也不介入机构与客户的私下委托。
 */
@Service
public class TechOrgTaskService {

    private static final Set<String> TASK_TYPES = Set.of("EVALUATION", "DELIVERY", "AGENCY", "APPRAISAL");
    private static final Set<String> SUBMITTABLE = Set.of("ASSIGNED", "IN_PROGRESS", "REJECTED");
    private static final Set<String> DECISIONS = Set.of("ACCEPTED", "REJECTED");

    private final TechOrgTaskMapper taskMapper;
    private final TechOrganizationMapper organizationMapper;
    private final TechProjectMapper projectMapper;
    private final UserMapper userMapper;

    public TechOrgTaskService(TechOrgTaskMapper taskMapper,
                              TechOrganizationMapper organizationMapper,
                              TechProjectMapper projectMapper,
                              UserMapper userMapper) {
        this.taskMapper = taskMapper;
        this.organizationMapper = organizationMapper;
        this.projectMapper = projectMapper;
        this.userMapper = userMapper;
    }

    @Transactional
    public TechOrgTask adminCreate(Long adminUserId, TechP1Requests.OrgTaskCreate req) {
        if (req.orgId() == null) {
            throw new BusinessException(400, "请选择承接机构");
        }
        TechOrganization org = organizationMapper.selectById(req.orgId());
        if (org == null) {
            throw new BusinessException(404, "机构不存在");
        }
        String taskType = text(req.taskType());
        taskType = taskType == null ? "EVALUATION" : taskType.toUpperCase();
        if (!TASK_TYPES.contains(taskType)) {
            throw new BusinessException(400, "不支持的任务类型");
        }
        String title = text(req.title());
        if (title == null) {
            throw new BusinessException(400, "请填写任务标题");
        }
        if (title.length() > 200) {
            throw new BusinessException(400, "任务标题不能超过 200 字");
        }
        String scope = text(req.scope());
        if (scope != null && scope.length() > 1000) {
            throw new BusinessException(400, "任务范围不能超过 1000 字");
        }
        Long projectId = req.projectId();
        if (projectId != null) {
            TechProject project = projectMapper.selectById(projectId);
            if (project == null) {
                throw new BusinessException(404, "关联项目不存在");
            }
        }
        Long assigneeUserId = req.assigneeUserId();
        if (assigneeUserId != null) {
            User assignee = userMapper.selectById(assigneeUserId);
            if (assignee == null) {
                throw new BusinessException(404, "承接人账号不存在");
            }
        }

        TechOrgTask task = new TechOrgTask();
        task.setTaskNo(TechNoGenerator.next("TOT"));
        task.setOrgId(org.getOrgId());
        task.setProjectId(projectId);
        task.setAssigneeUserId(assigneeUserId);
        task.setTaskType(taskType);
        task.setTitle(title);
        task.setScope(scope);
        task.setStatus("ASSIGNED");
        task.setDueDate(req.dueDate());
        task.setCreatedBy(adminUserId);
        taskMapper.insert(task);
        return task;
    }

    public Page<TechOrgTask> adminPage(long page, long size, String status, String keyword, Long orgId) {
        String st = text(status);
        String kw = text(keyword);
        return taskMapper.selectPage(TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE),
                Wrappers.<TechOrgTask>lambdaQuery()
                        .eq(st != null, TechOrgTask::getStatus, st)
                        .eq(orgId != null, TechOrgTask::getOrgId, orgId)
                        .and(kw != null, w -> w.like(TechOrgTask::getTaskNo, kw)
                                .or().like(TechOrgTask::getTitle, kw))
                        .orderByDesc(TechOrgTask::getCreateTime));
    }

    public List<TechOrgTask> myTasks(Long userId) {
        return taskMapper.selectList(Wrappers.<TechOrgTask>lambdaQuery()
                .eq(TechOrgTask::getAssigneeUserId, userId)
                .orderByDesc(TechOrgTask::getCreateTime));
    }

    public TechOrgTask detail(Long userId, String taskNo) {
        return loadForAssignee(userId, taskNo);
    }

    @Transactional
    public TechOrgTask submit(Long userId, String taskNo, TechP1Requests.OrgTaskSubmit req) {
        TechOrgTask task = loadForAssignee(userId, taskNo);
        if (!SUBMITTABLE.contains(task.getStatus())) {
            throw new BusinessException(400, "当前任务状态不可提交交付物");
        }
        String deliverable = text(req == null ? null : req.deliverableText());
        if (deliverable == null) {
            throw new BusinessException(400, "请填写交付说明");
        }
        if (deliverable.length() > 2000) {
            throw new BusinessException(400, "交付说明不能超过 2000 字");
        }
        LocalDateTime submittedAt = LocalDateTime.now();
        task.setDeliverableText(deliverable);
        task.setDeliverableHash(TechEvidenceHasher.sha256(taskNo + "|" + deliverable + "|" + submittedAt));
        task.setSubmittedAt(submittedAt);
        task.setStatus("SUBMITTED");
        taskMapper.updateById(task);
        return task;
    }

    @Transactional
    public TechOrgTask adminReview(String taskNo, TechP1Requests.OrgTaskReview req) {
        TechOrgTask task = load(taskNo);
        if (!"SUBMITTED".equals(task.getStatus())) {
            throw new BusinessException(400, "仅已提交的任务可验收");
        }
        String decision = text(req == null ? null : req.decision());
        decision = decision == null ? "" : decision.toUpperCase();
        if (!DECISIONS.contains(decision)) {
            throw new BusinessException(400, "验收结论只能是 ACCEPTED 或 REJECTED");
        }
        String note = text(req.note());
        if (note != null && note.length() > 500) {
            throw new BusinessException(400, "验收意见不能超过 500 字");
        }
        task.setStatus(decision);
        task.setReviewNote(note);
        task.setReviewedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        return task;
    }

    private TechOrgTask load(String taskNo) {
        TechOrgTask task = taskMapper.selectOne(Wrappers.<TechOrgTask>lambdaQuery()
                .eq(TechOrgTask::getTaskNo, taskNo).last("LIMIT 1"));
        if (task == null) {
            throw new BusinessException(404, "机构任务不存在");
        }
        return task;
    }

    private TechOrgTask loadForAssignee(Long userId, String taskNo) {
        TechOrgTask task = load(taskNo);
        if (!Objects.equals(task.getAssigneeUserId(), userId)) {
            throw new BusinessException(403, "无权操作该机构任务");
        }
        return task;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

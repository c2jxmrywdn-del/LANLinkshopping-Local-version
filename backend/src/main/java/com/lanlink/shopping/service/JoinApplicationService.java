package com.lanlink.shopping.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.dto.JoinApplyDTO;
import com.lanlink.shopping.entity.JoinApplication;
import com.lanlink.shopping.mapper.JoinApplicationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * “加入我们”业务闭环：
 * 提交（校验 + 防重复待审 + 落库 + 站内消息）→ 运营审核（通过/驳回+备注）→ 结果站内消息 → 用户侧进度查询。
 */
@Service
public class JoinApplicationService {

    private static final Logger log = LoggerFactory.getLogger(JoinApplicationService.class);

    private static final String TYPE_JOB = "job";
    private static final String TYPE_PARTNER = "partner";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";

    private final JoinApplicationMapper mapper;
    private final MessageService messageService;

    public JoinApplicationService(JoinApplicationMapper mapper, MessageService messageService) {
        this.mapper = mapper;
        this.messageService = messageService;
    }

    /** 提交申请：同一类型存在待审核申请时拒绝，避免重复投递刷量。 */
    @Transactional
    public JoinApplication apply(Long userId, JoinApplyDTO dto) {
        String type = dto.getApplyType();
        String position = text(dto.getPosition());
        String company = text(dto.getCompanyName());

        if (TYPE_JOB.equals(type) && position.isEmpty()) {
            throw new BusinessException("请选择或填写应聘职位");
        }
        if (TYPE_PARTNER.equals(type) && company.isEmpty()) {
            throw new BusinessException("请填写公司/机构名称");
        }
        Long pending = mapper.selectCount(Wrappers.<JoinApplication>lambdaQuery()
                .eq(JoinApplication::getUserId, userId)
                .eq(JoinApplication::getApplyType, type)
                .eq(JoinApplication::getStatus, STATUS_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException(TYPE_JOB.equals(type)
                    ? "您已有一条待审核的求职申请，请耐心等待结果"
                    : "您已有一条待审核的合作申请，请耐心等待结果");
        }

        JoinApplication app = new JoinApplication();
        app.setUserId(userId);
        app.setApplyType(type);
        app.setPosition(position.isEmpty() ? null : position);
        app.setCompanyName(company.isEmpty() ? null : company);
        app.setContactName(text(dto.getContactName()));
        app.setPhone(text(dto.getPhone()));
        app.setEmail(blankToNull(dto.getEmail()));
        app.setCity(blankToNull(dto.getCity()));
        app.setExperience(blankToNull(dto.getExperience()));
        app.setMessage(blankToNull(dto.getMessage()));
        app.setStatus(STATUS_PENDING);
        app.setApplyTime(LocalDateTime.now());
        mapper.insert(app);

        String label = TYPE_JOB.equals(type) ? "求职申请" : "合作申请";
        messageService.send(userId, "system", label + "已提交",
                "您的申请已提交，平台将在 3 个工作日内完成审核。",
                String.valueOf(app.getId()));
        log.info("[join] 新申请 id={}, userId={}, type={}", app.getId(), userId, type);
        return app;
    }

    /** 我的申请（倒序） */
    public List<JoinApplication> myList(Long userId) {
        return mapper.selectList(Wrappers.<JoinApplication>lambdaQuery()
                .eq(JoinApplication::getUserId, userId)
                .orderByDesc(JoinApplication::getApplyTime));
    }

    /** 运营端列表：可按类型/状态筛选 */
    public List<JoinApplication> adminList(String applyType, String status) {
        return mapper.selectList(Wrappers.<JoinApplication>lambdaQuery()
                .eq(hasText(applyType), JoinApplication::getApplyType, applyType)
                .eq(hasText(status), JoinApplication::getStatus, status)
                .orderByDesc(JoinApplication::getApplyTime));
    }

    /** 运营审核：仅待审核可处理；驳回必须填备注。 */
    @Transactional
    public JoinApplication review(Long adminUserId, Long id, boolean approved, String remark) {
        JoinApplication app = mapper.selectById(id);
        if (app == null) {
            throw new BusinessException("申请不存在");
        }
        if (!STATUS_PENDING.equals(app.getStatus())) {
            throw new BusinessException("该申请已处理，请勿重复审核");
        }
        String note = text(remark);
        if (!approved && note.isEmpty()) {
            throw new BusinessException("驳回时必须填写原因");
        }

        app.setStatus(approved ? STATUS_APPROVED : STATUS_REJECTED);
        app.setReviewRemark(note.isEmpty() ? null : note);
        app.setReviewBy(adminUserId);
        app.setReviewedTime(LocalDateTime.now());
        app.setUpdateTime(LocalDateTime.now());
        if (mapper.updateById(app) != 1) {
            throw new BusinessException("该申请已被其他操作处理");
        }

        String label = TYPE_JOB.equals(app.getApplyType()) ? "求职申请" : "合作申请";
        messageService.send(app.getUserId(), "system",
                approved ? label + "已通过" : label + "未通过",
                approved
                        ? "您的申请已通过审核，我们会尽快与您联系。" + (note.isEmpty() ? "" : " 备注：" + note)
                        : "您的申请未通过审核。原因：" + note,
                String.valueOf(app.getId()));
        log.info("[join] 审核完成 id={}, approved={}, adminUserId={}", id, approved, adminUserId);
        return app;
    }

    private static String text(String v) {
        return v == null ? "" : v.trim();
    }

    private static String blankToNull(String v) {
        String t = text(v);
        return t.isEmpty() ? null : t;
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }
}

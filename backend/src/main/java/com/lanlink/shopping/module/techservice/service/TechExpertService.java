package com.lanlink.shopping.module.techservice.service;

import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.dto.TechExpertApplyRequest;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 专家 / 技术经纪人资源池写入口：主动申请、定向邀请、资质审核。
 * 查询（分页、详情、目录）由独立 QueryService 承担。
 */
@Service
public class TechExpertService {

    private static final Set<String> EXPERT_TYPES = Set.of("EXPERT", "BROKER");

    private final TechExpertMapper expertMapper;
    private final TechComplianceGuard complianceGuard;

    public TechExpertService(TechExpertMapper expertMapper, TechComplianceGuard complianceGuard) {
        this.expertMapper = expertMapper;
        this.complianceGuard = complianceGuard;
    }

    /** 用户主动申请入驻：进入待审核队列，不直接上架 */
    @Transactional
    public TechExpert applyExpert(Long userId, TechExpertApplyRequest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "请填写姓名");
        }
        validateCompliance(req);
        TechExpert expert = new TechExpert();
        expert.setUserId(userId);
        expert.setExpertType(normalizeType(req.getExpertType()));
        expert.setName(req.getName().trim());
        expert.setTitle(blankToNull(req.getTitle()));
        expert.setOrganization(req.getOrganization().trim());
        expert.setIndustries(blankToNull(req.getIndustries()));
        expert.setDomains(blankToNull(req.getDomains()));
        expert.setBio(blankToNull(req.getBio()));
        expert.setCredentials(blankToNull(req.getCredentials()));
        expert.setConsultFee(req.getConsultFee() == null ? BigDecimal.ZERO : req.getConsultFee());
        expert.setVerified(0);
        expert.setInviteStatus("APPLIED");
        expert.setRating(BigDecimal.ZERO);
        expert.setServiceCount(0);
        expert.setResponseHours(24);
        expert.setSort(0);
        expert.setStatus(0);
        expertMapper.insert(expert);
        return expert;
    }

    /** 定向邀请入驻：运营录入并即时上架，inviteStatus=INVITED */
    @Transactional
    public TechExpert inviteExpert(TechExpertApplyRequest req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(400, "请填写专家姓名");
        }
        validateCompliance(req);
        TechExpert expert = new TechExpert();
        expert.setExpertType(normalizeType(req.getExpertType()));
        expert.setName(req.getName().trim());
        expert.setTitle(blankToNull(req.getTitle()));
        expert.setOrganization(req.getOrganization() == null ? "" : req.getOrganization().trim());
        expert.setIndustries(blankToNull(req.getIndustries()));
        expert.setDomains(blankToNull(req.getDomains()));
        expert.setBio(blankToNull(req.getBio()));
        expert.setCredentials(blankToNull(req.getCredentials()));
        expert.setConsultFee(req.getConsultFee() == null ? BigDecimal.ZERO : req.getConsultFee());
        expert.setVerified(1);
        expert.setInviteStatus("INVITED");
        expert.setRating(new BigDecimal("4.80"));
        expert.setServiceCount(0);
        expert.setResponseHours(12);
        expert.setSort(60);
        expert.setStatus(1);
        expertMapper.insert(expert);
        return expert;
    }

    /**
     * 资质审核：approve 上架并标记已核验；reject 置为 -1 保留记录可追溯。
     *
     * <p>驳回必须给原因：没有原因的驳回，申请人无法补材料重报，运营之间也无法交接。
     * 原因当前只进审核留痕（`t_audit_log`）——专家独立登录端未实现，表内没有任何读取方，
     * 且 `TechViews.ofExpert` 同时服务公开列表，落表列会把内部审核意见直接泄给游客。
     */
    @Transactional
    public TechExpert reviewExpert(Long expertId, String decision, String note) {
        TechExpert expert = expertMapper.selectById(expertId);
        if (expert == null) {
            throw new BusinessException(404, "专家记录不存在");
        }
        String trimmedNote = note == null ? null : note.trim();
        if ("approve".equalsIgnoreCase(decision)) {
            expert.setStatus(1);
            expert.setVerified(1);
            expert.setInviteStatus("ACTIVE");
            if (expert.getSort() == null || expert.getSort() == 0) expert.setSort(50);
        } else if ("reject".equalsIgnoreCase(decision)) {
            if (trimmedNote == null || trimmedNote.isEmpty()) {
                throw new BusinessException(400, "驳回必须填写原因，便于申请人补正材料");
            }
            expert.setStatus(-1);
            expert.setVerified(0);
            expert.setInviteStatus("REJECTED");
        } else {
            throw new BusinessException(400, "审核结论只能是 approve 或 reject");
        }
        expertMapper.updateById(expert);
        return expert;
    }

    /** 审核原因的落审计口径：去换行防注入审计串、按 255 截断与列宽对齐 */
    public static String reviewReasonForAudit(String decision, String note) {
        if (note == null || note.isBlank()) {
            return "未填写原因";
        }
        String oneLine = note.trim().replaceAll("\\s+", " ");
        return oneLine.length() <= 255 ? oneLine : oneLine.substring(0, 255);
    }

    private void validateCompliance(TechExpertApplyRequest req) {
        complianceGuard.validate("个人简介", req.getBio());
        complianceGuard.validate("资质与成果", req.getCredentials());
    }

    private static String normalizeType(String raw) {
        String type = raw == null || raw.isBlank() ? "EXPERT" : raw.trim().toUpperCase();
        if (!EXPERT_TYPES.contains(type)) {
            throw new BusinessException(400, "专家类型只能是 EXPERT 或 BROKER");
        }
        return type;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

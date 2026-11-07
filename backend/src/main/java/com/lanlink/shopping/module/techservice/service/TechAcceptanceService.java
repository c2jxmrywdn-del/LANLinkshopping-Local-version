package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.BusinessException;
import com.lanlink.shopping.module.techservice.domain.TechEvidenceHasher;
import com.lanlink.shopping.module.techservice.domain.TechNoGenerator;
import com.lanlink.shopping.module.techservice.dto.TechAcceptanceRequest;
import com.lanlink.shopping.module.techservice.entity.TechAcceptance;
import com.lanlink.shopping.module.techservice.mapper.TechAcceptanceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目验收：验收前须存在已签署合同；PASS 后项目归档为 COMPLETED。
 * 验收评分仅用于服务改进与履约统计，不对外作为评级依据。
 */
@Service
public class TechAcceptanceService {

    private static final Set<String> RESULTS = Set.of("PASS", "REJECT");

    private final TechAcceptanceMapper acceptanceMapper;
    private final TechContractService contractService;
    private final TechProjectService projectService;

    public TechAcceptanceService(TechAcceptanceMapper acceptanceMapper,
                                 TechContractService contractService,
                                 TechProjectService projectService) {
        this.acceptanceMapper = acceptanceMapper;
        this.contractService = contractService;
        this.projectService = projectService;
    }

    @Transactional
    public Map<String, Object> accept(Long userId, Long projectId, TechAcceptanceRequest req) {
        String result = req.result() == null ? "" : req.result().trim().toUpperCase();
        if (!RESULTS.contains(result)) {
            throw new BusinessException(400, "验收结论只能是 PASS 或 REJECT");
        }
        if (req.score() != null && (req.score() < 1 || req.score() > 5)) {
            throw new BusinessException(400, "满意度评分需在 1-5 之间");
        }
        // 归属校验（不存在/非本人会被拒绝）
        projectService.projectDetail(projectId, userId, false);
        if (contractService.countSignedByProject(projectId) == 0) {
            throw new BusinessException(400, "请先完成合同签署后再提交验收");
        }

        TechAcceptance acceptance = new TechAcceptance();
        acceptance.setAcceptanceNo(TechNoGenerator.next("TA-AC"));
        acceptance.setProjectId(projectId);
        acceptance.setResult(result);
        acceptance.setScore(req.score());
        acceptance.setNote(req.note());
        acceptance.setAcceptedBy(userId);
        acceptance.setCreateTime(LocalDateTime.now());
        acceptance.setEvidenceHash(TechEvidenceHasher.sha256(projectId + "|" + result + "|"
                + req.score() + "|" + req.note() + "|" + acceptance.getCreateTime()));
        acceptanceMapper.insert(acceptance);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acceptance", acceptance);
        if ("PASS".equals(result)) {
            out.put("project", projectService.completeProject(projectId, userId, false).get("project"));
        }
        return out;
    }

    public List<TechAcceptance> listByProject(Long userId, Long projectId) {
        projectService.projectDetail(projectId, userId, false);
        return acceptanceMapper.selectList(Wrappers.<TechAcceptance>lambdaQuery()
                .eq(TechAcceptance::getProjectId, projectId)
                .orderByDesc(TechAcceptance::getCreateTime));
    }

    public Page<TechAcceptance> adminPage(long page, long size) {
        return acceptanceMapper.selectPage(TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE),
                Wrappers.<TechAcceptance>lambdaQuery().orderByDesc(TechAcceptance::getCreateTime));
    }
}

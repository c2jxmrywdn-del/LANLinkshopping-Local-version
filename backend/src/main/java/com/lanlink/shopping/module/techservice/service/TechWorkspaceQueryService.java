package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechConsultation;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechConsultationMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import com.lanlink.shopping.module.techservice.mapper.TechHostingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易工作台读模型：只做聚合统计与最近记录，不承担写职责、不开启事务。
 */
@Service
public class TechWorkspaceQueryService {

    private final TechProjectMapper projectMapper;
    private final TechDealMapper dealMapper;
    private final TechAppraisalMapper appraisalMapper;
    private final TechHostingMapper hostingMapper;
    private final TechConsultationMapper consultationMapper;

    public TechWorkspaceQueryService(TechProjectMapper projectMapper,
                                     TechDealMapper dealMapper,
                                     TechAppraisalMapper appraisalMapper,
                                     TechHostingMapper hostingMapper,
                                     TechConsultationMapper consultationMapper) {
        this.projectMapper = projectMapper;
        this.dealMapper = dealMapper;
        this.appraisalMapper = appraisalMapper;
        this.hostingMapper = hostingMapper;
        this.consultationMapper = consultationMapper;
    }

    public Map<String, Object> workspaceSummary(Long userId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("projects", count(projectMapper.selectCount(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getUserId, userId))));
        counts.put("active", count(projectMapper.selectCount(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getUserId, userId).eq(TechProject::getStatus, "ACTIVE"))));
        counts.put("completed", count(projectMapper.selectCount(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getUserId, userId).eq(TechProject::getStatus, "COMPLETED"))));
        counts.put("deals", count(dealMapper.selectCount(Wrappers.<TechDeal>lambdaQuery()
                .and(w -> w.eq(TechDeal::getBuyerUserId, userId).or().eq(TechDeal::getSellerUserId, userId)))));
        counts.put("appraisals", count(appraisalMapper.selectCount(Wrappers.<TechAppraisal>lambdaQuery()
                .eq(TechAppraisal::getUserId, userId))));
        counts.put("hostings", count(hostingMapper.selectCount(Wrappers.<TechHosting>lambdaQuery()
                .eq(TechHosting::getUserId, userId))));
        counts.put("consultations", count(consultationMapper.selectCount(Wrappers.<TechConsultation>lambdaQuery()
                .eq(TechConsultation::getUserId, userId))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("recentProjects", projectMapper.selectList(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getUserId, userId)
                .orderByDesc(TechProject::getCreateTime).last("LIMIT 4")));
        out.put("recentDeals", dealMapper.selectList(Wrappers.<TechDeal>lambdaQuery()
                .and(w -> w.eq(TechDeal::getBuyerUserId, userId).or().eq(TechDeal::getSellerUserId, userId))
                .orderByDesc(TechDeal::getCreateTime).last("LIMIT 4")));
        return out;
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}

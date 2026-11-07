package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.techservice.entity.TechAppraisal;
import com.lanlink.shopping.module.techservice.entity.TechDemand;
import com.lanlink.shopping.module.techservice.entity.TechExpert;
import com.lanlink.shopping.module.techservice.entity.TechHosting;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechAppraisalMapper;
import com.lanlink.shopping.module.techservice.mapper.TechDemandMapper;
import com.lanlink.shopping.module.techservice.mapper.TechExpertMapper;
import com.lanlink.shopping.module.techservice.mapper.TechHostingMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技术服务运营后台读模型：待办统计与分页查询。
 * 写操作由各聚合命令服务承担，后台读模型不夹带任何写逻辑。
 */
@Service
public class TechAdminQueryService {

    private final TechExpertMapper expertMapper;
    private final TechDemandMapper demandMapper;
    private final TechProjectMapper projectMapper;
    private final TechAppraisalMapper appraisalMapper;
    private final TechHostingMapper hostingMapper;

    public TechAdminQueryService(TechExpertMapper expertMapper,
                                 TechDemandMapper demandMapper,
                                 TechProjectMapper projectMapper,
                                 TechAppraisalMapper appraisalMapper,
                                 TechHostingMapper hostingMapper) {
        this.expertMapper = expertMapper;
        this.demandMapper = demandMapper;
        this.projectMapper = projectMapper;
        this.appraisalMapper = appraisalMapper;
        this.hostingMapper = hostingMapper;
    }

    public Map<String, Object> overview() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("expertPending", count(expertMapper.selectCount(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 0))));
        counts.put("expertOnline", count(expertMapper.selectCount(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 1))));
        counts.put("demandOpen", count(demandMapper.selectCount(Wrappers.<TechDemand>lambdaQuery()
                .in(TechDemand::getStatus, "MATCHING", "IN_PROGRESS"))));
        counts.put("projectActive", count(projectMapper.selectCount(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getStatus, "ACTIVE"))));
        counts.put("appraisalPending", count(appraisalMapper.selectCount(Wrappers.<TechAppraisal>lambdaQuery()
                .in(TechAppraisal::getStatus, "SUBMITTED", "MATERIAL_REVIEW", "EXPERT_REVIEW"))));
        counts.put("hostingPending", count(hostingMapper.selectCount(Wrappers.<TechHosting>lambdaQuery()
                .eq(TechHosting::getStatus, "APPLIED"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("pendingExperts", expertMapper.selectList(Wrappers.<TechExpert>lambdaQuery()
                .eq(TechExpert::getStatus, 0).orderByDesc(TechExpert::getCreateTime).last("LIMIT 6")));
        out.put("pendingDemands", demandMapper.selectList(Wrappers.<TechDemand>lambdaQuery()
                .in(TechDemand::getStatus, "MATCHING", "IN_PROGRESS")
                .orderByDesc(TechDemand::getCreateTime).last("LIMIT 6")));
        out.put("activeProjects", projectMapper.selectList(Wrappers.<TechProject>lambdaQuery()
                .eq(TechProject::getStatus, "ACTIVE")
                .orderByDesc(TechProject::getUpdateTime).last("LIMIT 6")));
        return out;
    }

    public Page<TechExpert> expertPage(long page, long size, Integer status, String keyword) {
        String kw = text(keyword);
        LambdaQueryWrapper<TechExpert> q = Wrappers.<TechExpert>lambdaQuery()
                .eq(status != null, TechExpert::getStatus, status)
                .and(kw != null, w -> w.like(TechExpert::getName, kw)
                        .or().like(TechExpert::getOrganization, kw)
                        .or().like(TechExpert::getDomains, kw))
                .orderByDesc(TechExpert::getCreateTime);
        return expertMapper.selectPage(paged(page, size), q);
    }

    public Page<TechDemand> demandPage(long page, long size, String status, String keyword) {
        String kw = text(keyword);
        return demandMapper.selectPage(paged(page, size), Wrappers.<TechDemand>lambdaQuery()
                .eq(text(status) != null, TechDemand::getStatus, text(status))
                .and(kw != null, w -> w.like(TechDemand::getTitle, kw).or().like(TechDemand::getIndustry, kw))
                .orderByDesc(TechDemand::getCreateTime));
    }

    public Page<TechProject> projectPage(long page, long size, String status, String keyword) {
        String kw = text(keyword);
        return projectMapper.selectPage(paged(page, size), Wrappers.<TechProject>lambdaQuery()
                .eq(text(status) != null, TechProject::getStatus, text(status))
                .and(kw != null, w -> w.like(TechProject::getTitle, kw).or().like(TechProject::getProjectNo, kw))
                .orderByDesc(TechProject::getUpdateTime));
    }

    public Page<TechAppraisal> appraisalPage(long page, long size, String status) {
        return appraisalMapper.selectPage(paged(page, size), Wrappers.<TechAppraisal>lambdaQuery()
                .eq(text(status) != null, TechAppraisal::getStatus, text(status))
                .orderByDesc(TechAppraisal::getCreateTime));
    }

    public Page<TechHosting> hostingPage(long page, long size, String status) {
        return hostingMapper.selectPage(paged(page, size), Wrappers.<TechHosting>lambdaQuery()
                .eq(text(status) != null, TechHosting::getStatus, text(status))
                .orderByDesc(TechHosting::getCreateTime));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }

    /** 后台列表同样受分页上限约束（运营批量视图放宽到 200），避免 size 参数被当成全表导出 */
    private static <T> Page<T> paged(long page, long size) {
        return TechPageable.of(page, size, TechPageable.ADMIN_MAX_SIZE);
    }
}

package com.lanlink.shopping.module.techservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.techservice.entity.TechDeal;
import com.lanlink.shopping.module.techservice.entity.TechProject;
import com.lanlink.shopping.module.techservice.mapper.TechDealMapper;
import com.lanlink.shopping.module.techservice.mapper.TechProjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 首页聚合读模型：跨域统计 + 各领域精选列表。
 *
 * <p>该服务是唯一允许跨多个聚合读 Mapper 的查询组件（首页天然跨域）；
 * 其余 QueryService 保持单一领域依赖。所有业务列表由各领域 QueryService 提供。
 */
@Service
public class TechOverviewQueryService {

    private final TechExpertQueryService expertQuery;
    private final TechAchievementQueryService achievementQuery;
    private final TechDemandQueryService demandQuery;
    private final TechDirectoryQueryService directoryQuery;
    private final TechPolicyQueryService policyQuery;
    private final TechProjectMapper projectMapper;
    private final TechDealMapper dealMapper;

    public TechOverviewQueryService(TechExpertQueryService expertQuery,
                                    TechAchievementQueryService achievementQuery,
                                    TechDemandQueryService demandQuery,
                                    TechDirectoryQueryService directoryQuery,
                                    TechPolicyQueryService policyQuery,
                                    TechProjectMapper projectMapper,
                                    TechDealMapper dealMapper) {
        this.expertQuery = expertQuery;
        this.achievementQuery = achievementQuery;
        this.demandQuery = demandQuery;
        this.directoryQuery = directoryQuery;
        this.policyQuery = policyQuery;
        this.projectMapper = projectMapper;
        this.dealMapper = dealMapper;
    }

    public Map<String, Object> overview() {
        long experts = expertQuery.countByType("EXPERT");
        long brokers = expertQuery.countByType("BROKER");
        long institutions = directoryQuery.countOrganizations();
        long demands = demandQuery.countOpen();
        long achievements = achievementQuery.countVisible();
        long dormant = achievementQuery.countDormant();
        long projects = count(projectMapper.selectCount(null));
        List<TechDeal> dealRows = dealMapper.selectList(Wrappers.<TechDeal>lambdaQuery()
                .in(TechDeal::getStatus, "SIGNED", "ESCROWED", "DELIVERING", "COMPLETED"));
        BigDecimal dealAmount = dealRows.stream().map(TechDeal::getAmount)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long served = projectMapper.selectList(null).stream().map(TechProject::getUserId)
                .filter(Objects::nonNull).distinct().count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("experts", experts);
        stats.put("brokers", brokers);
        stats.put("institutions", institutions);
        stats.put("demands", demands);
        stats.put("achievements", achievements);
        stats.put("dormantPatents", dormant);
        stats.put("projects", projects);
        stats.put("deals", (long) dealRows.size());
        stats.put("dealAmount", dealAmount);
        stats.put("servedEnterprises", served);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stats", stats);
        out.put("experts", expertQuery.topExperts(4));
        out.put("demands", demandQuery.topDemands(4));
        out.put("achievements", achievementQuery.topAchievements(4));
        out.put("services", directoryQuery.allServices());
        out.put("policies", policyQuery.topPolicies(4));
        out.put("organizations", directoryQuery.topOrganizations(8));
        return out;
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}

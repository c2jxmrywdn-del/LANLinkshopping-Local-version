package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.techservice.api.TechViews;
import com.lanlink.shopping.module.techservice.service.TechAchievementQueryService;
import com.lanlink.shopping.module.techservice.service.TechCommunityQueryService;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechDemandQueryService;
import com.lanlink.shopping.module.techservice.service.TechDirectoryQueryService;
import com.lanlink.shopping.module.techservice.service.TechExpertQueryService;
import com.lanlink.shopping.module.techservice.service.TechOverviewQueryService;
import com.lanlink.shopping.module.techservice.service.TechPolicyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 技术服务公开目录（无需登录）。只返回 {@link TechViews} 契约，实体不出模块。
 * 每个端点只委托一个领域 QueryService，首页聚合由 OverviewQueryService 编排。
 */
@RestController
@RequestMapping("/tech/public")
public class TechPublicController {

    private final TechOverviewQueryService overviewQuery;
    private final TechExpertQueryService expertQuery;
    private final TechAchievementQueryService achievementQuery;
    private final TechDemandQueryService demandQuery;
    private final TechDirectoryQueryService directoryQuery;
    private final TechPolicyQueryService policyQuery;
    private final TechCommunityQueryService communityQuery;
    private final TechComplianceGuard complianceGuard;

    public TechPublicController(TechOverviewQueryService overviewQuery,
                                TechExpertQueryService expertQuery,
                                TechAchievementQueryService achievementQuery,
                                TechDemandQueryService demandQuery,
                                TechDirectoryQueryService directoryQuery,
                                TechPolicyQueryService policyQuery,
                                TechCommunityQueryService communityQuery,
                                TechComplianceGuard complianceGuard) {
        this.overviewQuery = overviewQuery;
        this.expertQuery = expertQuery;
        this.achievementQuery = achievementQuery;
        this.demandQuery = demandQuery;
        this.directoryQuery = directoryQuery;
        this.policyQuery = policyQuery;
        this.communityQuery = communityQuery;
        this.complianceGuard = complianceGuard;
    }

    /** 业务边界清单（公开可读，供前台展示与管理端话术对齐） */
    @GetMapping("/compliance")
    public R<Map<String, Object>> compliance() {
        return R.ok(complianceGuard.boundary());
    }

    /** 首页聚合：精选实体在 api 边界统一转换为 DTO */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(TechViews.overview(overviewQuery.overview()));
    }

    @GetMapping("/experts")
    public R<Map<String, Object>> experts(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String industry,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.expertPage(expertQuery.expertPage(page, size, type, industry, keyword)));
    }

    @GetMapping("/experts/{id}")
    public R<Map<String, Object>> expert(@PathVariable Long id) {
        return R.ok(TechViews.expertDetail(expertQuery.expertDetail(id)));
    }

    @GetMapping("/organizations")
    public R<List<TechViews.Organization>> organizations(@RequestParam(required = false) String type) {
        return R.ok(directoryQuery.organizationList(type).stream().map(TechViews::ofOrganization).toList());
    }

    @GetMapping("/services")
    public R<List<TechViews.ServiceProduct>> services(@RequestParam(required = false) String category) {
        return R.ok(directoryQuery.serviceList(category).stream().map(TechViews::ofServiceProduct).toList());
    }

    @GetMapping("/demands")
    public R<Map<String, Object>> demands(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) String industry,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status) {
        return R.ok(TechViews.demandPage(demandQuery.demandPage(page, size, industry, keyword, status)));
    }

    @GetMapping("/demands/{id}")
    public R<TechViews.Demand> demand(@PathVariable Long id) {
        return R.ok(TechViews.ofDemand(demandQuery.demandDetail(id)));
    }

    @GetMapping("/achievements")
    public R<Map<String, Object>> achievements(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "12") long size,
                                               @RequestParam(required = false) String industry,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false) Integer dormant,
                                               @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.achievementPage(
                achievementQuery.achievementPage(page, size, industry, type, dormant, keyword)));
    }

    @GetMapping("/achievements/{id}")
    public R<Map<String, Object>> achievement(@PathVariable Long id) {
        return R.ok(TechViews.achievementDetail(achievementQuery.achievementDetail(id)));
    }

    @GetMapping("/achievements/{id}/assessment")
    public R<Map<String, Object>> assessment(@PathVariable Long id) {
        return R.ok(achievementQuery.quickAssessment(id));
    }

    @GetMapping("/policies")
    public R<List<TechViews.Policy>> policies(@RequestParam(required = false) String region,
                                              @RequestParam(required = false) String category) {
        return R.ok(policyQuery.policyList(region, category).stream().map(TechViews::ofPolicy).toList());
    }

    @GetMapping("/posts")
    public R<Map<String, Object>> posts(@RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "12") long size,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) String industry,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.postPage(communityQuery.postPage(page, size, type, industry, keyword)));
    }

    @GetMapping("/posts/{id}")
    public R<TechViews.Post> post(@PathVariable Long id) {
        return R.ok(TechViews.ofPost(communityQuery.postDetail(id)));
    }
}

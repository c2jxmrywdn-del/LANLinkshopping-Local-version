package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.module.techservice.api.TechViews;
import com.lanlink.shopping.module.techservice.dto.TechAppraisalCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechConsultationCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechDealCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechDemandCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechExpertApplyRequest;
import com.lanlink.shopping.module.techservice.dto.TechHostingCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechPostCreateRequest;
import com.lanlink.shopping.module.techservice.service.TechAchievementQueryService;
import com.lanlink.shopping.module.techservice.service.TechAppraisalService;
import com.lanlink.shopping.module.techservice.service.TechCommunityService;
import com.lanlink.shopping.module.techservice.service.TechConsultationService;
import com.lanlink.shopping.module.techservice.service.TechDealService;
import com.lanlink.shopping.module.techservice.service.TechDemandService;
import com.lanlink.shopping.module.techservice.service.TechExpertService;
import com.lanlink.shopping.module.techservice.service.TechHostingService;
import com.lanlink.shopping.module.techservice.service.TechProjectService;
import com.lanlink.shopping.module.techservice.service.TechWorkspaceQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 技术服务前台接口（登录态 + CSRF）。
 *
 * <p>Controller 只做参数绑定、用户上下文、实体→DTO 转换与响应包装；
 * 业务编排全部在应用服务中，实体不离开模块。
 */
@RestController
@RequestMapping("/tech")
public class TechServiceController {

    private final TechDemandService demandService;
    private final TechConsultationService consultationService;
    private final TechAppraisalService appraisalService;
    private final TechHostingService hostingService;
    private final TechDealService dealService;
    private final TechProjectService projectService;
    private final TechWorkspaceQueryService workspaceQueryService;
    private final TechAchievementQueryService achievementQueryService;
    private final TechCommunityService communityService;
    private final TechExpertService expertService;

    public TechServiceController(TechDemandService demandService,
                                 TechConsultationService consultationService,
                                 TechAppraisalService appraisalService,
                                 TechHostingService hostingService,
                                 TechDealService dealService,
                                 TechProjectService projectService,
                                 TechWorkspaceQueryService workspaceQueryService,
                                 TechAchievementQueryService achievementQueryService,
                                 TechCommunityService communityService,
                                 TechExpertService expertService) {
        this.demandService = demandService;
        this.consultationService = consultationService;
        this.appraisalService = appraisalService;
        this.hostingService = hostingService;
        this.dealService = dealService;
        this.projectService = projectService;
        this.workspaceQueryService = workspaceQueryService;
        this.achievementQueryService = achievementQueryService;
        this.communityService = communityService;
        this.expertService = expertService;
    }

    @PostMapping("/demands")
    public R<TechViews.Demand> createDemand(@Valid @RequestBody TechDemandCreateRequest req,
                                            HttpServletRequest request) {
        return R.ok(TechViews.ofDemandFull(demandService.createDemand(UserContext.currentUserId(request), req)));
    }

    @GetMapping("/demands/my")
    public R<List<TechViews.Demand>> myDemands(HttpServletRequest request) {
        return R.ok(TechViews.ofDemands(demandService.myDemands(UserContext.currentUserId(request))));
    }

    @PostMapping("/consultations")
    public R<TechViews.Consultation> createConsultation(@Valid @RequestBody TechConsultationCreateRequest req,
                                                        HttpServletRequest request) {
        return R.ok(TechViews.ofConsultation(
                consultationService.createConsultation(UserContext.currentUserId(request), req)));
    }

    @GetMapping("/consultations/my")
    public R<List<TechViews.Consultation>> myConsultations(HttpServletRequest request) {
        return R.ok(TechViews.ofConsultations(consultationService.myConsultations(UserContext.currentUserId(request))));
    }

    @PostMapping("/appraisals")
    public R<TechViews.Appraisal> createAppraisal(@Valid @RequestBody TechAppraisalCreateRequest req,
                                                  HttpServletRequest request) {
        return R.ok(TechViews.ofAppraisal(
                appraisalService.createAppraisal(UserContext.currentUserId(request), req)));
    }

    @GetMapping("/appraisals/my")
    public R<List<TechViews.Appraisal>> myAppraisals(HttpServletRequest request) {
        return R.ok(TechViews.ofAppraisals(appraisalService.myAppraisals(UserContext.currentUserId(request))));
    }

    @PostMapping("/hostings")
    public R<TechViews.Hosting> createHosting(@Valid @RequestBody TechHostingCreateRequest req,
                                              HttpServletRequest request) {
        return R.ok(TechViews.ofHosting(hostingService.createHosting(UserContext.currentUserId(request), req)));
    }

    @GetMapping("/hostings/my")
    public R<List<TechViews.Hosting>> myHostings(HttpServletRequest request) {
        return R.ok(TechViews.ofHostings(hostingService.myHostings(UserContext.currentUserId(request))));
    }

    @PostMapping("/deals")
    public R<TechViews.Deal> createDeal(@Valid @RequestBody TechDealCreateRequest req,
                                        HttpServletRequest request) {
        return R.ok(TechViews.ofDeal(dealService.createDeal(UserContext.currentUserId(request), req)));
    }

    @GetMapping("/deals/my")
    public R<List<TechViews.Deal>> myDeals(HttpServletRequest request) {
        return R.ok(TechViews.ofDeals(dealService.myDeals(UserContext.currentUserId(request))));
    }

    @GetMapping("/achievements/my")
    public R<List<TechViews.Achievement>> myAchievements(HttpServletRequest request) {
        return R.ok(TechViews.ofAchievementsFull(
                achievementQueryService.myAchievements(UserContext.currentUserId(request))));
    }

    @GetMapping("/projects/my")
    public R<List<TechViews.Project>> myProjects(HttpServletRequest request) {
        return R.ok(TechViews.ofProjects(projectService.myProjects(UserContext.currentUserId(request))));
    }

    @GetMapping("/projects/{id}")
    public R<Map<String, Object>> project(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(TechViews.projectDetail(
                projectService.projectDetail(id, UserContext.currentUserId(request), false)));
    }

    @PostMapping("/projects/{id}/advance")
    public R<Map<String, Object>> advance(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(TechViews.projectDetail(
                projectService.advanceProject(id, UserContext.currentUserId(request), false)));
    }

    @GetMapping("/workspace")
    public R<Map<String, Object>> workspace(HttpServletRequest request) {
        return R.ok(TechViews.workspace(workspaceQueryService.workspaceSummary(UserContext.currentUserId(request))));
    }

    @PostMapping("/posts")
    public R<TechViews.Post> createPost(@Valid @RequestBody TechPostCreateRequest req,
                                        HttpServletRequest request) {
        User user = UserContext.current(request);
        String nickname = user == null ? null : user.getNickname();
        return R.ok(TechViews.ofPost(
                communityService.createPost(UserContext.currentUserId(request), nickname, req)));
    }

    @GetMapping("/posts/my")
    public R<List<TechViews.Post>> myPosts(HttpServletRequest request) {
        return R.ok(TechViews.ofPosts(communityService.myPosts(UserContext.currentUserId(request))));
    }

    @PostMapping("/posts/{id}/like")
    public R<TechViews.Post> likePost(@PathVariable Long id) {
        return R.ok(TechViews.ofPost(communityService.likePost(id)));
    }

    /**
     * 专家 / 技术经纪人入驻申请：进入待审核队列（status=0），
     * 由平台运营核验资质后上架；平台也可通过定向邀请直接引入权威专家。
     */
    @PostMapping("/experts/apply")
    public R<TechViews.Expert> applyExpert(@Valid @RequestBody TechExpertApplyRequest req,
                                           HttpServletRequest request) {
        return R.ok("入驻申请已提交，平台将在 3 个工作日内完成资质核验",
                TechViews.ofExpertFull(expertService.applyExpert(UserContext.currentUserId(request), req)));
    }
}

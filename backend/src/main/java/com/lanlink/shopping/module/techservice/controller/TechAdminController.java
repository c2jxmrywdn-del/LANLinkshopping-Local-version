package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.techservice.api.TechViews;
import com.lanlink.shopping.module.techservice.dto.TechExpertApplyRequest;
import com.lanlink.shopping.module.techservice.service.TechAdminQueryService;
import com.lanlink.shopping.module.techservice.service.TechAppraisalService;
import com.lanlink.shopping.module.techservice.service.TechComplianceGuard;
import com.lanlink.shopping.module.techservice.service.TechDemandService;
import com.lanlink.shopping.module.techservice.service.TechExpertService;
import com.lanlink.shopping.module.techservice.service.TechHostingService;
import com.lanlink.shopping.module.techservice.service.TechProjectService;
import com.lanlink.shopping.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 技术服务运营后台（仅平台运营）。
 * 登录 + @RequirePerm("admin:all") + CSRF 三重约束（见 WebConfig / CsrfInterceptor）。
 * 读走 TechAdminQueryService，写走各聚合命令服务，响应统一转换为 DTO。
 */
@RestController
@RequestMapping("/admin/tech-service")
public class TechAdminController {

    private final TechAdminQueryService queryService;
    private final TechExpertService expertService;
    private final TechDemandService demandService;
    private final TechAppraisalService appraisalService;
    private final TechHostingService hostingService;
    private final TechProjectService projectService;
    private final TechComplianceGuard complianceGuard;
    private final AuditService auditService;

    public TechAdminController(TechAdminQueryService queryService,
                               TechExpertService expertService,
                               TechDemandService demandService,
                               TechAppraisalService appraisalService,
                               TechHostingService hostingService,
                               TechProjectService projectService,
                               TechComplianceGuard complianceGuard,
                               AuditService auditService) {
        this.queryService = queryService;
        this.expertService = expertService;
        this.demandService = demandService;
        this.appraisalService = appraisalService;
        this.hostingService = hostingService;
        this.projectService = projectService;
        this.complianceGuard = complianceGuard;
        this.auditService = auditService;
    }

    /** 运营总览：待办队列与合规边界 */
    @GetMapping("/overview")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> overview() {
        Map<String, Object> out = TechViews.adminOverview(queryService.overview());
        out.put("compliance", complianceGuard.boundary());
        return R.ok(out);
    }

    @GetMapping("/experts")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> experts(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.page(queryService.expertPage(page, size, status, keyword), TechViews::ofExpertFull));
    }

    /** 定向邀请专家 / 技术经纪人入驻（邀请即时上架） */
    @PostMapping("/experts/invite")
    @RequirePerm("admin:all")
    public R<TechViews.Expert> invite(@RequestBody TechExpertApplyRequest body, HttpServletRequest request) {
        TechViews.Expert invited = TechViews.ofExpertFull(expertService.inviteExpert(body));
        auditService.record(UserContext.currentUserId(request), "TECH_EXPERT_INVITE",
                "定向邀请入驻: " + invited.expertId() + " " + invited.name(), request);
        return R.ok("已邀请入驻", invited);
    }

    /** 审核主动申请：decision=approve/reject；reject 必须带 note，原因写入审核留痕 */
    @PostMapping("/experts/{id}/review")
    @RequirePerm("admin:all")
    public R<TechViews.Expert> review(@PathVariable Long id, @RequestBody ReviewRequest body,
                                      HttpServletRequest request) {
        if (body == null || body.decision() == null) {
            return R.fail(400, "请选择审核结论");
        }
        TechViews.Expert reviewed = TechViews.ofExpertFull(
                expertService.reviewExpert(id, body.decision(), body.note()));
        auditService.record(UserContext.currentUserId(request), "TECH_EXPERT_REVIEW",
                "专家审核 " + id + "：" + body.decision() + "，结论状态 " + reviewed.inviteStatus()
                        + "，原因：" + TechExpertService.reviewReasonForAudit(body.decision(), body.note()), request);
        return R.ok("审核完成", reviewed);
    }

    @GetMapping("/demands")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> demands(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.page(queryService.demandPage(page, size, status, keyword), TechViews::ofDemandFull));
    }

    @PostMapping("/demands/{id}/status")
    @RequirePerm("admin:all")
    public R<TechViews.Demand> demandStatus(@PathVariable Long id, @RequestBody StatusRequest body,
                                            HttpServletRequest request) {
        if (body == null || body.status() == null) {
            return R.fail(400, "状态不能为空");
        }
        TechViews.Demand updated = TechViews.ofDemandFull(demandService.updateStatus(id, body.status()));
        auditService.record(UserContext.currentUserId(request), "TECH_DEMAND_STATUS",
                "需求状态 " + id + " → " + body.status(), request);
        return R.ok("需求状态已更新", updated);
    }

    @GetMapping("/projects")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> projects(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "12") long size,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(TechViews.page(queryService.projectPage(page, size, status, keyword), TechViews::ofProject));
    }

    @GetMapping("/projects/{id}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> project(@PathVariable Long id) {
        return R.ok(TechViews.projectDetail(projectService.projectDetail(id, null, true)));
    }

    /** 推进当前里程碑（陪跑交付节点，运营侧无需归属校验） */
    @PostMapping("/projects/{id}/advance")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> advance(@PathVariable Long id, HttpServletRequest request) {
        Map<String, Object> detail = TechViews.projectDetail(projectService.advanceProject(id, null, true));
        auditService.record(UserContext.currentUserId(request), "TECH_PROJECT_ADVANCE",
                "代运营推进项目 " + id, request);
        return R.ok("里程碑已推进", detail);
    }

    /** 指派陪跑团队（技术经纪人 + 责任专家） */
    @PostMapping("/projects/{id}/assign")
    @RequirePerm("admin:all")
    public R<TechViews.Project> assign(@PathVariable Long id, @RequestBody AssignRequest body,
                                       HttpServletRequest request) {
        TechViews.Project assigned =
                TechViews.ofProject(projectService.assignTeam(id, body.brokerId(), body.ownerExpertId()));
        auditService.record(UserContext.currentUserId(request), "TECH_PROJECT_ASSIGN",
                "项目 " + id + " 指派：经纪人 " + body.brokerId() + " / 责任专家 " + body.ownerExpertId(), request);
        return R.ok("陪跑团队已指派", assigned);
    }

    @GetMapping("/appraisals")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> appraisals(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "12") long size,
                                             @RequestParam(required = false) String status) {
        return R.ok(TechViews.page(queryService.appraisalPage(page, size, status), TechViews::ofAppraisal));
    }

    /** 更新评价受理进度（结论与报告编号由合作评价机构回填，平台仅跟踪） */
    @PostMapping("/appraisals/{id}/status")
    @RequirePerm("admin:all")
    public R<TechViews.Appraisal> appraisalStatus(@PathVariable Long id,
                                                  @RequestBody AppraisalStatusRequest body,
                                                  HttpServletRequest request) {
        if (body == null || body.status() == null) {
            return R.fail(400, "状态不能为空");
        }
        TechViews.Appraisal updated = TechViews.ofAppraisal(
                appraisalService.updateStatus(id, body.status(), body.conclusionLevel(), body.reportNo()));
        auditService.record(UserContext.currentUserId(request), "TECH_APPRAISAL_STATUS",
                "评价 " + id + " → " + body.status() + "，报告号 " + body.reportNo(), request);
        return R.ok("评价进度已更新", updated);
    }

    @GetMapping("/hostings")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> hostings(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "12") long size,
                                           @RequestParam(required = false) String status) {
        return R.ok(TechViews.page(queryService.hostingPage(page, size, status), TechViews::ofHosting));
    }

    @PostMapping("/hostings/{id}/status")
    @RequirePerm("admin:all")
    public R<TechViews.Hosting> hostingStatus(@PathVariable Long id, @RequestBody StatusRequest body,
                                              HttpServletRequest request) {
        if (body == null || body.status() == null) {
            return R.fail(400, "状态不能为空");
        }
        TechViews.Hosting updated = TechViews.ofHosting(hostingService.updateStatus(id, body.status()));
        auditService.record(UserContext.currentUserId(request), "TECH_HOSTING_STATUS",
                "托管 " + id + " → " + body.status(), request);
        return R.ok("托管状态已更新", updated);
    }

    /** 合规边界清单（审核话术参考） */
    @GetMapping("/compliance")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> compliance() {
        return R.ok(complianceGuard.boundary());
    }

    public record ReviewRequest(String decision, String note) {}
    public record StatusRequest(String status) {}
    public record AssignRequest(Long brokerId, Long ownerExpertId) {}
    public record AppraisalStatusRequest(String status, String conclusionLevel, String reportNo) {}
}

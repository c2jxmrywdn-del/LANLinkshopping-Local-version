package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.service.TechOrgTaskService;
import com.lanlink.shopping.module.techservice.service.TechPilotService;
import com.lanlink.shopping.module.techservice.service.TechReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * P1 运营后台：机构派单验收、履约评价台账、中试资源与预约管理。
 * 登录 + @RequirePerm("admin:all") + CSRF。
 */
@RestController
@RequestMapping("/admin/tech-service")
public class TechP1AdminController {

    private final TechOrgTaskService orgTaskService;
    private final TechReviewService reviewService;
    private final TechPilotService pilotService;

    public TechP1AdminController(TechOrgTaskService orgTaskService,
                                 TechReviewService reviewService,
                                 TechPilotService pilotService) {
        this.orgTaskService = orgTaskService;
        this.reviewService = reviewService;
        this.pilotService = pilotService;
    }

    @GetMapping("/org-tasks")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> orgTasks(@RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "12") long size,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long orgId) {
        return R.ok(TechP1Views.page(orgTaskService.adminPage(page, size, status, keyword, orgId),
                TechP1Views::ofOrgTask));
    }

    @PostMapping("/org-tasks")
    @RequirePerm("admin:all")
    public R<TechP1Views.OrgTask> createOrgTask(@RequestBody TechP1Requests.OrgTaskCreate body,
                                                HttpServletRequest request) {
        return R.ok("机构任务已派发", TechP1Views.ofOrgTask(
                orgTaskService.adminCreate(UserContext.currentUserId(request), body)));
    }

    @PostMapping("/org-tasks/{taskNo}/review")
    @RequirePerm("admin:all")
    public R<TechP1Views.OrgTask> reviewOrgTask(@PathVariable String taskNo,
                                                @RequestBody TechP1Requests.OrgTaskReview body) {
        return R.ok("机构任务已验收", TechP1Views.ofOrgTask(orgTaskService.adminReview(taskNo, body)));
    }

    @GetMapping("/reviews")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> reviews(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) String targetType) {
        return R.ok(TechP1Views.page(reviewService.adminPage(page, size, targetType),
                TechP1Views::ofReview));
    }

    @GetMapping("/pilot-resources")
    @RequirePerm("admin:all")
    public R<List<TechP1Views.PilotResource>> pilotResources() {
        return R.ok(pilotService.adminResources().stream().map(TechP1Views::ofPilotResource).toList());
    }

    @PostMapping("/pilot-resources")
    @RequirePerm("admin:all")
    public R<TechP1Views.PilotResource> upsertPilotResource(
            @RequestBody TechP1Requests.PilotResourceUpsert body) {
        return R.ok("资源已保存", TechP1Views.ofPilotResource(pilotService.upsertResource(body)));
    }

    @PostMapping("/pilot-resources/{id}/status")
    @RequirePerm("admin:all")
    public R<TechP1Views.PilotResource> togglePilotResource(@PathVariable Long id,
                                                            @RequestParam int status) {
        return R.ok("资源状态已更新", TechP1Views.ofPilotResource(pilotService.setResourceStatus(id, status)));
    }

    @GetMapping("/pilot-bookings")
    @RequirePerm("admin:all")
    public R<List<TechP1Views.PilotBooking>> pilotBookings(
            @RequestParam(required = false) String status) {
        return R.ok(pilotService.adminBookings(status).stream().map(TechP1Views::ofPilotBooking).toList());
    }

    @PostMapping("/pilot-bookings/{bookingNo}/status")
    @RequirePerm("admin:all")
    public R<TechP1Views.PilotBooking> updatePilotBooking(
            @PathVariable String bookingNo,
            @RequestBody TechP1Requests.PilotBookingStatus body) {
        return R.ok("预约状态已更新", TechP1Views.ofPilotBooking(
                pilotService.updateBookingStatus(bookingNo, body)));
    }
}

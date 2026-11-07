package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.service.TechOrgTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 机构工作台前台（登录 + CSRF）：查看派单、提交交付物。 */
@RestController
@RequestMapping("/tech/org-tasks")
public class TechOrgTaskController {

    private final TechOrgTaskService orgTaskService;

    public TechOrgTaskController(TechOrgTaskService orgTaskService) {
        this.orgTaskService = orgTaskService;
    }

    @GetMapping("/my")
    public R<List<TechP1Views.OrgTask>> myTasks(HttpServletRequest request) {
        return R.ok(orgTaskService.myTasks(UserContext.currentUserId(request)).stream()
                .map(TechP1Views::ofOrgTask).toList());
    }

    @GetMapping("/{taskNo}")
    public R<TechP1Views.OrgTask> detail(@PathVariable String taskNo, HttpServletRequest request) {
        return R.ok(TechP1Views.ofOrgTask(orgTaskService.detail(UserContext.currentUserId(request), taskNo)));
    }

    @PostMapping("/{taskNo}/submit")
    public R<TechP1Views.OrgTask> submit(@PathVariable String taskNo,
                                         @RequestBody TechP1Requests.OrgTaskSubmit body,
                                         HttpServletRequest request) {
        return R.ok("交付物已提交，等待平台验收", TechP1Views.ofOrgTask(
                orgTaskService.submit(UserContext.currentUserId(request), taskNo, body)));
    }
}

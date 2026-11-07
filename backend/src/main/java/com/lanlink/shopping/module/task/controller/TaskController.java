package com.lanlink.shopping.module.task.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.task.dto.TaskClaimRequest;
import com.lanlink.shopping.module.task.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务中心 API（F3 最小闭环，activity-plan.md §P1.4；登录态由 AuthInterceptor 统一拦截）。
 *  - GET  /task/my     任务列表 + 总进度 + 今日进度 + 完成态（登录）
 *  - POST /task/claim  分享任务回执领奖 {taskKey, refNo}（登录；每日上限服务端计数 + 唯一键兜底）
 * 首单任务由 TaskOrderListener 监听 OrderPaidEvent 自动发放，无手动入口。
 * 分层同 LegacyModuleArchitectureTest 纪律：Controller 仅依赖 Service 与 DTO，不触碰 entity/mapper。
 */
@RestController
@RequestMapping("/task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 任务列表 + 今日进度（目录定义含奖励档位，前端直接渲染） */
    @GetMapping("/my")
    public R<List<Map<String, Object>>> my(HttpServletRequest request) {
        return R.ok(taskService.myTasks(UserContext.currentUserId(request)));
    }

    /** 分享任务回执领奖：{taskKey, refNo}；awarded=false 表示今日该回执已领过（幂等，不报错） */
    @PostMapping("/claim")
    public R<Map<String, Object>> claim(@RequestBody TaskClaimRequest body, HttpServletRequest request) {
        Map<String, Object> out = taskService.claim(UserContext.currentUserId(request),
                body == null ? null : body.getTaskKey(),
                body == null ? null : body.getRefNo());
        return R.ok(Boolean.TRUE.equals(out.get("awarded")) ? "领取成功，奖励积分已到账" : "今日该回执已领取过奖励", out);
    }
}

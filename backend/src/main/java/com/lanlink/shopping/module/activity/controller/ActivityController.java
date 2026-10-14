package com.lanlink.shopping.module.activity.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.activity.entity.ActivityParticipant;
import com.lanlink.shopping.module.activity.service.ActivityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 活动系统 API（营销中台）
 *  - GET  /activity/list         进行中活动（含是否已参与）
 *  - POST /activity/{id}/join    参与活动（幂等）
 *  - GET  /activity/my           我的参与记录
 */
@RestController
@RequestMapping("/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(HttpServletRequest request) {
        return R.ok(activityService.ongoing(UserContext.currentUserId(request)));
    }

    @PostMapping("/{id}/join")
    public R<Void> join(@PathVariable Long id, HttpServletRequest request) {
        activityService.join(UserContext.currentUserId(request), id);
        return R.ok("参与成功，奖励 100 积分", null);
    }

    @GetMapping("/my")
    public R<List<ActivityParticipant>> my(HttpServletRequest request) {
        return R.ok(activityService.my(UserContext.currentUserId(request)));
    }
}

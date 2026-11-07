package com.lanlink.shopping.module.membership.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.membership.service.MembershipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 每日签到 API（营销中台·会员系统；权限口径与 /membership 端点一致：登录即可）
 *  - POST /membership/checkin         今日签到（幂等：已签返回当日已有记录）
 *  - GET  /membership/checkin/status  签到状态（连签天数/今日积分/本月日历）
 */
@RestController
@RequestMapping("/membership")
public class CheckinController {

    private final MembershipService membershipService;

    public CheckinController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    /** 今日签到（幂等）：未签落库并发积分，已签返回当日已有记录 */
    @PostMapping("/checkin")
    public R<Map<String, Object>> checkin(HttpServletRequest request) {
        return R.ok(membershipService.checkin(UserContext.currentUserId(request)));
    }

    /** 签到状态：{signedToday, streak, points(今日已签积分), month:[{date, points}]} */
    @GetMapping("/checkin/status")
    public R<Map<String, Object>> status(HttpServletRequest request) {
        return R.ok(membershipService.checkinStatus(UserContext.currentUserId(request)));
    }
}

package com.lanlink.shopping.module.membership.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订阅系统 API（营销中台）
 *  - GET  /subscription/plans    订阅方案列表（公开，无需登录）
 *  - POST /subscription/purchase 购买/续费订阅（需登录，body {planId}）
 *  - GET  /subscription/my       我的当前订阅（需登录）
 *  - POST /subscription/cancel   取消当前订阅（需登录）
 */
@RestController
@RequestMapping("/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** 购买请求体：{ "planId": 1 } */
    @Data
    public static class PurchaseForm {
        private Long planId;
    }

    @GetMapping("/plans")
    public R<List<SubscriptionPlan>> plans() {
        return R.ok(subscriptionService.listPlans());
    }

    @PostMapping("/purchase")
    public R<UserSubscription> purchase(@RequestBody PurchaseForm form, HttpServletRequest request) {
        Long planId = form == null ? null : form.getPlanId();
        return R.ok("订阅成功", subscriptionService.purchase(UserContext.currentUserId(request), planId, request));
    }

    @GetMapping("/my")
    public R<UserSubscription> my(HttpServletRequest request) {
        return R.ok(subscriptionService.mySubscription(UserContext.currentUserId(request)));
    }

    @PostMapping("/cancel")
    public R<Void> cancel(HttpServletRequest request) {
        UserSubscription sub = subscriptionService.cancel(UserContext.currentUserId(request), request);
        return sub == null ? R.fail("当前没有生效中的订阅") : R.ok("已取消订阅", null);
    }
}

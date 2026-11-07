package com.lanlink.shopping.module.membership.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.membership.entity.SubscriptionPlan;
import com.lanlink.shopping.module.membership.entity.UserSubscription;
import com.lanlink.shopping.module.membership.service.SubscriptionService;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订阅系统 API（营销中台）
 *  - GET  /subscription/plans     订阅方案列表（公开，无需登录）
 *  - POST /subscription/purchase  购买/续费订阅（需登录，body {planId, channel?}）——
 *                                 P1-2 两段式：创建待支付订阅单 → PaymentService 渠道分发 → 返回支付参数
 *  - GET  /subscription/my        我的当前订阅（需登录）
 *  - POST /subscription/cancel    撤销自动续费（需登录）——D3：到期失效，权益保留至 end_time
 *  - GET  /subscription/orders    我的订阅订单分页（需登录，购买/续费流水）
 * 支付状态轮询走 GET /payment/query/{SUB单号}（PaymentService 单据抽象按前缀分流）。
 */
@RestController
@RequestMapping("/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** 购买请求体：{ "planId": 1, "channel": "mock" }（channel 缺省由 PaymentService 渠道决策） */
    @Data
    public static class PurchaseForm {
        private Long planId;
        private String channel;
    }

    @GetMapping("/plans")
    public R<List<SubscriptionPlan>> plans() {
        return R.ok(subscriptionService.listPlans());
    }

    /** 购买订阅：返回 {orderNo(SUB...), channel, payInfo, mock}，前端按渠道拉起支付并轮询 /payment/query */
    @PostMapping("/purchase")
    public R<PayCreateVO> purchase(@RequestBody PurchaseForm form, HttpServletRequest request) {
        Long planId = form == null ? null : form.getPlanId();
        String channel = form == null ? null : form.getChannel();
        return R.ok("订阅单已创建", subscriptionService.purchase(
                UserContext.currentUserId(request), planId, channel, request));
    }

    @GetMapping("/my")
    public R<UserSubscription> my(HttpServletRequest request) {
        return R.ok(subscriptionService.mySubscription(UserContext.currentUserId(request)));
    }

    /** 撤销自动续费（D3）：订阅权益保留至 end_time，到期失效；历史保留可续费恢复 */
    @PostMapping("/cancel")
    public R<Void> cancel(HttpServletRequest request) {
        UserSubscription sub = subscriptionService.cancel(UserContext.currentUserId(request), request);
        return sub == null ? R.fail("当前没有生效中的订阅") : R.ok("已撤销自动续费，到期后失效", null);
    }

    /** 我的订阅订单（P1-5）：{records, total, page, size}，含支付状态/渠道/金额（整数元语义） */
    @GetMapping("/orders")
    public R<Map<String, Object>> orders(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "10") long size,
                                         HttpServletRequest request) {
        return R.ok(subscriptionService.orders(UserContext.currentUserId(request), page, size));
    }
}

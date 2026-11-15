package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.refund.RefundReviewRequest;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.entity.OrderRma;
import com.lanlink.shopping.entity.RefundApproval;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.OrderRmaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台退款复核（P0-C 退款出款收口）：退款出款的唯一显式运营入口。
 *
 * <p>安全边界：
 * <ul>
 *   <li>类级 {@code @RequirePerm("admin:all")}：仅平台运营可访问（商户/买家即便登录也 403）；</li>
 *   <li>{@code /admin/**} 由 CsrfInterceptor 覆盖：POST 必须带 CSRF 令牌，防跨站伪造退款；</li>
 *   <li>商户初审（merchant_approved/shipping_back/...）+ 平台复核（PLATFORM/APPROVE 审批行）
 *       双人留痕齐全才允许出款，见 OrderRmaService；本控制器不直接调用渠道。</li>
 * </ul>
 *
 * <p>接口：
 * <pre>
 * POST /admin/refund/review/order/{orderNo}/approve   链A 普通订单：平台复核通过并出款（幂等：已退满则 400）
 * POST /admin/refund/review/order/{orderNo}/reject    链A 普通订单：平台驳回（只改状态+留痕）
 * POST /admin/refund/review/rma/{rmaNo}/approve       RMA 售后单：平台复核通过并出款（幂等：已结案不重复出款）
 * POST /admin/refund/review/rma/{rmaNo}/reject        RMA 售后单：平台驳回 → platform_rejected
 * POST /admin/refund/review/rma/{rmaNo}/reopen        申诉重开：仅当存在引用该 RMA 的在途客服工单
 * GET  /admin/refund/review/history/{orderNo}         双人留痕审批历史（MERCHANT/PLATFORM 逐行）
 * </pre>
 */
@RestController
@RequestMapping("/admin/refund/review")
@RequirePerm("admin:all")
public class AdminRefundReviewController {

    private final OrderRmaService rmaService;

    public AdminRefundReviewController(OrderRmaService rmaService) {
        this.rmaService = rmaService;
    }

    @PostMapping("/order/{orderNo}/approve")
    public R<Order> approveOrder(@PathVariable String orderNo,
                                 @RequestBody(required = false) RefundReviewRequest req,
                                 HttpServletRequest request) {
        Long operatorId = UserContext.currentUserId(request);
        return R.ok("平台复核通过，已触发原路退款",
                rmaService.platformApproveOrder(operatorId, orderNo, req));
    }

    @PostMapping("/order/{orderNo}/reject")
    public R<Order> rejectOrder(@PathVariable String orderNo,
                                @RequestBody(required = false) RefundReviewRequest req,
                                HttpServletRequest request) {
        Long operatorId = UserContext.currentUserId(request);
        String reason = req == null ? null : (req.getReason() != null ? req.getReason() : req.getRemark());
        return R.ok("平台复核已驳回", rmaService.platformRejectOrder(operatorId, orderNo, reason));
    }

    @PostMapping("/rma/{rmaNo}/approve")
    public R<OrderRma> approveRma(@PathVariable String rmaNo,
                                  @RequestBody(required = false) RefundReviewRequest req,
                                  HttpServletRequest request) {
        Long operatorId = UserContext.currentUserId(request);
        return R.ok("平台复核通过，已触发原路退款",
                rmaService.platformApproveRma(operatorId, rmaNo, req));
    }

    @PostMapping("/rma/{rmaNo}/reject")
    public R<OrderRma> rejectRma(@PathVariable String rmaNo,
                                 @RequestBody(required = false) RefundReviewRequest req,
                                 HttpServletRequest request) {
        Long operatorId = UserContext.currentUserId(request);
        String reason = req == null ? null : (req.getReason() != null ? req.getReason() : req.getRemark());
        return R.ok("平台复核已驳回", rmaService.platformRejectRma(operatorId, rmaNo, reason));
    }

    @PostMapping("/rma/{rmaNo}/reopen")
    public R<OrderRma> reopenForAppeal(@PathVariable String rmaNo,
                                       @RequestBody(required = false) RefundReviewRequest req,
                                       HttpServletRequest request) {
        Long operatorId = UserContext.currentUserId(request);
        return R.ok("申诉核实成立，已重开平台复核",
                rmaService.reopenForAppeal(operatorId, rmaNo, req == null ? null : req.getRemark()));
    }

    @GetMapping("/history/{orderNo}")
    public R<List<RefundApproval>> history(@PathVariable String orderNo) {
        return R.ok(rmaService.approvalHistory(orderNo));
    }
}

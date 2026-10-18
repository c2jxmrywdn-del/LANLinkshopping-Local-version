package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.CheckoutDTO;
import com.lanlink.shopping.entity.Order;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.OrderService;
import com.lanlink.shopping.vo.OrderDetailVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单接口。
 * 需登录：/order/checkout、/order/my、/order/detail/{orderNo}、/order/pay/{orderNo}、
 *        /order/cancel/{orderNo}、/order/refund/apply/{orderNo}（AuthInterceptor 全局登录拦截，
 *        不在公开白名单；各接口另做订单归属校验，仅订单本人可操作）。
 * 需管理员：/order/admin/list、/order/admin/refund/reject/{orderNo}（@RequirePerm("admin:all")）。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public R<Order> checkout(@Valid @RequestBody CheckoutDTO dto, HttpServletRequest request) {
        return R.ok("下单成功", orderService.checkout(UserContext.currentUserId(request), dto));
    }

    @GetMapping("/my")
    public R<List<Order>> my(HttpServletRequest request) {
        return R.ok(orderService.myOrders(UserContext.currentUserId(request)));
    }

    /** 管理端订单分页（交易管理，可按支付状态过滤，仅平台运营） */
    @GetMapping("/admin/list")
    @RequirePerm("admin:all")
    public R<Page<Order>> adminList(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) Integer payStatus) {
        return R.ok(orderService.adminOrders(page, size, payStatus));
    }

    @GetMapping("/detail/{orderNo}")
    public R<OrderDetailVO> detail(@PathVariable String orderNo, HttpServletRequest request) {
        var u = UserContext.current(request);
        boolean isAdmin = u != null && Long.valueOf(3L).equals(u.getRoleId());
        return R.ok(orderService.detail(UserContext.currentUserId(request), orderNo, isAdmin));
    }

    @PostMapping("/pay/{orderNo}")
    public R<Order> pay(@PathVariable String orderNo, HttpServletRequest request) {
        return R.ok("支付成功", orderService.pay(UserContext.currentUserId(request), orderNo));
    }

    @PostMapping("/cancel/{orderNo}")
    public R<Void> cancel(@PathVariable String orderNo, HttpServletRequest request) {
        orderService.cancel(UserContext.currentUserId(request), orderNo);
        return R.ok("订单已取消", null);
    }

    /**
     * 用户申请退款（提交平台审核队列）。
     * 权限：需登录（无 @RequirePerm 注解——这是用户自助操作而非管理员操作；
     * 登录由 AuthInterceptor 全局拦截保证，服务层校验订单归属 userId，仅订单本人可申请）。
     */
    @PostMapping("/refund/apply/{orderNo}")
    public R<Void> applyRefund(@PathVariable String orderNo,
                               @RequestBody(required = false) Map<String, String> body,
                               HttpServletRequest request) {
        orderService.applyRefund(UserContext.currentUserId(request), orderNo,
                body == null ? null : body.get("reason"));
        return R.ok("退款申请已提交", null);
    }

    /** 平台驳回退款申请（仅平台运营，驳回原因回显给用户） */
    @PostMapping("/admin/refund/reject/{orderNo}")
    @RequirePerm("admin:all")
    public R<Void> rejectRefund(@PathVariable String orderNo,
                                @RequestBody(required = false) Map<String, String> body) {
        orderService.rejectRefund(orderNo, body == null ? null : body.get("reason"));
        return R.ok("已驳回退款申请", null);
    }
}

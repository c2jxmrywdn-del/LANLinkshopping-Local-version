package com.lanlink.shopping.payment;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.payment.dto.PayCreateRequest;
import com.lanlink.shopping.payment.dto.RefundRequest;
import com.lanlink.shopping.payment.service.PaymentService;
import com.lanlink.shopping.payment.vo.PayCreateVO;
import com.lanlink.shopping.payment.vo.PayStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 支付接口。
 * 需登录：/payment/create、/payment/mock-confirm、/payment/query、/payment/my。
 * 需管理员：/payment/refund（@RequirePerm 权限注解，仅平台运营满足）。
 * 公开（渠道回调，已加入鉴权白名单）：/payment/notify/wechat、/payment/notify/alipay、/payment/alipay/return。
 */
@RestController
@RequestMapping("/payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/create")
    public R<PayCreateVO> create(@Valid @RequestBody PayCreateRequest req, HttpServletRequest request) {
        return R.ok(paymentService.create(req.getOrderNo(), UserContext.currentUserId(request), req.getChannel()));
    }

    /** mock 模式下前端"模拟支付成功" */
    @PostMapping("/mock-confirm")
    public R<Boolean> mockConfirm(@RequestParam String orderNo, HttpServletRequest request) {
        return R.ok(paymentService.mockConfirm(orderNo, UserContext.currentUserId(request)));
    }

    @GetMapping("/query/{orderNo}")
    public R<PayStatusVO> query(@PathVariable String orderNo, HttpServletRequest request) {
        var u = UserContext.current(request);
        boolean isAdmin = u != null && Long.valueOf(3L).equals(u.getRoleId());
        return R.ok(paymentService.query(orderNo, UserContext.currentUserId(request), isAdmin));
    }

    /** 我的交易记录（支付状态/渠道/交易号/退款进度） */
    @GetMapping("/my")
    public R<List<PayStatusVO>> my(HttpServletRequest request) {
        return R.ok(paymentService.myPayments(UserContext.currentUserId(request)));
    }

    /** 发起退款（仅平台运营） */
    @PostMapping("/refund")
    @RequirePerm("admin:all")
    public R<Boolean> refund(@Valid @RequestBody RefundRequest req, HttpServletRequest request) {
        Long adminId = UserContext.currentUserId(request);
        return R.ok("退款已受理", paymentService.refund(req, adminId));
    }

    // ===== 渠道异步通知（公开，无登录态） =====

    @PostMapping(value = "/notify/wechat",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE + ";charset=UTF-8")
    public String wechatNotify(@RequestBody String xmlBody) {
        return paymentService.handleWechatNotify(xmlBody);
    }

    @PostMapping(value = "/notify/alipay", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String alipayNotify(@RequestParam Map<String, String> params) {
        return paymentService.handleAlipayNotify(params);
    }

    /** 支付宝同步跳转落地页（浏览器回跳，无敏感操作，仅提示） */
    @GetMapping(value = "/alipay/return", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String alipayReturn(@RequestParam Map<String, String> params) {
        String orderNo = params.getOrDefault("out_trade_no", "");
        return "<!doctype html><meta charset='utf-8'><div style='font-family:sans-serif;padding:40px'>"
                + "<h3>支付已完成</h3><p>订单号：" + escape(orderNo)
                + "。请返回“我的订单”查看状态。</p></div>";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

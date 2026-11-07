package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.MerchantCenterService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 商户中心：工作台汇总 / 资金结算概览 / 店铺资料维护。
 * 全部接口需商户身份（merchant:manage 权限，@RequirePerm 校验）。
 */
@RestController
@RequestMapping("/merchant/center")
public class MerchantCenterController {

    private final MerchantCenterService centerService;
    private final MerchantService merchantService;
    private final com.lanlink.shopping.service.OrderService orderService;

    public MerchantCenterController(MerchantCenterService centerService,
                                    MerchantService merchantService,
                                    com.lanlink.shopping.service.OrderService orderService) {
        this.centerService = centerService;
        this.merchantService = merchantService;
        this.orderService = orderService;
    }

    /** 工作台汇总：店铺信息 + 核心指标 + 待办清单 + 近 7 日趋势 + 最新订单 */
    @GetMapping("/summary")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> summary(HttpServletRequest request) {
        return R.ok(centerService.summary(UserContext.currentUserId(request)));
    }

    /** 资金结算概览：累计收入/退款/待结算 + 近 30 日趋势 + 结算明细 */
    @GetMapping("/finance")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> finance(HttpServletRequest request) {
        return R.ok(centerService.finance(UserContext.currentUserId(request)));
    }

    /** 店铺资料：更新负责人联系方式与经营地址 */
    @PutMapping("/contact")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> updateContact(@RequestBody Map<String, String> body,
                                                HttpServletRequest request) {
        var m = merchantService.updateContact(UserContext.currentUserId(request), body);
        return R.ok("店铺资料已更新", Map.of(
                "merId", m.getMerId(),
                "contactPhone", m.getContactPhone() == null ? "" : m.getContactPhone(),
                "contactEmail", m.getContactEmail() == null ? "" : m.getContactEmail(),
                "businessAddress", m.getBusinessAddress() == null ? "" : m.getBusinessAddress()));
    }

    /** 对公收款账户：商户维护企业对公银行收款账户 */
    @PutMapping("/bank-account")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> updateBankAccount(@RequestBody Map<String, String> body,
                                                    HttpServletRequest request) {
        var m = merchantService.updateBankAccount(UserContext.currentUserId(request), body);
        return R.ok("对公收款账户已更新", Map.of(
                "merId", m.getMerId(),
                "bankName", m.getBankName() == null ? "" : m.getBankName(),
                "bankAccountMasked", com.lanlink.shopping.common.DesensitizeUtil.maskBankCard(m.getBankAccount())));
    }

    /** 商户退款初审：同意初审（流转为 merchant_approved，等待平台出款） */
    @PostMapping("/order/refund/approve/{orderNo}")
    @RequirePerm("merchant:manage")
    public R<Void> approveRefund(@PathVariable String orderNo, HttpServletRequest request) {
        orderService.merchantApproveRefund(UserContext.currentUserId(request), orderNo);
        return R.ok("商户初审已通过，等待平台放款处理", null);
    }

    /** 商户退款初审：驳回申请（流转为 rejected 并记录原因） */
    @PostMapping("/order/refund/reject/{orderNo}")
    @RequirePerm("merchant:manage")
    public R<Void> rejectRefund(@PathVariable String orderNo,
                                @RequestBody(required = false) Map<String, String> body,
                                HttpServletRequest request) {
        String reason = body != null ? body.get("reason") : null;
        orderService.merchantRejectRefund(UserContext.currentUserId(request), orderNo, reason);
        return R.ok("退款申请已驳回", null);
    }
}

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

    public MerchantCenterController(MerchantCenterService centerService, MerchantService merchantService) {
        this.centerService = centerService;
        this.merchantService = merchantService;
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
}

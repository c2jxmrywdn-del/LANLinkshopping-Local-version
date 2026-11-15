package com.lanlink.shopping.module.merchantexit.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.ExitStatusVO;
import com.lanlink.shopping.module.merchantexit.dto.MerchantExitViews.GateReport;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitGateService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitQueryService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 商户端退店：merId 一律来自会话；写端点 CSRF+限流（/merchant/exit/**）。 */
@RestController
@RequestMapping("/merchant/exit")
public class MerchantExitController {

    private final MerchantExitService exitService;
    private final MerchantExitGateService gateService;
    private final MerchantExitQueryService queryService;
    private final MerchantService merchantService;

    public MerchantExitController(MerchantExitService exitService, MerchantExitGateService gateService,
                                  MerchantExitQueryService queryService, MerchantService merchantService) {
        this.exitService = exitService;
        this.gateService = gateService;
        this.queryService = queryService;
        this.merchantService = merchantService;
    }

    private Merchant me(HttpServletRequest req) {
        return merchantService.getByUser(UserContext.currentUserId(req));
    }

    @GetMapping("/status")
    @RequirePerm("merchant:manage")
    public R<ExitStatusVO> status(HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok(queryService.status(m.getMerId()));
    }

    @GetMapping("/gate")
    @RequirePerm("merchant:manage")
    public R<GateReport> gate(HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok(gateService.check(m.getMerId()));
    }

    @PostMapping("/request")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> request(@RequestBody Map<String, String> body, HttpServletRequest req) {
        exitService.requestExit(UserContext.currentUserId(req), body == null ? null : body.get("reason"), req);
        return R.ok(Map.of("ok", true));
    }

    @PostMapping("/withdraw")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> withdraw(HttpServletRequest req) {
        exitService.withdraw(UserContext.currentUserId(req), req);
        return R.ok(Map.of("ok", true));
    }

    @GetMapping("/deposits")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> deposits(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size,
                                           HttpServletRequest req) {
        Merchant m = me(req);
        if (m == null) {
            return R.fail("请先完成商户入驻");
        }
        return R.ok(queryService.deposits(m.getMerId(), current, size));
    }
}

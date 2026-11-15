package com.lanlink.shopping.module.merchantexit.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.merchantexit.job.MerchantExitNoticeJob;
import com.lanlink.shopping.module.merchantexit.service.MerchantDepositService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitGateService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitQueryService;
import com.lanlink.shopping.module.merchantexit.service.MerchantExitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 运营端退店审核：登录 + admin:all + CSRF + 限流（/admin/merchant-exit/**）。 */
@RestController
@RequestMapping("/admin/merchant-exit")
public class AdminMerchantExitController {

    private final MerchantExitService exitService;
    private final MerchantExitGateService gateService;
    private final MerchantExitQueryService queryService;
    private final MerchantDepositService depositService;
    private final MerchantExitNoticeJob noticeJob;

    public AdminMerchantExitController(MerchantExitService exitService, MerchantExitGateService gateService,
                                       MerchantExitQueryService queryService,
                                       MerchantDepositService depositService,
                                       MerchantExitNoticeJob noticeJob) {
        this.exitService = exitService;
        this.gateService = gateService;
        this.queryService = queryService;
        this.depositService = depositService;
        this.noticeJob = noticeJob;
    }

    @GetMapping("/list")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String exitStatus) {
        return R.ok(queryService.adminList(current, size, exitStatus));
    }

    @GetMapping("/detail/{merId}")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> detail(@PathVariable Long merId) {
        Map<String, Object> out = new LinkedHashMap<>(queryService.adminRow(merId));
        out.put("gateReport", gateService.check(merId));
        out.put("deposits", queryService.allDeposits(merId));
        out.put("logs", queryService.logs(merId));
        out.put("depositBalance", depositService.balanceOf(merId));
        return R.ok(out);
    }

    @PostMapping("/{merId}/approve")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> approve(@PathVariable Long merId, HttpServletRequest req) {
        exitService.approve(com.lanlink.shopping.config.UserContext.currentUserId(req), merId, req);
        return R.ok(Map.of("ok", true));
    }

    @PostMapping("/{merId}/reject")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> reject(@PathVariable Long merId, @RequestBody Map<String, String> body,
                                         HttpServletRequest req) {
        exitService.reject(com.lanlink.shopping.config.UserContext.currentUserId(req), merId,
                body == null ? null : body.get("reason"), req);
        return R.ok(Map.of("ok", true));
    }

    @PostMapping("/{merId}/close")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> close(@PathVariable Long merId,
                                        @RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest req) {
        BigDecimal deduct = null;
        if (body != null && body.get("deductAmount") != null) {
            deduct = new BigDecimal(String.valueOf(body.get("deductAmount")));
        }
        String remark = body == null ? null : (String) body.get("remark");
        exitService.close(com.lanlink.shopping.config.UserContext.currentUserId(req), merId, deduct, remark, req);
        return R.ok(Map.of("ok", true));
    }

    @PostMapping("/run-notice")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> runNotice() {
        return R.ok(Map.of("frozen", noticeJob.freezeDue()));
    }
}

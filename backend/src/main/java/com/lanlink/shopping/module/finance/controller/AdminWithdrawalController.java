package com.lanlink.shopping.module.finance.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.finance.service.WithdrawalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端提现审核（待审列表 / 通过并登记打款凭证 / 驳回）。
 *
 * <p>登录 + {@code @RequirePerm("admin:all")} + CSRF（{@code /admin/**} 登记）+ 限流
 * （{@code /admin/finance/*} 条目）。商户只能通过商户端接口操作自己的数据，本控制器全部
 * 以 merId/status 维度查询，无任何以会话身份反查商户的路径。
 */
@RestController
@RequestMapping("/admin/finance/withdrawals")
public class AdminWithdrawalController {

    private final WithdrawalService service;

    public AdminWithdrawalController(WithdrawalService service) {
        this.service = service;
    }

    /** 提现单分页（merId/status 可选过滤；status=0 为待审队列）。 */
    @GetMapping
    @RequirePerm("admin:all")
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) Long merId,
                                       @RequestParam(required = false) Integer status) {
        var page = service.adminPage(current, size, merId, status);
        return R.ok(Map.of(
                "records", page.getRecords(),
                "total", page.getTotal(),
                "current", page.getCurrent(),
                "size", page.getSize()));
    }

    /**
     * 审核通过并标记打款：body {@code {voucherUrl}}（线下对公打款凭证，运营经
     * /attachment/upload 上传后取得中转 URL）。平台只登记凭证，不发起出金（对齐结算域二清口径）。
     */
    @PostMapping("/{id}/approve")
    @RequirePerm("admin:all")
    public R<Object> approve(@PathVariable Long id, @RequestBody Map<String, String> body,
                             HttpServletRequest request) {
        return R.ok("已登记打款", (Object) service.approve(
                UserContext.currentUserId(request), id, body.get("voucherUrl")));
    }

    /** 驳回：body {@code {reason}} 必填；解冻并落台账 withdraw_reject_unrefreeze。 */
    @PostMapping("/{id}/reject")
    @RequirePerm("admin:all")
    public R<Object> reject(@PathVariable Long id, @RequestBody Map<String, String> body,
                            HttpServletRequest request) {
        return R.ok("已驳回并解冻", (Object) service.reject(
                UserContext.currentUserId(request), id, body.get("reason")));
    }
}

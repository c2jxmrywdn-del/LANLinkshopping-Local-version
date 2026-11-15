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

import java.math.BigDecimal;
import java.util.Map;

/**
 * 商户端提现与财务台账。权限模型与 MerchantCenterController 同款：
 * 登录 + {@code @RequirePerm("merchant:manage")}，merId 一律由服务端从会话解析，
 * 商户只能看到/操作自己 mer_id 的数据（fail-closed）。
 *
 * <p>CSRF：POST/PUT 全部落入 WebConfig CsrfInterceptor 的 {@code /merchant/**} 登记；
 * 限流：{@code /merchant/finance/*} 在 AuthRateLimitInterceptor.POLICIES 有对应条目。
 */
@RestController
@RequestMapping("/merchant/finance")
public class MerchantFinanceController {

    private final WithdrawalService service;

    public MerchantFinanceController(WithdrawalService service) {
        this.service = service;
    }

    /** 可提余额概览（available/settledTotal/paidTotal/frozenTotal/pendingCount）。 */
    @GetMapping("/balance")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> balance(HttpServletRequest request) {
        return R.ok(service.balanceOf(UserContext.currentUserId(request)));
    }

    /** 提现申请：body {amount, bankAccountName, bankAccountNo, bankName, applyNote?}。 */
    @PostMapping("/withdrawals")
    @RequirePerm("merchant:manage")
    public R<Object> apply(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object amtRaw = body.get("amount");
        if (amtRaw == null) {
            throw new com.lanlink.shopping.common.BusinessException(400, "请填写提现金额");
        }
        BigDecimal amount = new BigDecimal(String.valueOf(amtRaw));
        String note = body.get("applyNote") == null ? null : String.valueOf(body.get("applyNote"));
        return R.ok("提现申请已提交，资金已冻结，等待平台审核", (Object) service.apply(
                UserContext.currentUserId(request),
                amount,
                str(body.get("bankAccountName")),
                str(body.get("bankAccountNo")),
                str(body.get("bankName")),
                note));
    }

    /** 我的提现记录分页（status 可选过滤：0待审/1已打款/2驳回/3已撤回）。 */
    @GetMapping("/withdrawals")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> myWithdrawals(@RequestParam(defaultValue = "1") long current,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) Integer status,
                                                HttpServletRequest request) {
        var page = service.myPage(UserContext.currentUserId(request), current, size, status);
        return R.ok(Map.of(
                "records", page.getRecords(),
                "total", page.getTotal(),
                "current", page.getCurrent(),
                "size", page.getSize()));
    }

    /** 撤回提现申请（仅待审态，解冻）。 */
    @PostMapping("/withdrawals/{id}/cancel")
    @RequirePerm("merchant:manage")
    public R<Object> cancel(@PathVariable("id") Long id, HttpServletRequest request) {
        return R.ok("提现申请已撤回，资金已解冻", (Object) service.cancel(
                UserContext.currentUserId(request), id));
    }

    /**
     * 打款凭证字节中转（代理附件域，凭证归属是上传的运营，商户不能直连 /attachment/{no}/content）。
     * 归属校验在 service：提现单 merId 与会话商户不一致即 403（fail-closed）。
     */
    @GetMapping("/withdrawals/{id}/voucher")
    @RequirePerm("merchant:manage")
    public org.springframework.http.ResponseEntity<byte[]> voucher(
            @PathVariable("id") Long id,
            @RequestParam(value = "variant", required = false) String variant,
            HttpServletRequest request) {
        var content = service.loadVoucher(UserContext.currentUserId(request), id, "thumb".equals(variant));
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(content.bytes());
    }

    /** 我的财务台账分页（每笔资金变动一行）。 */
    @GetMapping("/ledger")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> ledger(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         HttpServletRequest request) {
        var page = service.ledgerPage(UserContext.currentUserId(request), current, size);
        return R.ok(Map.of(
                "records", page.getRecords(),
                "total", page.getTotal(),
                "current", page.getCurrent(),
                "size", page.getSize()));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

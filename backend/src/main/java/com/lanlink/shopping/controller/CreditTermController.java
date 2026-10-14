package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.CreditTermService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/credit-term")
public class CreditTermController {
    private final CreditTermService service;
    public CreditTermController(CreditTermService service) { this.service = service; }

    @GetMapping("/overview")
    @RequirePerm("credit:view")
    public R<?> overview(HttpServletRequest request) { return R.ok(service.overview(UserContext.currentUserId(request))); }

    @GetMapping("/bills")
    @RequirePerm("credit:view")
    public R<List<CreditBill>> bills(HttpServletRequest request) { return R.ok(service.bills(UserContext.currentUserId(request))); }

    @GetMapping("/bills/{id}")
    @RequirePerm("credit:view")
    public R<CreditBill> detail(@PathVariable Long id, HttpServletRequest request) { return R.ok(service.detail(UserContext.currentUserId(request), id)); }

    @GetMapping("/bills/{id}/repayments")
    @RequirePerm("credit:view")
    public R<?> repayments(@PathVariable Long id, HttpServletRequest request) { return R.ok(service.repayments(UserContext.currentUserId(request), id)); }

    @PostMapping("/apply")
    @RequirePerm("credit:apply")
    public R<CreditAccount> apply(@RequestBody ApplyDTO dto, HttpServletRequest request) {
        return R.ok("账期申请已提交", service.apply(UserContext.currentUserId(request), dto.getRequestedLimit(), dto.getTermDays(), dto.getPurpose()));
    }

    @PostMapping("/bills/{id}/repay")
    @RequirePerm("credit:repay")
    public R<CreditBill> repay(@PathVariable Long id, @RequestBody RepayDTO dto, HttpServletRequest request) {
        return R.ok("还款成功", service.repay(UserContext.currentUserId(request), id, dto.getAmount(), dto.getMethod()));
    }

    @GetMapping("/admin/accounts")
    @RequirePerm("admin:all")
    public R<List<CreditAccount>> adminAccounts(@RequestParam(required = false) String status) { return R.ok(service.adminAccounts(status)); }

    @PostMapping("/admin/accounts/{id}/review")
    @RequirePerm("admin:all")
    public R<CreditAccount> review(@PathVariable Long id, @RequestBody ReviewDTO dto) {
        return R.ok("审核完成", service.review(id, dto.getStatus(), dto.getApprovedLimit(), dto.getTermDays(), dto.getRemark()));
    }

    @Data public static class ApplyDTO {
        @NotNull @DecimalMin("1000") @Digits(integer=10,fraction=2) private BigDecimal requestedLimit;
        @NotNull private Integer termDays;
        private String purpose;
    }
    @Data public static class RepayDTO {
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) private BigDecimal amount;
        private String method;
    }
    @Data public static class ReviewDTO {
        private String status;
        private BigDecimal approvedLimit;
        private Integer termDays;
        private String remark;
    }
}

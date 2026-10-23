package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.CreditAccount;
import com.lanlink.shopping.entity.CreditAdjustRequest;
import com.lanlink.shopping.entity.CreditBill;
import com.lanlink.shopping.entity.CreditRepayment;
import com.lanlink.shopping.vo.RepayResultVO;
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
import java.util.Map;

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
    public R<List<CreditRepayment>> repayments(@PathVariable Long id, HttpServletRequest request) { return R.ok(service.repayments(UserContext.currentUserId(request), id)); }

    @PostMapping("/apply")
    @RequirePerm("credit:apply")
    public R<CreditAccount> apply(@RequestBody ApplyDTO dto, HttpServletRequest request) {
        return R.ok("账期申请已提交", service.apply(UserContext.currentUserId(request), dto.getRequestedLimit(), dto.getTermDays(), dto.getPurpose()));
    }

    @PostMapping("/bills/{id}/repay")
    @RequirePerm("credit:repay")
    public R<RepayResultVO> repay(@PathVariable Long id, @RequestBody RepayDTO dto, HttpServletRequest request) {
        RepayResultVO result = service.repay(UserContext.currentUserId(request), id, dto.getAmount(), dto.getMethod(), dto.getReferenceNo());
        return R.ok(result.isPending() ? "转账登记已提交，待平台财务核销" : "还款成功", result);
    }

    // ==================== 调额申请 ====================

    @GetMapping("/adjustments")
    @RequirePerm("credit:view")
    public R<List<CreditAdjustRequest>> adjustments(HttpServletRequest request) {
        return R.ok(service.adjustments(UserContext.currentUserId(request)));
    }

    @PostMapping("/adjust")
    @RequirePerm("credit:apply")
    public R<CreditAdjustRequest> adjust(@RequestBody AdjustApplyDTO dto, HttpServletRequest request) {
        return R.ok("调额申请已提交", service.applyAdjustment(
                UserContext.currentUserId(request), dto.getRequestedLimit(), dto.getTermDays(), dto.getPurpose()));
    }

    @PostMapping("/adjustments/{id}/withdraw")
    @RequirePerm("credit:apply")
    public R<CreditAdjustRequest> withdrawAdjust(@PathVariable Long id, HttpServletRequest request) {
        return R.ok("调额申请已撤回", service.withdrawAdjustment(UserContext.currentUserId(request), id));
    }

    // ==================== 管理端 ====================

    @GetMapping("/admin/accounts")
    @RequirePerm("admin:all")
    public R<List<CreditAccount>> adminAccounts(@RequestParam(required = false) String status) { return R.ok(service.adminAccounts(status)); }

    @PostMapping("/admin/accounts/{id}/review")
    @RequirePerm("admin:all")
    public R<CreditAccount> review(@PathVariable Long id, @RequestBody ReviewDTO dto) {
        return R.ok("审核完成", service.review(id, dto.getStatus(), dto.getApprovedLimit(), dto.getTermDays(), dto.getRemark()));
    }

    @GetMapping("/admin/adjustments")
    @RequirePerm("admin:all")
    public R<List<Map<String, Object>>> adminAdjustments(@RequestParam(required = false) String status) {
        return R.ok(service.adminAdjustments(status));
    }

    @PostMapping("/admin/adjustments/{id}/review")
    @RequirePerm("admin:all")
    public R<CreditAdjustRequest> reviewAdjust(@PathVariable Long id, @RequestBody AdjustReviewDTO dto) {
        return R.ok("调额审核完成", service.reviewAdjustment(id, dto.getStatus(), dto.getApprovedLimit(), dto.getTermDays(), dto.getRemark()));
    }

    @GetMapping("/admin/repayments")
    @RequirePerm("admin:all")
    public R<List<Map<String, Object>>> adminRepayments(@RequestParam(required = false) String status) {
        return R.ok(service.adminRepayments(status));
    }

    @PostMapping("/admin/repayments/{id}/confirm")
    @RequirePerm("admin:all")
    public R<CreditRepayment> confirmRepayment(@PathVariable Long id, @RequestBody TransferConfirmDTO dto) {
        return R.ok(dto.isApproved() ? "转账已核销入账" : "转账登记已驳回",
                service.confirmTransferRepayment(id, dto.isApproved(), dto.getRemark()));
    }

    @Data public static class ApplyDTO {
        @NotNull @DecimalMin("1000") @Digits(integer=10,fraction=2) private BigDecimal requestedLimit;
        @NotNull private Integer termDays;
        private String purpose;
    }
    @Data public static class RepayDTO {
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) private BigDecimal amount;
        private String method;
        private String referenceNo;
    }
    @Data public static class ReviewDTO {
        private String status;
        private BigDecimal approvedLimit;
        private Integer termDays;
        private String remark;
    }
    @Data public static class AdjustApplyDTO {
        @NotNull @DecimalMin("1000") @Digits(integer=10,fraction=2) private BigDecimal requestedLimit;
        @NotNull private Integer termDays;
        private String purpose;
    }
    @Data public static class AdjustReviewDTO {
        private String status;
        private BigDecimal approvedLimit;
        private Integer termDays;
        private String remark;
    }
    @Data public static class TransferConfirmDTO {
        private boolean approved;
        private String remark;
    }
}

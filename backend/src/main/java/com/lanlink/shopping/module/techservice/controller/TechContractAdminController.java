package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.techservice.api.TechContractViews;
import com.lanlink.shopping.module.techservice.dto.TechEscrowCreateRequest;
import com.lanlink.shopping.module.techservice.service.TechAcceptanceService;
import com.lanlink.shopping.module.techservice.service.TechContractService;
import com.lanlink.shopping.module.techservice.service.TechSettlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P0 运营结算后台：合同台账、服务费确认、本金监管台账、验收记录。
 * 登录 + admin 权限 + CSRF；平台只登记资金凭证，不划转本金。
 */
@RestController
@RequestMapping("/admin/tech-service")
public class TechContractAdminController {

    private final TechContractService contractService;
    private final TechSettlementService settlementService;
    private final TechAcceptanceService acceptanceService;

    public TechContractAdminController(TechContractService contractService,
                                       TechSettlementService settlementService,
                                       TechAcceptanceService acceptanceService) {
        this.contractService = contractService;
        this.settlementService = settlementService;
        this.acceptanceService = acceptanceService;
    }

    @GetMapping("/contracts")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> contracts(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "12") long size,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword) {
        return R.ok(TechContractViews.page(contractService.adminPage(page, size, status, keyword),
                TechContractViews::ofContract));
    }

    @GetMapping("/contracts/{contractNo}")
    @RequirePerm("admin:all")
    public R<TechContractViews.Contract> contract(@PathVariable String contractNo) {
        return R.ok(TechContractViews.ofContract(contractService.adminDetail(contractNo)));
    }

    @GetMapping("/service-fees")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> fees(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "12") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(TechContractViews.page(settlementService.adminFeePage(page, size, status, keyword),
                TechContractViews::ofFee));
    }

    /** 确认服务费到账（条件更新，重复确认会失败） */
    @PostMapping("/service-fees/{id}/mark-paid")
    @RequirePerm("admin:all")
    public R<TechContractViews.ServiceFee> markPaid(@PathVariable Long id,
                                                    @RequestBody MarkPaidRequest body) {
        return R.ok("服务费已确认到账", TechContractViews.ofFee(settlementService.markPaid(
                id, body.payChannel(), body.externalRef(), body.invoiceNo())));
    }

    @GetMapping("/escrow-ledger")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> escrow(@RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "12") long size) {
        return R.ok(TechContractViews.page(settlementService.escrowPage(page, size),
                TechContractViews::ofEscrow));
    }

    /** 登记外部资金监管凭证（银行存管/持牌分账/交易所结算） */
    @PostMapping("/escrow-ledger")
    @RequirePerm("admin:all")
    public R<TechContractViews.Escrow> recordEscrow(@RequestBody TechEscrowCreateRequest body) {
        return R.ok("监管流水已登记", TechContractViews.ofEscrow(settlementService.recordEscrow(body)));
    }

    @GetMapping("/acceptances")
    @RequirePerm("admin:all")
    public R<Map<String, Object>> acceptances(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "12") long size) {
        return R.ok(TechContractViews.page(acceptanceService.adminPage(page, size),
                TechContractViews::ofAcceptance));
    }

    public record MarkPaidRequest(String payChannel, String externalRef, String invoiceNo) {}
}

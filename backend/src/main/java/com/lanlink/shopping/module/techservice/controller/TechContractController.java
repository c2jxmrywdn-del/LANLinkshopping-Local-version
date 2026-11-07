package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.techservice.api.TechContractViews;
import com.lanlink.shopping.module.techservice.dto.TechAcceptanceRequest;
import com.lanlink.shopping.module.techservice.dto.TechContractCreateRequest;
import com.lanlink.shopping.module.techservice.dto.TechServiceFeeCreateRequest;
import com.lanlink.shopping.module.techservice.service.TechAcceptanceService;
import com.lanlink.shopping.module.techservice.service.TechContractService;
import com.lanlink.shopping.module.techservice.service.TechSettlementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * P0 合同与结算（登录态 + CSRF）：
 * 模板 → 创建合同 → 本地签署 → 服务费登记 → 项目验收。
 */
@RestController
@RequestMapping("/tech")
public class TechContractController {

    private final TechContractService contractService;
    private final TechSettlementService settlementService;
    private final TechAcceptanceService acceptanceService;

    public TechContractController(TechContractService contractService,
                                  TechSettlementService settlementService,
                                  TechAcceptanceService acceptanceService) {
        this.contractService = contractService;
        this.settlementService = settlementService;
        this.acceptanceService = acceptanceService;
    }

    @GetMapping("/contracts/templates")
    public R<List<TechContractViews.Template>> templates() {
        return R.ok(contractService.templates().stream().map(TechContractViews::ofTemplate).toList());
    }

    @PostMapping("/contracts")
    public R<TechContractViews.Contract> create(@RequestBody TechContractCreateRequest body,
                                                HttpServletRequest request) {
        return R.ok("合同已生成，待签署",
                TechContractViews.ofContract(contractService.create(UserContext.currentUserId(request), body)));
    }

    @GetMapping("/contracts/my")
    public R<List<TechContractViews.Contract>> myContracts(HttpServletRequest request) {
        return R.ok(contractService.myContracts(UserContext.currentUserId(request)).stream()
                .map(TechContractViews::ofContract).toList());
    }

    @GetMapping("/contracts/{contractNo}")
    public R<TechContractViews.Contract> detail(@PathVariable String contractNo, HttpServletRequest request) {
        return R.ok(TechContractViews.ofContract(
                contractService.detail(UserContext.currentUserId(request), contractNo)));
    }

    /** 本地演示签署；生产接入持牌电子签服务 */
    @PostMapping("/contracts/{contractNo}/sign")
    public R<TechContractViews.Contract> sign(@PathVariable String contractNo, HttpServletRequest request) {
        return R.ok("合同已签署", TechContractViews.ofContract(
                contractService.sign(UserContext.currentUserId(request), contractNo)));
    }

    @PostMapping("/contracts/{contractNo}/service-fees")
    public R<TechContractViews.ServiceFee> createFee(@PathVariable String contractNo,
                                                     @RequestBody TechServiceFeeCreateRequest body,
                                                     HttpServletRequest request) {
        return R.ok("服务费已登记，等待支付", TechContractViews.ofFee(
                settlementService.createFee(UserContext.currentUserId(request), contractNo, body)));
    }

    @GetMapping("/service-fees/my")
    public R<List<TechContractViews.ServiceFee>> myFees(HttpServletRequest request) {
        return R.ok(settlementService.myFees(UserContext.currentUserId(request)).stream()
                .map(TechContractViews::ofFee).toList());
    }

    @PostMapping("/projects/{projectId}/acceptance")
    public R<Map<String, Object>> accept(@PathVariable Long projectId,
                                         @RequestBody TechAcceptanceRequest body,
                                         HttpServletRequest request) {
        Map<String, Object> raw = acceptanceService.accept(UserContext.currentUserId(request), projectId, body);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("acceptance", TechContractViews.ofAcceptance(raw.get("acceptance")));
        if (raw.get("project") != null) {
            out.put("project", com.lanlink.shopping.module.techservice.api.TechViews.projectOf(raw.get("project")));
        }
        return R.ok("验收已提交", out);
    }

    @GetMapping("/projects/{projectId}/acceptances")
    public R<List<TechContractViews.Acceptance>> acceptances(@PathVariable Long projectId,
                                                             HttpServletRequest request) {
        return R.ok(acceptanceService.listByProject(UserContext.currentUserId(request), projectId).stream()
                .map(TechContractViews::ofAcceptance).toList());
    }
}

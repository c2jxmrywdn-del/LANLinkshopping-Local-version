package com.lanlink.shopping.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.LogisticsAftersale;
import com.lanlink.shopping.entity.LogisticsAlert;
import com.lanlink.shopping.entity.LogisticsProvider;
import com.lanlink.shopping.entity.LogisticsSettlement;
import com.lanlink.shopping.entity.LogisticsSettlementItem;
import com.lanlink.shopping.entity.LogisticsTemplate;
import com.lanlink.shopping.entity.LogisticsReconciliation;
import com.lanlink.shopping.entity.LogisticsInvoice;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.ReconciliationImportDTO;
import com.lanlink.shopping.dto.LogisticsReconciliationDTO.DisputeResolveDTO;
import com.lanlink.shopping.dto.LogisticsInvoiceDTO.InvoiceUploadDTO;
import com.lanlink.shopping.dto.LogisticsInvoiceDTO.InvoiceBindDTO;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.LogisticsAdminService;
import com.lanlink.shopping.service.LogisticsSettlementService;
import com.lanlink.shopping.service.LogisticsReconciliationService;
import com.lanlink.shopping.service.LogisticsInvoiceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 物流运营后台（服务商管理 / 运费模板 / 异常告警 / 物流售后 / 物流结算）
 * 全部端点需平台运营权限 admin:all。
 */
@RestController
@RequestMapping("/admin/logistics")
@RequirePerm("admin:all")
public class LogisticsAdminController {

    private final LogisticsAdminService adminService;
    private final LogisticsSettlementService settlementService;
    private final LogisticsReconciliationService reconService;
    private final LogisticsInvoiceService invoiceService;

    public LogisticsAdminController(LogisticsAdminService adminService,
                                    LogisticsSettlementService settlementService,
                                    LogisticsReconciliationService reconService,
                                    LogisticsInvoiceService invoiceService) {
        this.adminService = adminService;
        this.settlementService = settlementService;
        this.reconService = reconService;
        this.invoiceService = invoiceService;
    }

    /* ==================== 服务商 ==================== */

    @GetMapping("/provider/list")
    public R<List<LogisticsProvider>> providers() {
        return R.ok(adminService.providers());
    }

    @PostMapping("/provider/save")
    public R<LogisticsProvider> saveProvider(@RequestBody LogisticsProvider provider, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("保存成功", adminService.saveProvider(provider, userId, request));
    }

    /* ==================== 运费模板 ==================== */

    @GetMapping("/template/list")
    public R<List<LogisticsTemplate>> templates(@RequestParam(required = false) Long providerId) {
        return R.ok(adminService.templates(providerId));
    }

    @PostMapping("/template/save")
    public R<LogisticsTemplate> saveTemplate(@RequestBody LogisticsTemplate template, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("保存成功", adminService.saveTemplate(template, userId, request));
    }

    /* ==================== 异常告警 ==================== */

    @GetMapping("/alert/list")
    public R<Page<LogisticsAlert>> alerts(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "10") long size,
                                          @RequestParam(required = false) String status) {
        return R.ok(adminService.alerts(page, size, status));
    }

    @PostMapping("/alert/handle/{alertId}")
    public R<Void> handleAlert(@PathVariable Long alertId,
                               @RequestParam(required = false) String note,
                               HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        adminService.handleAlert(alertId, note, userId, request);
        return R.ok("告警已处理", null);
    }

    /* ==================== 物流售后 ==================== */

    @GetMapping("/aftersale/list")
    public R<Page<LogisticsAftersale>> aftersales(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String status) {
        return R.ok(adminService.aftersales(page, size, status));
    }

    @PostMapping("/aftersale/create")
    public R<LogisticsAftersale> createAftersale(@RequestBody LogisticsAftersale aftersale,
                                                 HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("售后单创建成功", adminService.createAftersale(aftersale, userId, request));
    }

    @PostMapping("/aftersale/process/{aftersaleId}")
    public R<LogisticsAftersale> processAftersale(@PathVariable Long aftersaleId,
                                                  @RequestParam String action,
                                                  @RequestParam(required = false) String note,
                                                  HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("售后处理成功", adminService.processAftersale(aftersaleId, action, note, userId, request));
    }

    /* ==================== 物流结算 ==================== */

    @GetMapping("/settlement/list")
    public R<Page<LogisticsSettlement>> settlements(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long size,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Long providerId) {
        return R.ok(settlementService.page(page, size, status, providerId));
    }

    @GetMapping("/settlement/items/{settlementId}")
    public R<List<LogisticsSettlementItem>> settlementItems(@PathVariable Long settlementId) {
        return R.ok(settlementService.items(settlementId));
    }

    @PostMapping("/settlement/generate")
    public R<LogisticsSettlement> generateSettlement(
            @RequestParam Long providerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) BigDecimal deduction,
            @RequestParam(required = false) BigDecimal extraFee,
            HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("结算单生成成功",
                settlementService.generate(providerId, periodStart, periodEnd, deduction, extraFee, userId, request));
    }

    /** 结算审核动作：submit/approve/pay/reject */
    @PostMapping("/settlement/review/{settlementId}")
    public R<LogisticsSettlement> reviewSettlement(@PathVariable Long settlementId,
                                                   @RequestParam String action,
                                                   @RequestParam(required = false) String note,
                                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("结算操作成功", settlementService.review(settlementId, action, note, userId, request));
    }

    /* ==================== 承运商月结对账单勾稽 ==================== */

    @PostMapping("/recon/import")
    public R<LogisticsReconciliation> importRecon(@RequestBody ReconciliationImportDTO req, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("对账单导入成功", reconService.importCarrierBill(req, userId));
    }

    @PostMapping("/recon/match/{reconId}")
    public R<Map<String, Object>> runReconMatching(@PathVariable Long reconId) {
        return R.ok("三单勾稽核对完成", reconService.runMatching(reconId));
    }

    @GetMapping("/recon/detail/{reconId}")
    public R<Map<String, Object>> reconDetail(@PathVariable Long reconId) {
        return R.ok(reconService.getReconciliationDetail(reconId));
    }

    @PostMapping("/recon/resolve-dispute")
    public R<Void> resolveReconDispute(@RequestBody DisputeResolveDTO req) {
        reconService.resolveDispute(req.getItemId(), req.getAdjustmentAmount(), req.getAdjustNote());
        return R.ok("争议已调解平账", null);
    }

    /* ==================== 6% 交通运输专票进池与冲抵 ==================== */

    @PostMapping("/invoice/upload")
    public R<LogisticsInvoice> uploadInvoice(@RequestBody InvoiceUploadDTO req) {
        return R.ok("专票成功入池", invoiceService.uploadInvoice(req));
    }

    @PostMapping("/invoice/verify/{invoiceId}")
    public R<LogisticsInvoice> verifyInvoice(@PathVariable Long invoiceId) {
        return R.ok("税局查验完成", invoiceService.verifyInvoice(invoiceId));
    }

    @GetMapping("/invoice/pool")
    public R<List<LogisticsInvoice>> invoicePool(@RequestParam(required = false) Long providerId,
                                                @RequestParam(required = false) String verifyStatus,
                                                @RequestParam(required = false) String offsetStatus) {
        return R.ok(invoiceService.listInvoicePool(providerId, verifyStatus, offsetStatus));
    }

    @PostMapping("/invoice/bind-settlement")
    public R<Void> bindSettlement(@RequestBody InvoiceBindDTO req) {
        invoiceService.bindSettlement(req.getInvoiceId(), req.getSettlementId());
        return R.ok("专票冲抵核销成功", null);
    }
}

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
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.LogisticsAdminService;
import com.lanlink.shopping.service.LogisticsSettlementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    public LogisticsAdminController(LogisticsAdminService adminService,
                                    LogisticsSettlementService settlementService) {
        this.adminService = adminService;
        this.settlementService = settlementService;
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
}

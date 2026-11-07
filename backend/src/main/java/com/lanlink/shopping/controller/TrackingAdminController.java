package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.tracking.service.TrackingExceptionService;
import com.lanlink.shopping.module.tracking.service.TrackingMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 物流跟踪运营端点（out/logistics-tracking-plan.md P2/P3 任务 4/5）：
 *   GET  /api/admin/logistics/exception                    异常看板（48h 无更新异常 + 人工复核队列 + 待处理告警）
 *   POST /api/admin/logistics/exception/{waybillId}/ticket 看板「转工单」（写 alert.ticket_id）
 *   GET  /api/admin/logistics/metrics                      监控指标（回调成功率/补拉失败率/入库延迟 P95/异常占比）
 * 全类 @RequirePerm("admin:all")，对齐 LogisticsAdminController 既有模式（登录 + 权限双层拦截）。
 */
@RestController
@RequestMapping("/admin/logistics")
@RequirePerm("admin:all")
public class TrackingAdminController {

    private final TrackingExceptionService exceptionService;
    private final TrackingMetricsService metricsService;

    public TrackingAdminController(TrackingExceptionService exceptionService,
                                   TrackingMetricsService metricsService) {
        this.exceptionService = exceptionService;
        this.metricsService = metricsService;
    }

    @GetMapping("/exception")
    public R<Map<String, Object>> exceptionBoard() {
        return R.ok(exceptionService.board());
    }

    @PostMapping("/exception/{waybillId}/ticket")
    public R<Map<String, Object>> createTicket(@PathVariable Long waybillId, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        return R.ok("已转客服工单", exceptionService.createTicket(waybillId, userId));
    }

    @GetMapping("/metrics")
    public R<Map<String, Object>> metrics() {
        return R.ok(metricsService.collect(LocalDateTime.now()));
    }
}

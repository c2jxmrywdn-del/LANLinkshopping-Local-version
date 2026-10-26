package com.lanlink.shopping.module.pricemonitor.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.pricemonitor.service.PriceMonitorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 价格监控（平台运营后台）
 *  - GET /admin/price-monitor/anomalies?days=7&limit=50  全平台近期价格变动排行
 *  - GET /admin/price-monitor/benchmarks                 各行业基准价总览
 *  - POST /admin/price-monitor/refresh                   手动触发行业基准刷新（幂等）
 */
@RestController
@RequestMapping("/admin/price-monitor")
@RequirePerm("admin:all")
public class AdminPriceMonitorController {

    private final PriceMonitorService service;

    public AdminPriceMonitorController(PriceMonitorService service) {
        this.service = service;
    }

    @GetMapping("/anomalies")
    public R<List<Map<String, Object>>> anomalies(@RequestParam(defaultValue = "7") int days,
                                                  @RequestParam(defaultValue = "50") int limit) {
        return R.ok(service.adminAnomalies(Math.min(Math.max(days, 1), 90), limit));
    }

    @GetMapping("/benchmarks")
    public R<List<Map<String, Object>>> benchmarks() {
        return R.ok(service.adminBenchmarks());
    }

    @PostMapping("/refresh")
    public R<Integer> refresh() {
        return R.ok("行业基准已刷新", service.refreshBenchmarks());
    }
}

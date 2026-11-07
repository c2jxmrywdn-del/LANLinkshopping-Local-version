package com.lanlink.shopping.module.metrics.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.metrics.service.MetricsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 平台经营指标看板 API（/admin/** 由 AuthInterceptor 限平台运营）。
 * GET /admin/metrics/overview?days=14 → funnel/bySource/dailyTrade/ads 四块。
 * 口径为其余功能提供统一评估基准：搜索/推荐来源 CTR、GMV/AOV 护栏。
 */
@RestController
@RequestMapping("/admin/metrics")
public class MetricsController {

    private final MetricsService service;

    public MetricsController(MetricsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(required = false) Integer days) {
        return R.ok(service.overview(days));
    }
}

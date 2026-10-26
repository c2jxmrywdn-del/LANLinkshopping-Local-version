package com.lanlink.shopping.module.pricemonitor.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.entity.Merchant;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.pricemonitor.entity.MarketNews;
import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;
import com.lanlink.shopping.module.pricemonitor.service.MarketNewsService;
import com.lanlink.shopping.module.pricemonitor.service.PriceMonitorAiService;
import com.lanlink.shopping.module.pricemonitor.service.PriceMonitorService;
import com.lanlink.shopping.service.MerchantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 价格智能监控 API（买家 + 商户端）
 *  - GET  /price-monitor/trend/{prodId}?days=30      价格走势+日K+高低价（公开，白名单）
 *  - GET  /price-monitor/factors/{prodId}            五维价格因子看板（公开，白名单）
 *  - GET  /price-monitor/news                        市场资讯（公开，白名单）
 *  - GET  /price-monitor/analysis/{prodId}           智能调价建议（登录）
 *  - POST /price-monitor/alerts                      订阅价格预警（登录）
 *  - GET  /price-monitor/my/alerts                   我的预警订阅（登录）
 *  - GET  /price-monitor/product/{prodId}/alerts     商品详情回显订阅（登录）
 *  - DELETE /price-monitor/alerts/{id}               取消订阅（登录）
 *  - GET  /price-monitor/merchant/overview           商户价格总览（merchant:manage）
 *  - GET  /price-monitor/merchant/analysis/{prodId}  商户单商品分析（merchant:manage）
 */
@RestController
@RequestMapping("/price-monitor")
public class PriceMonitorController {

    private final PriceMonitorService service;
    private final PriceMonitorAiService aiService;
    private final MarketNewsService newsService;
    private final MerchantService merchantService;

    public PriceMonitorController(PriceMonitorService service, PriceMonitorAiService aiService,
                                  MarketNewsService newsService, MerchantService merchantService) {
        this.service = service;
        this.aiService = aiService;
        this.newsService = newsService;
        this.merchantService = merchantService;
    }

    @GetMapping("/trend/{prodId}")
    public R<Map<String, Object>> trend(@PathVariable Long prodId,
                                        @RequestParam(defaultValue = "30") int days) {
        return R.ok(service.trend(prodId, Math.min(Math.max(days, 7), 90)));
    }

    /** 首页每日行情可选商品（有真实调价留痕的在售商品）。 */
    @GetMapping("/market/products")
    public R<List<Map<String, Object>>> marketProducts(@RequestParam(defaultValue = "12") int limit) {
        return R.ok(service.marketProducts(limit));
    }

    @GetMapping("/analysis/{prodId}")
    public R<Map<String, Object>> analysis(@PathVariable Long prodId, HttpServletRequest request) {
        UserContext.currentUserId(request); // 需登录
        return R.ok(service.analyze(prodId));
    }

    @PostMapping("/alerts")
    public R<Void> subscribe(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Long prodId = body.get("prodId") == null ? null : Long.valueOf(String.valueOf(body.get("prodId")));
        String cond = String.valueOf(body.getOrDefault("conditionType", ""));
        BigDecimal threshold = body.get("threshold") == null ? null
                : new BigDecimal(String.valueOf(body.get("threshold")));
        service.subscribe(userId, prodId, cond, threshold);
        return R.ok("提醒已开启，价格变动将通过站内消息通知你", null);
    }

    @GetMapping("/my/alerts")
    public R<List<PriceAlert>> myAlerts(HttpServletRequest request) {
        return R.ok(service.myAlerts(UserContext.currentUserId(request)));
    }

    @GetMapping("/product/{prodId}/alerts")
    public R<List<PriceAlert>> productAlerts(@PathVariable Long prodId, HttpServletRequest request) {
        return R.ok(service.productAlerts(UserContext.currentUserId(request), prodId));
    }

    @DeleteMapping("/alerts/{id}")
    public R<Void> cancel(@PathVariable Long id, HttpServletRequest request) {
        service.cancel(UserContext.currentUserId(request), id);
        return R.ok("已取消该提醒", null);
    }

    // ===== 市场资讯 + AI 解读 =====

    @GetMapping("/news")
    public R<List<MarketNews>> news(@RequestParam(required = false) String category,
                                    @RequestParam(defaultValue = "20") int limit) {
        return R.ok(newsService.list(category, limit));
    }

    /** 五维价格因子看板（公开：首页每日行情、商品详情页均可展示）。 */
    @GetMapping("/factors/{prodId}")
    public R<Map<String, Object>> factors(@PathVariable Long prodId) {
        return R.ok(aiService.factors(prodId));
    }

    @GetMapping("/ai-analysis/{prodId}")
    public R<Map<String, Object>> aiAnalysis(@PathVariable Long prodId,
                                             @RequestParam(defaultValue = "zh") String lang,
                                             HttpServletRequest request) {
        UserContext.currentUserId(request); // 需登录
        return R.ok(aiService.analysis(prodId, lang));
    }

    @PostMapping("/ask")
    public R<Map<String, Object>> ask(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        UserContext.currentUserId(request); // 需登录
        Long prodId = body.get("prodId") == null ? null : Long.valueOf(String.valueOf(body.get("prodId")));
        String question = body.get("question") == null ? null : String.valueOf(body.get("question"));
        String lang = body.get("lang") == null ? "zh" : String.valueOf(body.get("lang"));
        return R.ok(aiService.ask(prodId, question, lang));
    }

    // ===== 商户端 =====

    @RequirePerm("merchant:manage")
    @GetMapping("/merchant/overview")
    public R<List<Map<String, Object>>> merchantOverview(HttpServletRequest request) {
        return R.ok(service.merchantOverview(mustMerId(request)));
    }

    @RequirePerm("merchant:manage")
    @GetMapping("/merchant/analysis/{prodId}")
    public R<Map<String, Object>> merchantAnalysis(@PathVariable Long prodId, HttpServletRequest request) {
        mustMerId(request);
        return R.ok(service.analyze(prodId));
    }

    private Long mustMerId(HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        Merchant m = merchantService.getByUser(userId);
        if (m == null) throw new com.lanlink.shopping.common.BusinessException("仅入驻商户可访问");
        return m.getMerId();
    }
}

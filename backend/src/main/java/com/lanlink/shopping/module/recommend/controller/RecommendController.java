package com.lanlink.shopping.module.recommend.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.recommend.service.RecommendService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品推荐 API（公开浏览；个性化仅在 analytics 授权下生效，否则自动回退热度榜）。
 *  - GET /recommendations/home?limit=8        首页"猜你喜欢/热门商品"
 *  - GET /recommendations/similar/{prodId}    详情页"看了又看"
 */
@RestController
@RequestMapping("/recommendations")
public class RecommendController {

    private final RecommendService service;

    public RecommendController(RecommendService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public R<Map<String, Object>> home(@RequestParam(defaultValue = "8") int limit,
                                       @RequestHeader(value = "Cookie", required = false) String cookie,
                                       HttpServletRequest request) {
        Long userId = currentUserIdOrNull(request);
        var rec = service.home(userId, analyticsConsentCookie(cookie), clamp(limit));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personalized", rec.personalized());
        out.put("reason", rec.reason());
        out.put("items", rec.items());
        return R.ok(out);
    }

    @GetMapping("/similar/{prodId}")
    public R<Map<String, Object>> similar(@PathVariable Long prodId,
                                          @RequestParam(defaultValue = "6") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", service.similar(prodId, clamp(limit)));
        return R.ok(out);
    }

    /** 登录用户 id（推荐位公开浏览，匿名自动降级） */
    private Long currentUserIdOrNull(HttpServletRequest request) {
        try {
            return UserContext.currentUserId(request);
        } catch (Exception e) {
            return null;
        }
    }

    /** analytics 授权信号：前端 saveCookieConsent 会写 ll_analytics=1 Cookie，服务端直读 */
    private String analyticsConsentCookie(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split("; ")) {
            if (part.startsWith("ll_analytics=")) {
                return part.substring("ll_analytics=".length());
            }
        }
        return null;
    }

    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 20);
    }
}

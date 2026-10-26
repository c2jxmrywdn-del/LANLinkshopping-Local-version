package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.service.AdCampaignService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 广告流量推广接口。
 * 商户端（/ad/campaigns/**）：创建/支付/暂停/恢复/终止/列表/总览，需 merchant:manage。
 * 商城侧（/ad/slots、/ad/track）：广告位与曝光/点击上报，公开（游客也可浏览商城广告）。
 */
@RestController
@RequestMapping("/ad")
public class AdCampaignController {

    private final AdCampaignService adService;

    public AdCampaignController(AdCampaignService adService) {
        this.adService = adService;
    }

    // ===== 商户端 =====

    /** 创建推广计划（选品 + 预算 + 天数 → 待支付） */
    @PostMapping("/campaigns")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.entity.AdCampaign> create(@RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        return R.ok("推广计划已创建，请支付预算", adService.create(UserContext.currentUserId(request), body));
    }

    /** 钱包支付总预算并开始投放 */
    @PostMapping("/campaigns/{campaignId}/pay")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.entity.AdCampaign> pay(@PathVariable Long campaignId,
                                                         HttpServletRequest request) {
        return R.ok("支付成功，推广已上线",
                adService.pay(UserContext.currentUserId(request), campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/pause")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.entity.AdCampaign> pause(@PathVariable Long campaignId,
                                                           HttpServletRequest request) {
        return R.ok("推广已暂停", adService.pause(UserContext.currentUserId(request), campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/resume")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.entity.AdCampaign> resume(@PathVariable Long campaignId,
                                                            HttpServletRequest request) {
        return R.ok("推广已恢复", adService.resume(UserContext.currentUserId(request), campaignId));
    }

    /** 手动终止并结算退回剩余预算 */
    @PostMapping("/campaigns/{campaignId}/terminate")
    @RequirePerm("merchant:manage")
    public R<com.lanlink.shopping.entity.AdCampaign> terminate(@PathVariable Long campaignId,
                                                               HttpServletRequest request) {
        return R.ok("推广已结束，剩余预算已退回钱包",
                adService.terminate(UserContext.currentUserId(request), campaignId));
    }

    /** 我的推广计划列表 */
    @GetMapping("/campaigns/my")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> my(HttpServletRequest request) {
        return R.ok(adService.myCampaigns(UserContext.currentUserId(request)));
    }

    /** 推广总览（花费/点击/曝光/CTR/结算退回/钱包余额） */
    @GetMapping("/campaigns/stats")
    @RequirePerm("merchant:manage")
    public R<Map<String, Object>> stats(HttpServletRequest request) {
        return R.ok(adService.stats(UserContext.currentUserId(request)));
    }

    // ===== 商城侧（公开） =====

    /** 广告位商品（商城列表插入位，按剩余预算轮换） */
    @GetMapping("/slots")
    public R<List<Map<String, Object>>> slots(@RequestParam(defaultValue = "3") int limit) {
        return R.ok(adService.slots(limit));
    }

    /** 曝光/点击上报：type = impression（计数）| click（按 CPC 计费） */
    @PostMapping("/track")
    public R<Map<String, Object>> track(@RequestBody Map<String, Object> body) {
        Long campaignId = null;
        try {
            campaignId = body.get("campaignId") == null ? null : Long.valueOf(String.valueOf(body.get("campaignId")));
        } catch (NumberFormatException ignored) {
        }
        String type = String.valueOf(body.getOrDefault("type", ""));
        if (campaignId == null) return R.fail("缺少推广计划标识");
        boolean charged = false;
        if ("click".equals(type)) {
            charged = adService.trackClick(campaignId);
        } else if ("impression".equals(type)) {
            adService.trackImpression(campaignId);
        } else {
            return R.fail("不支持的上报类型");
        }
        return R.ok(Map.of("charged", charged));
    }
}

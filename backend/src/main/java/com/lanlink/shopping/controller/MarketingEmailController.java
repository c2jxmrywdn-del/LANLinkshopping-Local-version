package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.dto.MarketingEmailCampaignDTO;
import com.lanlink.shopping.service.MarketingEmailService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Platform-operations endpoint for promotional email campaigns.
 * AuthInterceptor restricts /admin/** to platform operators; WebConfig also
 * applies the session CSRF check before this endpoint can queue email.
 */
@RestController
@RequestMapping("/admin/marketing-email")
public class MarketingEmailController {
    private final MarketingEmailService marketingEmailService;

    public MarketingEmailController(MarketingEmailService marketingEmailService) {
        this.marketingEmailService = marketingEmailService;
    }

    @PostMapping("/send")
    public R<Map<String, Object>> send(@Valid @RequestBody MarketingEmailCampaignDTO campaign) {
        return R.ok(marketingEmailService.sendCampaign(campaign));
    }
}

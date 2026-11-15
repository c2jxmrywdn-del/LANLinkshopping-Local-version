package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.service.HomeService;
import com.lanlink.shopping.service.MerchantService;
import org.springframework.web.bind.annotation.*;

/**
 * 首页 / 行业 / 分类 (公开)
 */
@RestController
public class HomeController {

    private final HomeService homeService;
    private final MerchantService merchantService;

    public HomeController(HomeService homeService, MerchantService merchantService) {
        this.homeService = homeService;
        this.merchantService = merchantService;
    }

    @GetMapping("/home/industries")
    public R<?> industries() {
        return R.ok(homeService.industries());
    }

    @GetMapping("/home/hot")
    public R<?> hot(@RequestParam(defaultValue = "8") int limit) {
        var products = homeService.hot(limit);
        merchantService.attachPlatformInvitedBadge(products);
        return R.ok(products);
    }

    @GetMapping("/category/list")
    public R<?> categories(@RequestParam(required = false) Long indId) {
        return R.ok(homeService.categories(indId));
    }
}

package com.lanlink.shopping.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.service.HomeService;
import org.springframework.web.bind.annotation.*;

/**
 * 首页 / 行业 / 分类 (公开)
 */
@RestController
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping("/home/industries")
    public R<?> industries() {
        return R.ok(homeService.industries());
    }

    @GetMapping("/home/hot")
    public R<?> hot(@RequestParam(defaultValue = "8") int limit) {
        return R.ok(homeService.hot(limit));
    }

    @GetMapping("/category/list")
    public R<?> categories(@RequestParam(required = false) Long indId) {
        return R.ok(homeService.categories(indId));
    }
}

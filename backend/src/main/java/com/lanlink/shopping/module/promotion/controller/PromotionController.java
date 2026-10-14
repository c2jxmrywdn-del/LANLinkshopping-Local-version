package com.lanlink.shopping.module.promotion.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.module.promotion.entity.Promotion;
import com.lanlink.shopping.module.promotion.service.PromotionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 促销系统 API（营销中台）
 *  - GET /promotion/list  当前生效促销（前台展示）
 *  - GET /promotion/active 同 list（语义别名）
 */
@RestController
@RequestMapping("/promotion")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping("/list")
    public R<List<Promotion>> list() {
        return R.ok(promotionService.list());
    }

    @GetMapping("/active")
    public R<List<Promotion>> active() {
        return R.ok(promotionService.list());
    }
}

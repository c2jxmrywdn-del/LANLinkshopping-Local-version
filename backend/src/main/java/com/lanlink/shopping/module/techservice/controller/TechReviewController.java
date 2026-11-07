package com.lanlink.shopping.module.techservice.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.module.techservice.api.TechP1Views;
import com.lanlink.shopping.module.techservice.dto.TechP1Requests;
import com.lanlink.shopping.module.techservice.service.TechReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 履约评价前台（登录 + CSRF）：项目验收完成后对专家 / 经纪人 / 机构评分。 */
@RestController
@RequestMapping("/tech/projects/{projectId}/reviews")
public class TechReviewController {

    private final TechReviewService reviewService;

    public TechReviewController(TechReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public R<List<TechP1Views.Review>> myReviews(@PathVariable Long projectId,
                                                 HttpServletRequest request) {
        return R.ok(reviewService.listMineByProject(UserContext.currentUserId(request), projectId).stream()
                .map(TechP1Views::ofReview).toList());
    }

    @PostMapping
    public R<TechP1Views.Review> submit(@PathVariable Long projectId,
                                        @RequestBody TechP1Requests.ReviewCreate body,
                                        HttpServletRequest request) {
        return R.ok("评价已提交", TechP1Views.ofReview(
                reviewService.submit(UserContext.currentUserId(request), projectId, body)));
    }
}

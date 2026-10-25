package com.lanlink.shopping.module.campaign.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.CouponQuoteDTO;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统一活动系统 API（用户端 + 商户端）
 *  - GET  /campaign/list                生效活动列表（tab: all/coupon/promo/incentive；公开浏览）
 *  - GET  /campaign/detail/{id}         单个活动详情（公开浏览）
 *  - POST /campaign/{id}/claim          领取优惠券（登录）
 *  - POST /campaign/{id}/join           参与激励活动（登录）
 *  - GET  /campaign/my/coupons          我的券包（登录）
 *  - GET  /campaign/my/participations   我的参与记录（登录）
 *  - POST /campaign/coupons/quote       结算可用券试算（登录；金额由服务端按购物车重算）
 *  - GET  /campaign/merchant/list       商户端活动列表（报名/激励进度）
 *  - POST /campaign/merchant/{id}/join  商户报名活动
 *  - POST /campaign/merchant/{id}/claim-reward 商户激励领奖
 */
@RestController
@RequestMapping("/campaign")
public class CampaignController {

    private final CampaignService campaignService;
    private final PricingFacade pricingFacade;

    public CampaignController(CampaignService campaignService, PricingFacade pricingFacade) {
        this.campaignService = campaignService;
        this.pricingFacade = pricingFacade;
    }

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String tab,
                                             HttpServletRequest request) {
        return R.ok(campaignService.userOngoing(UserContext.currentUserId(request), tab));
    }

    /** 单个活动详情（未生效/不存在返回 data=null，前端按"已结束"处理） */
    @GetMapping("/detail/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id, HttpServletRequest request) {
        return R.ok(campaignService.detail(UserContext.currentUserId(request), id));
    }

    @PostMapping("/{id}/claim")
    public R<Void> claim(@PathVariable Long id, HttpServletRequest request) {
        campaignService.claimCoupon(UserContext.currentUserId(request), id);
        return R.ok("领取成功，可在结算时使用", null);
    }

    @PostMapping("/{id}/join")
    public R<Void> join(@PathVariable Long id, HttpServletRequest request) {
        campaignService.joinIncentive(UserContext.currentUserId(request), id);
        return R.ok("参与成功，奖励积分已到账", null);
    }

    @GetMapping("/my/coupons")
    public R<List<Map<String, Object>>> myCoupons(HttpServletRequest request) {
        return R.ok(campaignService.myCoupons(UserContext.currentUserId(request)));
    }

    @GetMapping("/my/participations")
    public R<List<Map<String, Object>>> myParticipations(HttpServletRequest request) {
        return R.ok(campaignService.myParticipations(UserContext.currentUserId(request)));
    }

    /** 结算可用券试算：金额与行业由 PricingFacade 按购物车服务端重算，不信任前端金额 */
    @PostMapping("/coupons/quote")
    public R<List<Map<String, Object>>> quoteCoupons(@RequestBody CouponQuoteDTO dto,
                                                     HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        List<Long> cartIds = dto == null ? null : dto.getCartIds();
        return R.ok(pricingFacade.quoteCoupons(userId, cartIds));
    }

    // ===== 商户端：活动报名 + 激励 =====

    @RequirePerm("merchant:manage")
    @GetMapping("/merchant/list")
    public R<List<Map<String, Object>>> merchantList(HttpServletRequest request) {
        return R.ok(campaignService.merchantCampaigns(UserContext.currentUserId(request)));
    }

    @RequirePerm("merchant:manage")
    @PostMapping("/merchant/{id}/join")
    public R<Void> merchantJoin(@PathVariable Long id, HttpServletRequest request) {
        campaignService.merchantJoin(UserContext.currentUserId(request), id);
        return R.ok("报名成功", null);
    }

    @RequirePerm("merchant:manage")
    @PostMapping("/merchant/{id}/claim-reward")
    public R<Void> merchantClaimReward(@PathVariable Long id, HttpServletRequest request) {
        campaignService.merchantClaimReward(UserContext.currentUserId(request), id);
        return R.ok("激励奖励已发放至积分账户", null);
    }
}

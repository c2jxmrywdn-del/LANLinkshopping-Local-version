package com.lanlink.shopping.module.campaign.controller;

import com.lanlink.shopping.common.R;
import com.lanlink.shopping.config.UserContext;
import com.lanlink.shopping.dto.CouponQuoteDTO;
import com.lanlink.shopping.dto.MerchantPromoDTO;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.integration.security.RequirePerm;
import com.lanlink.shopping.module.campaign.dto.CampaignView;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.campaign.service.PricingPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统一活动系统 API（用户端 + 商户端）
 *  - GET  /campaign/list                生效活动列表（tab: all/coupon/promo/incentive；公开浏览；promo 混入商户促销）
 *  - GET  /campaign/detail/{id}         单个活动详情（公开浏览）
 *  - GET  /campaign/for-product/{prodId} 商品详情页促销（公开浏览：平台 + 商户，带商户名）
 *  - POST /campaign/{id}/claim          领取优惠券（登录）
 *  - POST /campaign/{id}/join           参与激励活动（登录）
 *  - GET  /campaign/my/coupons          我的券包（登录）
 *  - GET  /campaign/my/participations   我的参与记录（登录）
 *  - POST /campaign/coupons/quote       结算可用券试算（登录；金额由服务端按购物车重算）
 *  - GET  /campaign/pricing/preview     下单前促销预览（登录；quote 只读包装，与下单同参同果）
 *  - GET  /campaign/cart-hint           购物车满减差额凑单提示（登录；只读）
 *  - POST /campaign/merchant/create     商户创建店铺促销（待平台审核）
 *  - GET  /campaign/merchant/my         商户自建促销列表
 *  - POST /campaign/merchant/{id}/toggle 商户促销上/下架切换（上架需审核通过）
 *  - GET  /campaign/merchant/list       商户端活动列表（报名/激励进度）
 *  - POST /campaign/merchant/{id}/join  商户报名活动
 *  - POST /campaign/merchant/{id}/claim-reward 商户激励领奖
 */
@RestController
@RequestMapping("/campaign")
public class CampaignController {

    private final CampaignService campaignService;
    private final PricingFacade pricingFacade;
    private final PricingPreviewService pricingPreviewService;

    public CampaignController(CampaignService campaignService, PricingFacade pricingFacade,
                              PricingPreviewService pricingPreviewService) {
        this.campaignService = campaignService;
        this.pricingFacade = pricingFacade;
        this.pricingPreviewService = pricingPreviewService;
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

    /** 商品详情页促销（公开）：生效中命中该商品的促销（平台 all/ind + 商户 prod），带商户名称 */
    @GetMapping("/for-product/{prodId}")
    public R<List<Map<String, Object>>> forProduct(@PathVariable Long prodId) {
        return R.ok(campaignService.promosForProduct(prodId));
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

    // ===== 领券中心（F1 两端点，设计稿 D4）：补齐路由把「券池空态」与「营销空态」分开 =====

    /** 券池聚合（type=coupon，含已领数量与剩余配额）：登录态；空池返回空数组，前端按营销空态渲染 */
    @GetMapping("/coupons/center")
    public R<List<Map<String, Object>>> couponCenter(HttpServletRequest request) {
        return R.ok(campaignService.couponCenter(UserContext.currentUserId(request)));
    }

    /** 批量领取（一键领取）：循环复用单券原子领取，逐券 {campaignId, ok, reason}，部分成功可接受 */
    @PostMapping("/coupons/batch-claim")
    public R<List<Map<String, Object>>> batchClaim(@RequestBody CouponQuoteDTO dto,
                                                   HttpServletRequest request) {
        Long userId = UserContext.currentUserId(request);
        List<Long> ids = dto == null ? null : dto.getIds();
        return R.ok("领取完成", campaignService.batchClaim(userId, ids));
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

    /**
     * 下单前促销预览（登录）：PricingFacade.quote 的只读包装，与下单同参同果。
     * 金额/行业/行快照服务端重算，返回 {original, promoDiscount, promoTitle, memberDiscount, source, estimateFinal}。
     * estimateFinal 为促销/会员互斥取优后的预估应付，不含优惠券与积分抵现。
     */
    @GetMapping("/pricing/preview")
    public R<Map<String, Object>> pricingPreview(@RequestParam(required = false) List<Long> cartIds,
                                                 HttpServletRequest request) {
        return R.ok(pricingPreviewService.preview(UserContext.currentUserId(request), cartIds));
    }

    /**
     * 购物车满减差额凑单提示（登录）：未达门槛的平台级 full_reduce 中差额最小者。
     * 返回 {gap, threshold, benefitAmount, campaignTitle}，无可用提示时 data 为 null。
     */
    @GetMapping("/cart-hint")
    public R<PricingFacade.CartHint> cartHint(@RequestParam(required = false) List<Long> cartIds,
                                              HttpServletRequest request) {
        return R.ok(pricingFacade.cartHint(UserContext.currentUserId(request), cartIds));
    }

    // ===== 商户端：自建促销（平台审核制） =====

    /** 商户创建店铺促销（满减/折扣/秒杀，商品须属本商户；提交后待平台审核，审核通过方可上架） */
    @RequirePerm("merchant:manage")
    @PostMapping("/merchant/create")
    public R<CampaignView> merchantCreate(@RequestBody MerchantPromoDTO dto, HttpServletRequest request) {
        return R.ok("已提交，等待平台审核",
                CampaignView.of(campaignService.merchantCreate(UserContext.currentUserId(request), dto)));
    }

    /** 本商户自建促销列表（含全部字段与审核状态） */
    @RequirePerm("merchant:manage")
    @GetMapping("/merchant/my")
    public R<List<CampaignView>> merchantMy(HttpServletRequest request) {
        return R.ok(campaignService.merchantMy(UserContext.currentUserId(request)).stream()
                .map(CampaignView::of).toList());
    }

    /** 本商户促销上/下架切换（越权拒绝；上架要求平台审核通过），返回 {status: 新status} */
    @RequirePerm("merchant:manage")
    @PostMapping("/merchant/{id}/toggle")
    public R<Map<String, Object>> merchantToggle(@PathVariable Long id, HttpServletRequest request) {
        int status = campaignService.merchantToggle(UserContext.currentUserId(request), id);
        return R.ok(status == 1 ? "活动已上架" : "活动已下架", Map.of("status", status));
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

package com.lanlink.shopping.integration.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.entity.Cart;
import com.lanlink.shopping.entity.Product;
import com.lanlink.shopping.mapper.CartMapper;
import com.lanlink.shopping.mapper.ProductMapper;
import com.lanlink.shopping.module.campaign.entity.Campaign;
import com.lanlink.shopping.module.campaign.mapper.CampaignMapper;
import com.lanlink.shopping.module.campaign.service.CampaignService;
import com.lanlink.shopping.module.membership.service.MembershipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结算价格编排（营销中台集成 Facade）。
 *
 * 冲突防止策略（明确互斥规则，杜绝叠加套利）：
 *  - 促销优惠（满减/折扣/限时秒杀，全场或行业限定）与会员等级折扣 二选一，取对用户更优惠者；
 *  - 两者不叠加；同一类型多规则时取最优（CampaignService 内部已取最大优惠）；
 *  - 优惠券在上述最优价基础上核销（券面值受"券前应付"封顶，保证应付 ≥ 0）。
 *
 * 模块隔离：本 Facade 是唯一同时感知活动模块与会员模块的组件，
 * 订单系统只依赖 PricingFacade，不直接触碰任何营销模块。
 */
@Component
public class PricingFacade {

    /** 促销优惠来源标识 */
    public static final String SOURCE_PROMOTION = "promotion";
    /** 会员折扣来源标识 */
    public static final String SOURCE_MEMBER = "member";

    private final CampaignService campaignService;
    private final MembershipService membershipService;
    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    /** 活动表只读访问：仅用于凑单提示（cartHint）的未达门槛满减查询，不参与计价取优 */
    private final CampaignMapper campaignMapper;

    /** 生产构造（Spring 装配使用） */
    @Autowired
    public PricingFacade(CampaignService campaignService, MembershipService membershipService,
                         CartMapper cartMapper, ProductMapper productMapper, CampaignMapper campaignMapper) {
        this.campaignService = campaignService;
        this.membershipService = membershipService;
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
        this.campaignMapper = campaignMapper;
    }

    /** 兼容构造：既有单测 4 参口径（无活动 Mapper 时凑单提示降级返回 null） */
    public PricingFacade(CampaignService campaignService, MembershipService membershipService,
                         CartMapper cartMapper, ProductMapper productMapper) {
        this(campaignService, membershipService, cartMapper, productMapper, null);
    }

    /**
     * 计算订单应付金额（不含优惠券；券在订单事务内由 CampaignService.applyCouponForOrder 核销）。
     * @param userId        用户（会员折扣按等级读取）
     * @param orderAmount   商品原价合计
     * @param orderIndId    订单所属行业（行业限定促销用）
     * @param memberEligible 是否享有会员折扣（仅采购方身份；商户/管理员不参与）
     */
    public Quote quote(Long userId, BigDecimal orderAmount, Long orderIndId, boolean memberEligible) {
        return quote(userId, orderAmount, orderIndId, memberEligible, null);
    }

    /**
     * 行感知计价重载：订单行（商品/商户/行金额）参与商户促销（scope=prod）计价；
     * 平台促销 vs 会员折扣互斥取优口径不变。
     * @param lines 订单行快照（可空=无行信息，商户促销跳过）
     */
    public Quote quote(Long userId, BigDecimal orderAmount, Long orderIndId, boolean memberEligible,
                       List<CampaignService.Line> lines) {
        BigDecimal amount = orderAmount == null ? BigDecimal.ZERO : orderAmount;
        // 无行信息时走原口径（CampaignService 内部等价委托行感知计价），保持既有调用语义
        BigDecimal promoDiscount;
        String appliedTitle = null;
        if (lines == null || lines.isEmpty()) {
            promoDiscount = campaignService.calculateDiscount(amount, orderIndId);
        } else {
            CampaignService.PromoResult promo = campaignService.calculateDiscountDetailed(amount, orderIndId, lines);
            promoDiscount = promo == null ? BigDecimal.ZERO : promo.getDiscount();
            appliedTitle = promo == null ? null : promo.getAppliedTitle();
        }
        BigDecimal memberDiscount = BigDecimal.ZERO;
        if (memberEligible) {
            BigDecimal rate = membershipService.levelDiscountRate(userId);
            if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) < 0) {
                memberDiscount = amount.multiply(BigDecimal.ONE.subtract(rate))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        return pickBest(amount, promoDiscount, memberDiscount, appliedTitle);
    }

    /**
     * 结算可用券试算：按购物车行服务端重算金额与行业（与下单口径一致，不信任前端金额）。
     * 行快照（商品/商户/行金额）同步传给店铺券（scope=prod）过滤。
     * @param cartIds 结算购物车行ID（空则按"已勾选"口径）
     */
    public List<Map<String, Object>> quoteCoupons(Long userId, List<Long> cartIds) {
        List<Cart> carts = loadCarts(userId, cartIds);
        if (carts.isEmpty()) return List.of();
        BigDecimal total = BigDecimal.ZERO;
        Long indId = null;
        List<CampaignService.Line> lines = new ArrayList<>();
        for (Cart c : carts) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null || p.getPrice() == null || p.getPrice().signum() <= 0) continue;
            // 行金额必须与下单同源（阶梯价按数量取单价），否则凑单提示/券门槛会与实际应付漂移
            BigDecimal lineAmount = TierPricing.resolve(p.getPrice(), p.getTierPriceJson(), c.getQuantity())
                    .unitPrice().multiply(BigDecimal.valueOf(c.getQuantity()));
            total = total.add(lineAmount);
            lines.add(new CampaignService.Line(p.getProdId(), p.getMerId(), lineAmount));
            if (indId == null) indId = p.getIndId();
        }
        return campaignService.quoteCoupons(userId, total, indId, lines);
    }

    /**
     * 购物车行服务端重算（total/indId/行快照），口径与 {@link #quoteCoupons} 同款：
     * 金额与行业一律按商品当前价重算，不信任前端；行快照供商户促销（scope=prod）计价。
     * 供促销预览（PricingPreviewService，与下单同参同果）与凑单提示（cartHint）共用。
     * @return null 表示无可结算购物车行（与 quoteCoupons 空车提前返回口径一致）
     */
    public CartTotals recalcCart(Long userId, List<Long> cartIds) {
        List<Cart> carts = loadCarts(userId, cartIds);
        if (carts.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        Long indId = null;
        List<CampaignService.Line> lines = new ArrayList<>();
        for (Cart c : carts) {
            Product p = productMapper.selectById(c.getProdId());
            if (p == null || p.getPrice() == null || p.getPrice().signum() <= 0) continue;
            // 行金额必须与下单同源（阶梯价按数量取单价），否则凑单提示/券门槛会与实际应付漂移
            BigDecimal lineAmount = TierPricing.resolve(p.getPrice(), p.getTierPriceJson(), c.getQuantity())
                    .unitPrice().multiply(BigDecimal.valueOf(c.getQuantity()));
            total = total.add(lineAmount);
            lines.add(new CampaignService.Line(p.getProdId(), p.getMerId(), lineAmount));
            if (indId == null) indId = p.getIndId();
        }
        return new CartTotals(total, indId, lines);
    }

    /**
     * 购物车凑单提示（只读）：在「未达门槛」的平台级 full_reduce 中找「门槛-当前差额」最小者。
     * 不新增促销类型、不改变生效口径——已达门槛的活动其优惠本就由 quote 自动应用，不做提示。
     * 生效口径镜像 CampaignService.effectiveCampaigns（上架 + 审核通过 + 时间窗内；CampaignService
     * 归促销链路独占修改，此处仅只读镜像，两侧需同步维护）。
     * @return 差额最小者；无可结算行 / 无未达门槛满减 / 无活动模块访问能力时返回 null
     */
    public CartHint cartHint(Long userId, List<Long> cartIds) {
        if (campaignMapper == null) return null; // 降级：无活动模块访问能力（兼容构造）不给提示
        CartTotals totals = recalcCart(userId, cartIds);
        if (totals == null) return null;
        LocalDateTime now = LocalDateTime.now();
        List<Campaign> fullReduces = campaignMapper.selectList(Wrappers.<Campaign>lambdaQuery()
                .eq(Campaign::getStatus, 1)
                .eq(Campaign::getReviewStatus, 1)
                .eq(Campaign::getType, CampaignService.TYPE_FULL_REDUCE)
                .isNull(Campaign::getMerId)
                .and(w -> w.isNull(Campaign::getStartTime).or().le(Campaign::getStartTime, now))
                .and(w -> w.isNull(Campaign::getEndTime).or().ge(Campaign::getEndTime, now))
                .orderByDesc(Campaign::getCampaignId));
        CartHint best = null;
        for (Campaign c : fullReduces) {
            // 行业限定促销须与购物车行业匹配（与 calculateDiscountDetailed 的 c.applicable 同口径）
            if (!c.applicable(totals.getIndId())) continue;
            BigDecimal threshold = c.getThreshold() == null ? BigDecimal.ZERO : c.getThreshold();
            if (totals.getTotal().compareTo(threshold) >= 0) continue; // 已达门槛：无需凑单
            BigDecimal benefit = c.getBenefitAmount(); // 满减为固定优惠额（promoBenefit 同口径）
            if (benefit == null || benefit.signum() <= 0) continue; // 无有效优惠额不作凑单目标
            BigDecimal gap = threshold.subtract(totals.getTotal());
            // 主口径：差额最小；同差额取可减金额更大者（对用户更优）
            if (best == null || gap.compareTo(best.getGap()) < 0
                    || (gap.compareTo(best.getGap()) == 0 && benefit.compareTo(best.getBenefitAmount()) > 0)) {
                best = new CartHint(gap, threshold, benefit, c.getTitle());
            }
        }
        return best;
    }

    private List<Cart> loadCarts(Long userId, List<Long> cartIds) {
        if (cartIds != null && !cartIds.isEmpty()) {
            return cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                    .eq(Cart::getUserId, userId).in(Cart::getCartId, cartIds));
        }
        return cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId).eq(Cart::getChecked, 1));
    }

    private Quote pickBest(BigDecimal amount, BigDecimal promoDiscount, BigDecimal memberDiscount, String appliedTitle) {
        // 互斥取优：优惠更大者生效，另一来源归零（防止叠加）；appliedTitle 随促销生效来源
        if (promoDiscount.compareTo(memberDiscount) >= 0) {
            return new Quote(amount, promoDiscount, BigDecimal.ZERO, SOURCE_PROMOTION, appliedTitle);
        }
        return new Quote(amount, BigDecimal.ZERO, memberDiscount, SOURCE_MEMBER, null);
    }

    /** 购物车行服务端重算结果（金额/行业/行快照；quoteCoupons、促销预览、凑单提示共用口径） */
    public static class CartTotals {
        private final BigDecimal total;
        private final Long indId;
        private final List<CampaignService.Line> lines;

        public CartTotals(BigDecimal total, Long indId, List<CampaignService.Line> lines) {
            this.total = total;
            this.indId = indId;
            this.lines = lines;
        }

        public BigDecimal getTotal() { return total; }
        public Long getIndId() { return indId; }
        public List<CampaignService.Line> getLines() { return lines; }
    }

    /** 购物车凑单提示：未达门槛的平台满减中差额最小者 */
    public static class CartHint {
        /** 距门槛差额（门槛 - 当前金额） */
        private final BigDecimal gap;
        /** 活动门槛金额 */
        private final BigDecimal threshold;
        /** 达标后可减金额 */
        private final BigDecimal benefitAmount;
        /** 活动标题 */
        private final String campaignTitle;

        public CartHint(BigDecimal gap, BigDecimal threshold, BigDecimal benefitAmount, String campaignTitle) {
            this.gap = gap;
            this.threshold = threshold;
            this.benefitAmount = benefitAmount;
            this.campaignTitle = campaignTitle;
        }

        public BigDecimal getGap() { return gap; }
        public BigDecimal getThreshold() { return threshold; }
        public BigDecimal getBenefitAmount() { return benefitAmount; }
        public String getCampaignTitle() { return campaignTitle; }
    }

    /** 结算报价结果 */
    public static class Quote {
        private final BigDecimal original;
        private final BigDecimal promoDiscount;
        private final BigDecimal memberDiscount;
        private final String source;
        /** 实际生效的促销活动标题（会员折扣生效或无促销时为 null） */
        private final String appliedTitle;

        public Quote(BigDecimal original, BigDecimal promoDiscount, BigDecimal memberDiscount, String source) {
            this(original, promoDiscount, memberDiscount, source, null);
        }

        public Quote(BigDecimal original, BigDecimal promoDiscount, BigDecimal memberDiscount,
                     String source, String appliedTitle) {
            this.original = original;
            this.promoDiscount = promoDiscount;
            this.memberDiscount = memberDiscount;
            this.source = source;
            this.appliedTitle = appliedTitle;
        }

        /** 最终应付金额（保底 ≥0） */
        public BigDecimal finalAmount() {
            BigDecimal total = original.subtract(promoDiscount).subtract(memberDiscount);
            return total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : total;
        }
        /** 实际生效的优惠金额（促销或会员二者之一） */
        public BigDecimal appliedDiscount() {
            return promoDiscount.max(memberDiscount);
        }
        public BigDecimal getOriginal() { return original; }
        public BigDecimal getPromoDiscount() { return promoDiscount; }
        public BigDecimal getMemberDiscount() { return memberDiscount; }
        public String getSource() { return source; }
        public String getAppliedTitle() { return appliedTitle; }
    }
}

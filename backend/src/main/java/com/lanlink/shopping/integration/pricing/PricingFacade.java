package com.lanlink.shopping.integration.pricing;

import com.lanlink.shopping.module.membership.service.MembershipService;
import com.lanlink.shopping.module.promotion.service.PromotionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 结算价格编排（营销中台集成 Facade）。
 *
 * 冲突防止策略（明确互斥规则，杜绝叠加套利）：
 *  - 促销优惠（满减/折扣，全场或行业限定）与会员等级折扣 二选一，取对用户更优惠者；
 *  - 两者不叠加；同一类型多规则时取最优（PromotionService 内部已取最大优惠）。
 *
 * 模块隔离：本 Facade 是唯一同时感知促销模块与会员模块的组件，
 * 订单系统只依赖 PricingFacade，不直接触碰任何营销模块。
 */
@Component
public class PricingFacade {

    /** 促销优惠来源标识 */
    public static final String SOURCE_PROMOTION = "promotion";
    /** 会员折扣来源标识 */
    public static final String SOURCE_MEMBER = "member";

    private final PromotionService promotionService;
    private final MembershipService membershipService;

    public PricingFacade(PromotionService promotionService, MembershipService membershipService) {
        this.promotionService = promotionService;
        this.membershipService = membershipService;
    }

    /**
     * 计算订单应付金额。
     * @param userId        用户（会员折扣按等级读取）
     * @param orderAmount   商品原价合计
     * @param orderIndId    订单所属行业（行业限定促销用）
     * @param memberEligible 是否享有会员折扣（仅采购方身份；商户/管理员不参与）
     */
    public Quote quote(Long userId, BigDecimal orderAmount, Long orderIndId, boolean memberEligible) {
        BigDecimal amount = orderAmount == null ? BigDecimal.ZERO : orderAmount;
        BigDecimal promoDiscount = promotionService.calculateDiscount(amount, orderIndId);
        BigDecimal memberDiscount = BigDecimal.ZERO;
        if (memberEligible) {
            BigDecimal rate = membershipService.levelDiscountRate(userId);
            if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) < 0) {
                memberDiscount = amount.multiply(BigDecimal.ONE.subtract(rate))
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }
        return pickBest(amount, promoDiscount, memberDiscount);
    }

    private Quote pickBest(BigDecimal amount, BigDecimal promoDiscount, BigDecimal memberDiscount) {
        // 互斥取优：优惠更大者生效，另一来源归零（防止叠加）
        if (promoDiscount.compareTo(memberDiscount) >= 0) {
            return new Quote(amount, promoDiscount, BigDecimal.ZERO, SOURCE_PROMOTION);
        }
        return new Quote(amount, BigDecimal.ZERO, memberDiscount, SOURCE_MEMBER);
    }

    /** 结算报价结果 */
    public static class Quote {
        private final BigDecimal original;
        private final BigDecimal promoDiscount;
        private final BigDecimal memberDiscount;
        private final String source;

        public Quote(BigDecimal original, BigDecimal promoDiscount, BigDecimal memberDiscount, String source) {
            this.original = original;
            this.promoDiscount = promoDiscount;
            this.memberDiscount = memberDiscount;
            this.source = source;
        }

        /** 最终应付金额（保底 ≥0） */
        public BigDecimal finalAmount() {
            BigDecimal total = original.subtract(promoDiscount).subtract(memberDiscount);
            return total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : total;
        }
        public BigDecimal getOriginal() { return original; }
        public BigDecimal getPromoDiscount() { return promoDiscount; }
        public BigDecimal getMemberDiscount() { return memberDiscount; }
        public String getSource() { return source; }
    }
}

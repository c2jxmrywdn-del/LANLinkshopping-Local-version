package com.lanlink.shopping.module.campaign.service;

import com.lanlink.shopping.entity.User;
import com.lanlink.shopping.integration.pricing.MemberEligibility;
import com.lanlink.shopping.integration.pricing.PricingFacade;
import com.lanlink.shopping.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 促销预览服务（结算优惠可感知闭环）：{@link PricingFacade#quote} 的只读包装。
 *
 * 口径保证（防预览与实付漂移）：
 *  - 金额/行业/行快照走 {@link PricingFacade#recalcCart} 服务端重算（与下单、券试算同源），
 *    不信任前端金额；
 *  - 会员资格判定走共用工具 {@link MemberEligibility}（下单同款实现，禁止两处各写一份）；
 *  - 计价走 {@link PricingFacade#quote} 本体——预览与下单对同一入参必然同果，互斥取优
 *    （促销 vs 会员二选一、券另算）逻辑零复制。
 *
 * 只读承诺：不写任何状态、不核销任何优惠、不产生积分/成长值变动。
 */
@Service
public class PricingPreviewService {

    private final PricingFacade pricingFacade;
    private final UserMapper userMapper;

    public PricingPreviewService(PricingFacade pricingFacade, UserMapper userMapper) {
        this.pricingFacade = pricingFacade;
        this.userMapper = userMapper;
    }

    /**
     * 下单前促销预览。
     * @param userId  用户
     * @param cartIds 结算购物车行ID（空则按"已勾选"口径，与下单一致）
     * @return original 商品原价合计 / promoDiscount 促销优惠 / promoTitle 生效促销标题
     *         （会员折扣生效或无促销时为 null）/ memberDiscount 会员折扣 / source 生效来源
     *         （promotion|member）/ estimateFinal 预估应付（不含优惠券与积分抵现）
     */
    public Map<String, Object> preview(Long userId, List<Long> cartIds) {
        PricingFacade.CartTotals totals = pricingFacade.recalcCart(userId, cartIds);
        if (totals == null) return emptyPreview();
        // 会员资格与下单共用同一判定实现（MemberEligibility），防止两处口径漂移
        User user = userId == null ? null : userMapper.selectById(userId);
        boolean memberEligible = MemberEligibility.isMemberEligible(user);
        PricingFacade.Quote quote = pricingFacade.quote(userId, totals.getTotal(), totals.getIndId(),
                memberEligible, totals.getLines());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("original", quote.getOriginal());
        m.put("promoDiscount", quote.getPromoDiscount());
        m.put("promoTitle", quote.getAppliedTitle());
        m.put("memberDiscount", quote.getMemberDiscount());
        m.put("source", quote.getSource());
        m.put("estimateFinal", quote.finalAmount());
        return m;
    }

    /** 空购物车口径：全零报价（无可结算行时不调用计价链，前端按无优惠展示） */
    private Map<String, Object> emptyPreview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("original", BigDecimal.ZERO);
        m.put("promoDiscount", BigDecimal.ZERO);
        m.put("promoTitle", null);
        m.put("memberDiscount", BigDecimal.ZERO);
        m.put("source", null);
        m.put("estimateFinal", BigDecimal.ZERO);
        return m;
    }
}

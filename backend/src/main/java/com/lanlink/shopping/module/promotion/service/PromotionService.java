package com.lanlink.shopping.module.promotion.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lanlink.shopping.module.promotion.entity.Promotion;
import com.lanlink.shopping.module.promotion.mapper.PromotionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 促销系统服务（营销中台·独立模块）。
 * 计算口径对外只暴露 calculateDiscount()，由集成层 PricingFacade 统一编排，
 * 促销模块不感知订单/会员细节，保证模块隔离。
 */
@Service
public class PromotionService {

    private final PromotionMapper promotionMapper;

    public PromotionService(PromotionMapper promotionMapper) {
        this.promotionMapper = promotionMapper;
    }

    /** 当前生效的促销（启用 且 在时间窗内） */
    public List<Promotion> activeNow() {
        LocalDateTime now = LocalDateTime.now();
        return promotionMapper.selectList(Wrappers.<Promotion>lambdaQuery()
                .eq(Promotion::getStatus, 1)
                .and(w -> w.isNull(Promotion::getStartTime).or().le(Promotion::getStartTime, now))
                .and(w -> w.isNull(Promotion::getEndTime).or().ge(Promotion::getEndTime, now))
                .orderByDesc(Promotion::getPromoId));
    }

    /**
     * 计算促销优惠金额（满减/折扣；对不符合门槛或行业的返回 0）。
     * @param orderAmount  订单原价总额
     * @param orderIndId   订单所属行业（用于行业限定促销）
     * @return 优惠金额（≥0）
     */
    public BigDecimal calculateDiscount(BigDecimal orderAmount, Long orderIndId) {
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal best = BigDecimal.ZERO;
        LocalDateTime now = LocalDateTime.now();
        for (Promotion p : activeNow()) {
            // 防御性校验（正常由 activeNow 过滤，此处防止并发/脏数据影响）
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            if (p.getStartTime() != null && p.getStartTime().isAfter(now)) continue;
            if (p.getEndTime() != null && p.getEndTime().isBefore(now)) continue;
            if (!p.applicable(orderIndId)) continue;
            if (orderAmount.compareTo(p.getThreshold()) < 0) continue;
            BigDecimal benefit = BigDecimal.ZERO;
            if ("full_reduce".equals(p.getType()) && p.getBenefitAmount() != null) {
                benefit = p.getBenefitAmount();
            } else if ("discount".equals(p.getType()) && p.getDiscountRate() != null
                    && p.getDiscountRate().compareTo(BigDecimal.ZERO) > 0
                    && p.getDiscountRate().compareTo(BigDecimal.ONE) < 0) {
                benefit = orderAmount.multiply(BigDecimal.ONE.subtract(p.getDiscountRate()))
                        .setScale(2, RoundingMode.HALF_UP);
            }
            if (benefit.compareTo(best) > 0) best = benefit;
        }
        return best;
    }

    /** 促销列表（前台展示） */
    public List<Promotion> list() {
        return activeNow();
    }
}

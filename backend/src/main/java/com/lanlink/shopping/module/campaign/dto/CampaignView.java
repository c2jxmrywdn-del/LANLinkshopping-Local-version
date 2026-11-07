package com.lanlink.shopping.module.campaign.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lanlink.shopping.module.campaign.entity.Campaign;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活动响应契约。字段与实体序列化保持一致（含派生的 promoType），实体不出模块。
 */
public record CampaignView(Long campaignId, String title, String type, String description, String accent,
                           LocalDateTime startTime, LocalDateTime endTime, Integer status,
                           BigDecimal threshold, BigDecimal benefitAmount, BigDecimal discountRate,
                           String scope, Long indId, Integer perUserLimit, Integer totalQuota, Integer claimedCount,
                           Integer rewardPoints, BigDecimal merTargetAmount, Integer merRewardPoints,
                           Integer joinable, Long merId, String prodIds, Integer reviewStatus, String reviewNote,
                           LocalDateTime createTime, LocalDateTime updateTime, Boolean promoType) {

    public static CampaignView of(Campaign e) {
        return new CampaignView(e.getCampaignId(), e.getTitle(), e.getType(), e.getDescription(), e.getAccent(),
                e.getStartTime(), e.getEndTime(), e.getStatus(), e.getThreshold(), e.getBenefitAmount(),
                e.getDiscountRate(), e.getScope(), e.getIndId(), e.getPerUserLimit(), e.getTotalQuota(),
                e.getClaimedCount(), e.getRewardPoints(), e.getMerTargetAmount(), e.getMerRewardPoints(),
                e.getJoinable(), e.getMerId(), e.getProdIds(), e.getReviewStatus(), e.getReviewNote(),
                e.getCreateTime(), e.getUpdateTime(), e.isPromoType());
    }

    /** 分页视图：保留 Page 既有字段，并兼容 page 键 */
    public static Map<String, Object> page(Page<Campaign> page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", page.getRecords().stream().map(CampaignView::of).toList());
        out.put("total", page.getTotal());
        out.put("size", page.getSize());
        out.put("current", page.getCurrent());
        out.put("pages", page.getPages());
        out.put("orders", page.orders());
        out.put("optimizeCountSql", page.optimizeCountSql());
        out.put("searchCount", page.searchCount());
        out.put("countId", page.countId());
        out.put("maxLimit", page.maxLimit());
        out.put("page", page.getCurrent());
        return out;
    }
}

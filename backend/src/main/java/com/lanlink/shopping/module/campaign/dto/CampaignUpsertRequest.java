package com.lanlink.shopping.module.campaign.dto;

import com.lanlink.shopping.module.campaign.entity.Campaign;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 平台活动创建/更新入参（Controller 不再直接接收实体） */
public record CampaignUpsertRequest(String title, String type, String description, String accent,
                                    LocalDateTime startTime, LocalDateTime endTime, Integer status,
                                    BigDecimal threshold, BigDecimal benefitAmount, BigDecimal discountRate,
                                    String scope, Long indId, Integer perUserLimit, Integer totalQuota,
                                    Integer rewardPoints, BigDecimal merTargetAmount, Integer merRewardPoints,
                                    Integer joinable, Long merId, String prodIds,
                                    Integer reviewStatus, String reviewNote) {

    public Campaign toEntity() {
        Campaign c = new Campaign();
        c.setTitle(title);
        c.setType(type);
        c.setDescription(description);
        c.setAccent(accent);
        c.setStartTime(startTime);
        c.setEndTime(endTime);
        c.setStatus(status);
        c.setThreshold(threshold);
        c.setBenefitAmount(benefitAmount);
        c.setDiscountRate(discountRate);
        c.setScope(scope);
        c.setIndId(indId);
        c.setPerUserLimit(perUserLimit);
        c.setTotalQuota(totalQuota);
        c.setRewardPoints(rewardPoints);
        c.setMerTargetAmount(merTargetAmount);
        c.setMerRewardPoints(merRewardPoints);
        c.setJoinable(joinable);
        c.setMerId(merId);
        c.setProdIds(prodIds);
        c.setReviewStatus(reviewStatus);
        c.setReviewNote(reviewNote);
        return c;
    }
}

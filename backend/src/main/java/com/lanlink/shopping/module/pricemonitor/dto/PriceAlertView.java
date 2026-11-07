package com.lanlink.shopping.module.pricemonitor.dto;

import com.lanlink.shopping.module.pricemonitor.entity.PriceAlert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格预警订阅响应契约。字段与实体序列化结果保持一致，实体不出模块。
 */
public record PriceAlertView(Long id, Long userId, Long prodId, String conditionType, BigDecimal threshold,
                             Integer status, LocalDateTime lastTriggeredTime, LocalDateTime createTime) {

    public static PriceAlertView of(PriceAlert e) {
        return new PriceAlertView(e.getId(), e.getUserId(), e.getProdId(), e.getConditionType(), e.getThreshold(),
                e.getStatus(), e.getLastTriggeredTime(), e.getCreateTime());
    }
}

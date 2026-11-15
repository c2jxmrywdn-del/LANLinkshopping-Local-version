package com.lanlink.shopping.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 物流运费模板管理 VO（out/carrier-expansion-design.md §4.2）：
 * 暴露 tierMode/tierRuleJson/vasRuleJson/minCharge 供管理端结构化编辑。
 */
public class LogisticsTemplateAdminDTO {

    @Data
    public static class TemplateVO {
        private Long templateId;
        private String templateName;
        private Long providerId;
        private String pricingType;
        private String tierMode;
        private BigDecimal firstWeight;
        private BigDecimal firstPrice;
        private BigDecimal continuedWeight;
        private BigDecimal continuedPrice;
        private BigDecimal volumeFactor;
        private BigDecimal freeThreshold;
        private String tierRuleJson;
        private String vasRuleJson;
        private BigDecimal minCharge;
        private BigDecimal remoteSurcharge;
        private Integer status;
    }
}

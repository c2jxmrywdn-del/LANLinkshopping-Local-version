package com.lanlink.shopping.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 物流服务商管理 DTO（out/carrier-expansion-design.md §4.2）：
 * 实体 LogisticsProvider.capabilityJson 带 @JsonIgnore（商户端不外发），
 * 管理端读写经本 DTO 打通——list 转出、save 转入，实体注解保持不动。
 */
public class LogisticsProviderAdminDTO {

    /** 管理端服务商列表/编辑 VO：含 capabilityJson 与 SLA 指标 */
    @Data
    public static class ProviderVO {
        private Long providerId;
        private String providerName;
        private String providerCode;
        private String contactName;
        private String contactPhone;
        private String apiType;
        private String apiEndpoint;
        /** 能力标记 JSON（scope/heavyCargo/pallet/install/guaranteed/costLevel/routeNotes 等统一口径） */
        private String capabilityJson;
        private Integer status;
        private String settleCycle;
        private Integer avgEtaHours;
        private Integer serviceScore;
        private Double slaRate;
        private Integer sort;
    }

    /** 管理端服务商保存请求 */
    @Data
    public static class ProviderSaveRequest {
        private Long providerId;
        private String providerName;
        private String providerCode;
        private String contactName;
        private String contactPhone;
        private String apiType;
        private String apiEndpoint;
        private String capabilityJson;
        /** 脱敏密钥透传（带星号时服务端保留库内真实值） */
        private String apiKey;
        private Integer status;
        private String settleCycle;
        private Integer avgEtaHours;
        private Integer serviceScore;
        private Double slaRate;
        private Integer sort;
    }

    /** 管理端运费模板保存请求（tierMode/tierRuleJson/vasRuleJson/minCharge 结构化编辑） */
    @Data
    public static class TemplateSaveRequest {
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

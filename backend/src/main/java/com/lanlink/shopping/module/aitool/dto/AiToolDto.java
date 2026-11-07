package com.lanlink.shopping.module.aitool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商城 AI 工具网关数据传输对象（DTO / VO）
 * 包含 6 大电商核心工具的输入与输出契约，兼容 OpenAI Function Calling 与 Anthropic MCP 标准。
 */
public final class AiToolDto {

    private AiToolDto() {}

    // ==================== 1. 商品与现货检索 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchProductsRequest {
        @NotBlank(message = "搜索关键词不能为空")
        private String keyword;
        private Long catId;
        private Long indId;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        @Builder.Default
        private Boolean inStockOnly = true;
        @Builder.Default
        private String sort = "sales"; // sales, priceAsc, priceDesc, newest
        @Builder.Default
        private Integer limit = 5;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSummaryVo {
        private Long prodId;
        private String title;
        private String brand;
        private String spec;
        private BigDecimal price;
        private Integer stock;
        private String stockStatus; // IN_STOCK, LOW_STOCK, OUT_OF_STOCK
        private Integer sales;
        private String coverUrl;
        private String catName;
        private String merchantName;
        private Boolean verifiedSupplier;
        private Boolean hasTierPricing;
        private Integer moq;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchProductsResponse {
        private Long total;
        private List<ProductSummaryVo> items;
        private String keyword;
    }

    // ==================== 2. 商品规格与阶梯价详情 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductDetailRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierPriceItemVo {
        private Integer minQty;
        private BigDecimal unitPrice;
        private BigDecimal discountRate; // 折扣比率，如 0.90 表示 9 折
        private String desc;             // 如 "≥100件: ¥45.00/件 (省10%)"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplierInfoVo {
        private Long merchantId;
        private String enterpriseName;
        private String certification;
        private Boolean isDirectManufacturer;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcurementPolicyVo {
        private String vatInvoice;       // "支持13%增值税专用发票开具"
        private String warranty;         // "365天官方质保，原厂MTC质保书"
        private String shippingPolicy;   // "48小时内顺丰/德邦专线发货"
        private String returnPolicy;     // "材质不符假一赔十，入库7天无损退换"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductDetailResponse {
        private Long prodId;
        private String title;
        private String brand;
        private String spec;
        private BigDecimal basePrice;
        private Integer stock;
        private String stockStatus;
        private Integer moq;
        private String unit;
        private String coverUrl;
        private String detail;
        private List<TierPriceItemVo> tierPricing;
        private SupplierInfoVo supplier;
        private ProcurementPolicyVo policy;
    }

    // ==================== 3. 动态阶梯价与税费试算 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateQuotationRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "采购数量不能为空")
        @Min(value = 1, message = "采购数量必须大于0")
        private Integer quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalculateQuotationResponse {
        private Long prodId;
        private String title;
        private Integer quantity;
        private Integer moq;
        private Boolean meetsMoq;
        private BigDecimal originalUnitPrice;
        private BigDecimal effectiveUnitPrice;
        private String appliedTierDesc;
        private BigDecimal goodsTotalAmount;
        private BigDecimal estimatedFreight;
        private BigDecimal estimatedVatTax;
        private BigDecimal finalTotalAmount;
        private BigDecimal totalSavings;
        private String quotationSummary;
    }

    // ==================== 4. 生成采购草稿与结算深链（人在回路） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCartDraftRequest {
        @NotNull(message = "商品ID不能为空")
        private Long productId;

        @NotNull(message = "采购数量不能为空")
        @Min(value = 1, message = "采购数量必须大于0")
        private Integer quantity;

        private Long userId;
        private String userAuthToken;
        private String clientIp;
        private String remark;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCartDraftResponse {
        private String draftId;
        private Long prodId;
        private String title;
        private Integer quantity;
        private BigDecimal effectiveUnitPrice;
        private BigDecimal finalTotalAmount;
        private String checkoutDeepLink;
        private Integer expireMinutes;
        private String status; // PENDING_USER_CONFIRMATION
        private Boolean confirmationRequired;
        private String instruction;
    }

    // ==================== 5. 政策与采购知识库检索 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryKnowledgeRequest {
        @NotBlank(message = "查询问题不能为空")
        private String query;
        @Builder.Default
        private String scope = "all"; // all, policy, invoice, payment, logistics, aftersales
        @Builder.Default
        private Integer topK = 3;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeHitVo {
        private String title;
        private String category;
        private String content;
        private Double relevanceScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryKnowledgeResponse {
        private String query;
        private Integer count;
        private List<KnowledgeHitVo> hits;
        private Boolean noAnswer;
    }

    // ==================== 5.1 统一只读知识检索（外部 LLM tool / MCP 共用） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeSearchRequest {
        @NotBlank(message = "查询问题不能为空")
        private String query;
        @Builder.Default
        private Integer topK = 5;
        @Builder.Default
        private String scope = "all"; // all, product, policy
        private Long catId;
        private Long indId;
        private Long merId;
        @Builder.Default
        private Double minScore = 0.30;
        @Builder.Default
        private Boolean includeLiveData = true;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeSearchHitVo {
        private String docId;
        private String refId;
        private String source;
        private String title;
        private String snippet;
        private Double score;
        private String updatedAt;
        private Map<String, Object> metadata;
        private Map<String, Object> liveData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeSearchResponse {
        private String requestId;
        private String query;
        private Integer count;
        private Boolean noAnswer;
        private String status; // ANSWERED, PARTIAL, NO_ANSWER, FILTERED_EMPTY, RETRIEVAL_FAILED
        private List<KnowledgeSearchHitVo> hits;
        private Long latencyMs;
    }

    // ==================== 6. 物流轨迹与订单跟踪（含 PII 掩码） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackLogisticsRequest {
        @NotBlank(message = "订单编号不能为空")
        private String orderNo;
        private Long userId;
        private String userAuthToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogisticsTraceNodeVo {
        private String time;
        private String location;
        private String status;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrackLogisticsResponse {
        private String orderNo;
        private String orderStatus;
        private String orderStatusDesc;
        private String receiverName;  // 掩码: "张*"
        private String receiverPhone; // 掩码: "138****5678"
        private String destination;   // 掩码: "江苏省苏州市工业园区***"
        private String waybillNo;
        private String carrierName;
        private String expressNo;
        private String currentStatus;
        private List<LogisticsTraceNodeVo> traces;
    }

    // ==================== 7. 采购意向草稿单财务审核（财务审核 Agent） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditProcurementDraftRequest {
        @NotBlank(message = "草稿单ID不能为空")
        private String draftId;
        private Long userId;
        private Long enterpriseId;
        private String userAuthToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditProcurementDraftResponse {
        private String draftId;
        private Long prodId;
        private String title;
        private Integer quantity;
        private BigDecimal finalTotalAmount;
        private BigDecimal effectiveUnitPrice;
        private BigDecimal baseUnitPrice;
        private BigDecimal priceDeviationRate; // 相对基准价偏离度，如 -0.15 (优惠15%)
        private BigDecimal availableCredit;     // 企业可用授信额度
        private Boolean creditSufficient;       // 授信是否充足
        private String riskLevel;               // LOW, MEDIUM, HIGH
        private String auditStatus;             // PASSED, REJECTED, REQUIRES_HUMAN_APPROVAL
        private Boolean requiresHumanApproval;  // 是否需要人工审批介入
        private BigDecimal maxAutoApprovalLimit;// 自动免审阈值 (如 50000.00)
        private String auditRemark;             // 审核结论与风控说明
        private String evaluatedAt;
    }

    // ==================== 8. 采购审批流放行/驳回（财务审核 Agent） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApproveProcurementFlowRequest {
        @NotBlank(message = "草稿单ID不能为空")
        private String draftId;
        @NotNull(message = "审批决定不能为空")
        private Boolean approved;
        private String comment;
        private Long auditorId;
        @Builder.Default
        private String agentRole = "finance_auditor_agent";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApproveProcurementFlowResponse {
        private String draftId;
        private String status;         // APPROVED, REJECTED
        private String approvalToken;  // 放行 Token (appr_...)，供下游支付核验
        private String approvedAt;
        private Integer expireMinutes;
        private String message;
    }

    // ==================== 9. 订单履约时效与SLA监控（履约追踪 Agent） ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeliveryAlertVo {
        private Long alertId;
        private String alertType;  // delay, reject, return, damage, timeout_no_update
        private String alertLevel; // low, medium, high
        private String content;
        private String createTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorDeliverySlaRequest {
        private String orderNo;
        private String waybillNo;
        private Long userId;
        private String userAuthToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitorDeliverySlaResponse {
        private String orderNo;
        private String waybillNo;
        private String slaStatus;  // NORMAL, DELAY_WARNING, CRITICAL_DELAY
        private Long elapsedHours; // 自下单或发货以来已耗时(小时)
        private String estimatedDeliveryTime;
        private String carrierName;
        private String currentStatus;
        private Boolean hasAlert;
        private List<DeliveryAlertVo> alerts;
        private String warningMessage;
        private String suggestedAction;
    }

    // ==================== 通用工具统一调用调度 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenericToolCallRequest {
        @NotBlank(message = "工具名称不能为空")
        private String tool;
        private Map<String, Object> arguments;
        private Long userId;
        private String userAuthToken;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GenericToolCallResponse {
        private String tool;
        private Boolean success;
        private Object data;
        private String error;
        private Long latencyMs;
    }
}

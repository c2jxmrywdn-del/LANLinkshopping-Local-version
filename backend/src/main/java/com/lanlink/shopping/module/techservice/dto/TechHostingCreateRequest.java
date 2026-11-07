package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TechHostingCreateRequest {
    @NotBlank(message = "请选择托管类型")
    private String hostingType;
    private Long targetId;
    private String targetName;
    private Long providerOrgId;
    @NotBlank(message = "请填写托管范围")
    private String scope;
    private LocalDate startDate;
    private LocalDate endDate;
    private String feeMode;
    @DecimalMin(value = "0", message = "托管费用不能为负数")
    private BigDecimal feeAmount;
    @DecimalMin(value = "0", message = "分成比例不能为负数")
    @DecimalMax(value = "100", message = "分成比例不能超过 100%")
    private BigDecimal commissionRate;
    private String activationPlan;
}

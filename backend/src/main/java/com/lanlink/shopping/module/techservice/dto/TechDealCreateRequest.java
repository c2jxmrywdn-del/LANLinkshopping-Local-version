package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TechDealCreateRequest {
    @NotNull(message = "请选择技术成果")
    private Long achievementId;
    @NotBlank(message = "请选择交易方式")
    private String dealType;
    @NotNull(message = "请填写交易金额")
    @DecimalMin(value = "0.01", message = "交易金额必须大于 0")
    private BigDecimal amount;
    private String paymentMode;
    @DecimalMin(value = "0", message = "分成比例不能为负数")
    @DecimalMax(value = "100", message = "分成比例不能超过 100%")
    private BigDecimal commissionRate;
    private Long projectId;
}

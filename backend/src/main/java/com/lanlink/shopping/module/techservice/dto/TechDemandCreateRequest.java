package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TechDemandCreateRequest {
    @NotBlank(message = "需求标题不能为空")
    @Size(max = 128, message = "需求标题不能超过 128 个字符")
    private String title;
    @NotBlank(message = "请选择行业")
    private String industry;
    @NotBlank(message = "请选择技术领域")
    private String techField;
    @NotBlank(message = "请描述技术需求")
    @Size(max = 2000, message = "需求描述不能超过 2000 个字符")
    private String description;
    private String painPoints;
    private String expectedOutcome;
    @DecimalMin(value = "0", message = "预算不能为负数")
    private BigDecimal budgetMin;
    @DecimalMin(value = "0", message = "预算不能为负数")
    private BigDecimal budgetMax;
    private LocalDate deadline;
    private Boolean anonymous;
}

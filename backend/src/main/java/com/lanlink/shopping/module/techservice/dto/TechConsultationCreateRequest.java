package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TechConsultationCreateRequest {
    @NotNull(message = "请选择专家或技术经纪人")
    private Long expertId;
    @NotBlank(message = "请填写咨询主题")
    private String topic;
    private String description;
    private String mode;
    private LocalDateTime scheduledAt;
    @Min(value = 15, message = "咨询时长至少 15 分钟")
    private Integer durationMin;
    @DecimalMin(value = "0", message = "咨询费用不能为负数")
    private BigDecimal amount;
}

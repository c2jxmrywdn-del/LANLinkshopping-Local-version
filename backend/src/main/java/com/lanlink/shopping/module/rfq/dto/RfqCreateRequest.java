package com.lanlink.shopping.module.rfq.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 买家发布询价（数量/截止时间必填；价格由商户在报价环节给出，买家不填价） */
@Data
public class RfqCreateRequest {

    @NotBlank(message = "询价标题不能为空")
    @Size(max = 120, message = "询价标题过长")
    private String title;

    @Size(max = 1000, message = "需求描述过长")
    private String description;

    /** 意向目录商品（可空：仅描述需求，由商户报价时锚定具体商品） */
    private Long prodId;

    @NotNull(message = "采购数量不能为空")
    @DecimalMin(value = "0.01", message = "采购数量需大于 0")
    @Digits(integer = 12, fraction = 2, message = "采购数量格式不正确")
    private BigDecimal quantity;

    @Size(max = 16, message = "计量单位过长")
    private String unit;

    @NotNull(message = "报价截止时间不能为空")
    private LocalDateTime quoteDeadline;
}
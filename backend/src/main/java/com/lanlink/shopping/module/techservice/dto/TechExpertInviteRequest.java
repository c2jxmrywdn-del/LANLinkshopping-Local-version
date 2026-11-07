package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TechExpertInviteRequest {
    @NotBlank(message = "请填写专家姓名")
    private String name;
    @NotBlank(message = "请选择入驻类型")
    private String expertType;
    private String title;
    private String organization;
    private String industries;
    private String domains;
    private String bio;
    private String credentials;
    @DecimalMin(value = "0", message = "咨询费不能为负数")
    private BigDecimal consultFee;
}

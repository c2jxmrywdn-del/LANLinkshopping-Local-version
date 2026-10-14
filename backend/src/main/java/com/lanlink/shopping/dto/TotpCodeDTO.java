package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class TotpCodeDTO {
    @NotBlank(message = "请输入动态验证码")
    @Pattern(regexp = "^\\d{6}$", message = "动态验证码为 6 位数字")
    private String code;
}
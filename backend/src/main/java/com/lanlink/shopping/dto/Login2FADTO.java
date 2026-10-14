package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 两步验证登录：ticket + 动态验证码
 */
@Data
public class Login2FADTO {
    @NotBlank(message = "登录状态已失效，请重新登录")
    private String loginTicket;
    @NotBlank(message = "请输入动态验证码")
    @Pattern(regexp = "^\\d{6}$", message = "动态验证码为 6 位数字")
    private String code;
}
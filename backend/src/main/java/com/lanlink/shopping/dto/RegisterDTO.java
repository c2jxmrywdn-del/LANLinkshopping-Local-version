package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
    @NotBlank(message = "密码不能为空")
    private String password;
    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称不能超过 32 字")
    private String nickname;
    /** 注册角色: buyer 采购方 / merchant 商户 */
    private String roleCode = "buyer";
}

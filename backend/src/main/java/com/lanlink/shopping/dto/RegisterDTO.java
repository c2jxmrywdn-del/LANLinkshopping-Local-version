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
    /**
     * 已废弃：自助注册固定为 buyer，服务端不再读取该字段。
     *
     * <p>保留仅为兼容旧前端请求体（多余字段会被忽略），避免客户端 400。
     * 商户角色必须通过入驻申请（MerchantService）审核后升级。
     */
    @Deprecated
    private String roleCode;

    /**
     * 短信验证码（可选）。
     *
     * <p>未填时保持原有注册行为（兼容既有客户端）；填写时服务端必须校验通过才允许注册。
     * 校验在 {@code AuthController#register} 中完成，避免把验证码逻辑耦合进用户实体服务。
     */
    @Size(min = 6, max = 6, message = "验证码为 6 位数字")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String code;

    /**
     * 邀请码（可选）。
     *
     * <p>由邀请链接 {@code /register?invite=INVxxxx} 带入；服务端在注册成功后校验并绑定邀请关系。
     * 格式约束宽松（只限长度/字符集），非法或过期的邀请码<b>不阻断注册</b>，仅跳过绑定。
     */
    @Size(max = 32, message = "邀请码格式不正确")
    @Pattern(regexp = "^$|^[A-Za-z0-9]{4,32}$", message = "邀请码格式不正确")
    private String inviteCode;
}

package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * “加入我们”申请提交入参。applyType=job 时 position 必填；applyType=partner 时 companyName 必填。
 * 交叉字段校验在 Service 层完成，避免注解无法表达的条件分支。
 */
@Data
public class JoinApplyDTO {
    /** job 求职 / partner 渠道合作 */
    @NotBlank(message = "请选择申请类型")
    @Pattern(regexp = "^(job|partner)$", message = "申请类型不合法")
    private String applyType;

    /** 求职：应聘职位 */
    @Size(max = 64, message = "职位名称过长")
    private String position;

    /** 合作：公司/机构名称 */
    @Size(max = 128, message = "公司名称过长")
    private String companyName;

    @NotBlank(message = "请填写联系人姓名")
    @Size(min = 2, max = 32, message = "姓名长度需为 2-32 个字符")
    private String contactName;

    @NotBlank(message = "请填写联系电话")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "邮箱格式不正确")
    private String email;

    @Size(max = 64, message = "城市/区域过长")
    private String city;

    @Size(max = 32, message = "工作年限过长")
    private String experience;

    @Size(max = 1000, message = "说明不能超过 1000 个字符")
    private String message;
}

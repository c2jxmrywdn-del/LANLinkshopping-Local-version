package com.lanlink.shopping.module.techservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 专家 / 技术经纪人入驻申请（定向邀请后由平台审核上架） */
@Data
public class TechExpertApplyRequest {
    /** EXPERT 技术专家 / BROKER 技术经纪人 */
    private String expertType;
    @NotBlank(message = "请填写姓名")
    @Size(max = 40, message = "姓名不能超过 40 个字符")
    private String name;
    @Size(max = 60, message = "职称不能超过 60 个字符")
    private String title;
    @NotBlank(message = "请填写所属单位")
    @Size(max = 100, message = "单位名称不能超过 100 个字符")
    private String organization;
    @Size(max = 200, message = "行业标签不能超过 200 个字符")
    private String industries;
    @Size(max = 200, message = "擅长领域不能超过 200 个字符")
    private String domains;
    @Size(max = 1000, message = "个人简介不能超过 1000 个字符")
    private String bio;
    @Size(max = 500, message = "资质与成果不能超过 500 个字符")
    private String credentials;
    @DecimalMin(value = "0", message = "咨询费不能为负数")
    private BigDecimal consultFee;
}

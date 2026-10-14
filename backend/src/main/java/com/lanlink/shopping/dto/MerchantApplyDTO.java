package com.lanlink.shopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商户入驻申请
 */
@Data
public class MerchantApplyDTO {
    @NotBlank(message = "企业名称不能为空")
    private String entName;
    private String creditCode;
    @NotBlank(message = "注册类型不能为空")
    private String regType;          // 公司 / 个体工商户
    @NotNull(message = "注册资本不能为空")
    private BigDecimal regCapital;   // 单位: 元
    @NotNull(message = "请选择是否有稳定纳税记录")
    private Integer taxStatus;       // 1稳定 0无
    private String joinType = "入驻"; // 加盟/入驻/邀约
    private String qNames;           // 已具备资质名称,逗号分隔(可选)
    // 证照材料（营业执照/税务证明/税务登记号）由入驻审核通过后的商户
    // 通过 merchant:manage 权限保护的 /merchant/license、/merchant/tax-proof、/merchant/tax-query 上传绑定，
    // 申请阶段无需前置提交，此处仅作向后兼容的可选字段。
    private String licenseUrl;       // 营业执照图片URL(入驻后由商户补传)
    private List<String> taxProofUrls; // 近3个月税务缴纳证明URL(入驻后由商户补传)
    private String taxRegNo;         // 税务登记号(入驻后由商户绑定)
}

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
    private String joinCode;         // 平台邀约码 / 特约加盟码
    private String inviteCode;       // 平台邀约码别名
    private String franchiseCode;    // 特约加盟码别名
    private String qNames;           // 已具备资质名称,逗号分隔(可选)

    // ===== 商户重要敏感信息（入驻阶段提交，后端 AES-256 加密落库） =====
    private String legalPerson;      // 法人姓名
    private String legalIdCard;      // 法人身份证号
    private String bankAccount;      // 对公银行账号
    private String bankName;         // 开户银行
    private String contactPhone;     // 负责人联系电话
    private String contactEmail;     // 负责人联系邮箱
    private String businessAddress;  // 经营场所/注册地址

    // ===== 资质证照与纳税证明 =====
    private String licenseUrl;       // 营业执照图片URL
    private List<String> taxProofUrls; // 近3个月税务缴纳证明URL
    private String taxRegNo;         // 税务登记号

    // ===== 特殊经营许可证 =====
    private Boolean hasSpecialPermit; // 是否有特殊经营许可证
    private String specialPermitName; // 特殊经营许可证名称
    private String specialPermitUrl;  // 特殊经营许可证附件URL
}

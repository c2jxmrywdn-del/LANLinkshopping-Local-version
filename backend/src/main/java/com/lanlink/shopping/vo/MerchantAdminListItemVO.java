package com.lanlink.shopping.vo;

import com.lanlink.shopping.common.DesensitizeUtil;
import com.lanlink.shopping.entity.Merchant;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营端商户列表项（脱敏投影）。
 *
 * <p>历史缺陷：列表直接返回 {@code Merchant} 实体，而该实体带 {@code autoResultMap}
 * + {@code EncryptTypeHandler}，查询即解密——等于每次刷新列表都把全部商户的
 * 法人姓名、身份证号、对公账号、联系电话/邮箱、经营地址明文下发到浏览器，
 * 完全绕过「查看敏感信息需验证码 + 审计」的门禁。
 *
 * <p>列表只需要展示与筛选，因此这里只输出脱敏后的值；明文仍只能经
 * {@code /merchant/admin/sensitive/{merId}}（验证码 + MERCHANT_SENSITIVE_VIEW 审计）获取。
 */
@Data
public class MerchantAdminListItemVO {
    private Long merId;
    private Long entId;
    private Long userId;
    private String regType;
    private BigDecimal regCapital;
    private Integer taxStatus;
    private String joinType;
    private Integer reviewStatus;
    private String rejectReason;
    private String licenseUrl;
    private Integer hasSpecialPermit;
    private BigDecimal commissionRate;
    private String exitStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 敏感字段：仅脱敏值 =====
    private String legalPerson;
    private String legalIdCard;
    private String bankAccount;
    private String bankName;
    private String contactPhone;
    private String contactEmail;
    private String businessAddress;

    public static MerchantAdminListItemVO from(Merchant m) {
        MerchantAdminListItemVO vo = new MerchantAdminListItemVO();
        vo.setMerId(m.getMerId());
        vo.setEntId(m.getEntId());
        vo.setUserId(m.getUserId());
        vo.setRegType(m.getRegType());
        vo.setRegCapital(m.getRegCapital());
        vo.setTaxStatus(m.getTaxStatus());
        vo.setJoinType(m.getJoinType());
        vo.setReviewStatus(m.getReviewStatus());
        vo.setRejectReason(m.getRejectReason());
        vo.setLicenseUrl(m.getLicenseUrl());
        vo.setHasSpecialPermit(m.getHasSpecialPermit());
        vo.setCommissionRate(m.getCommissionRate());
        vo.setExitStatus(m.getExitStatus());
        vo.setCreateTime(m.getCreateTime());
        vo.setUpdateTime(m.getUpdateTime());
        vo.setLegalPerson(DesensitizeUtil.maskRealName(m.getLegalPerson()));
        vo.setLegalIdCard(DesensitizeUtil.maskIdCard(m.getLegalIdCard()));
        vo.setBankAccount(DesensitizeUtil.maskBankCard(m.getBankAccount()));
        vo.setBankName(m.getBankName() == null ? null
                : DesensitizeUtil.maskPiiInText(m.getBankName()));
        vo.setContactPhone(DesensitizeUtil.maskPhone(m.getContactPhone()));
        vo.setContactEmail(DesensitizeUtil.maskEmail(m.getContactEmail()));
        vo.setBusinessAddress(DesensitizeUtil.maskAddress(m.getBusinessAddress()));
        return vo;
    }
}

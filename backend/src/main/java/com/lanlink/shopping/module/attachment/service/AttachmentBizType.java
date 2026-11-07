package com.lanlink.shopping.module.attachment.service;

import com.lanlink.shopping.common.BusinessException;

/** 附件所属业务域：线名即入库值与对象键目录，两处必须同源，只允许这里定义。 */
public enum AttachmentBizType {

    MERCHANT_LICENSE("merchant-license"),
    MERCHANT_TAX_PROOF("merchant-tax-proof"),
    MERCHANT_SPECIAL_PERMIT("merchant-special-permit"),
    TECH_EXPERT_PROFILE("tech-expert-profile"),
    TECH_ORGANIZATION("tech-organization"),
    TECH_APPRAISAL_MATERIAL("tech-appraisal-material"),
    TECH_DEAL_DOCUMENT("tech-deal-document"),
    LOGISTICS_COA_DOC("logistics-coa-doc"),
    LOGISTICS_EPOD_RECEIPT("logistics-epod-receipt");

    private final String wireName;

    AttachmentBizType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** 严格匹配入库线名：大小写变体或未知值一律拒绝，避免同一业务域出现两套口径。 */
    public static AttachmentBizType fromWireName(String value) {
        for (AttachmentBizType type : values()) {
            if (type.wireName.equals(value)) {
                return type;
            }
        }
        throw new BusinessException(400, "不支持的附件业务域");
    }
}

package com.lanlink.shopping.dto.rma;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RmaApplyDTO {
    private String orderNo;
    /** refund_only 仅退款 / return_goods 退货退款 */
    private String rmaType = "return_goods";
    private BigDecimal applyAmount;
    private String reasonCategory = "other";
    @NotBlank(message = "请填写退款申请原因")
    private String applyReason;
    /** 证据 JSON 字符串 [{type,url,name}]，≥1 必填（统一必填拍板），权威校验在 RefundEvidenceValidator */
    @NotBlank(message = "请至少上传 1 项退款凭证")
    private String proofImages;
}

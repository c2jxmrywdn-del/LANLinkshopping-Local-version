package com.lanlink.shopping.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.math.BigDecimal;

/** 退款请求（管理端） */
@Data
public class RefundRequest {
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
    /** 退款金额（元），留空为全额退款 */
    private BigDecimal amount;
    /** 退款原因 */
    private String reason;
}

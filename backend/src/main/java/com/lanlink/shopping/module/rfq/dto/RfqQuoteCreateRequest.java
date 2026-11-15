package com.lanlink.shopping.module.rfq.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户报价。数量固定取询价单数量（不接受客户端传数量/总额），
 * 总额由后端按 unitPrice × rfq.quantity 计算，杜绝"低价报价、改总额"的篡改。
 */
@Data
public class RfqQuoteCreateRequest {

    @NotNull(message = "报价商品不能为空")
    private Long prodId;

    @NotNull(message = "报价单价不能为空")
    @DecimalMin(value = "0.01", message = "报价单价需大于 0")
    @Digits(integer = 12, fraction = 2, message = "报价单价格式不正确")
    private BigDecimal unitPrice;

    @Size(max = 500, message = "报价备注过长")
    private String remark;

    /** 报价有效期（可空；非空时必须晚于当前时间） */
    private LocalDateTime validUntil;
}
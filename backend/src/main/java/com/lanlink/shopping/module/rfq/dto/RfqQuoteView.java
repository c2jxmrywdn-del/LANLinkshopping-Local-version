package com.lanlink.shopping.module.rfq.dto;

import com.lanlink.shopping.module.rfq.entity.RfqQuote;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商户自己的报价视图（仅本人可见，竞对不可见） */
@Data
public class RfqQuoteView {
    private Long quoteId;
    private Long rfqId;
    private Long prodId;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal totalAmount;
    private String remark;
    private LocalDateTime validUntil;
    private String status;
    private LocalDateTime createTime;

    public static RfqQuoteView of(RfqQuote q) {
        RfqQuoteView v = new RfqQuoteView();
        v.setQuoteId(q.getQuoteId());
        v.setRfqId(q.getRfqId());
        v.setProdId(q.getProdId());
        v.setUnitPrice(q.getUnitPrice());
        v.setQuantity(q.getQuantity());
        v.setTotalAmount(q.getTotalAmount());
        v.setRemark(q.getRemark());
        v.setValidUntil(q.getValidUntil());
        v.setStatus(q.getStatus());
        v.setCreateTime(q.getCreateTime());
        return v;
    }
}
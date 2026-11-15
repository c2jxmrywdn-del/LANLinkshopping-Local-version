package com.lanlink.shopping.module.rfq.dto;

import com.lanlink.shopping.module.rfq.entity.Rfq;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 买家视角的询价单视图（不含任何商户报价金额；报价在 S4 的报价视图里单独暴露给本人） */
@Data
public class RfqView {
    private String rfqNo;
    private String title;
    private String description;
    private Long prodId;
    private BigDecimal quantity;
    private String unit;
    private LocalDateTime quoteDeadline;
    private Integer maxQuotes;
    private String status;
    private LocalDateTime createTime;

    public static RfqView of(Rfq r) {
        RfqView v = new RfqView();
        v.setRfqNo(r.getRfqNo());
        v.setTitle(r.getTitle());
        v.setDescription(r.getDescription());
        v.setProdId(r.getProdId());
        v.setQuantity(r.getQuantity());
        v.setUnit(r.getUnit());
        v.setQuoteDeadline(r.getQuoteDeadline());
        v.setMaxQuotes(r.getMaxQuotes());
        v.setStatus(r.getStatus());
        v.setCreateTime(r.getCreateTime());
        return v;
    }
}
package com.lanlink.shopping.module.rfq.dto;

import com.lanlink.shopping.module.rfq.entity.Rfq;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户视角的询价单：**不含买家身份与联系方式**（永不披露），也不含任何竞对报价。
 */
@Data
public class RfqMerchantView {
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

    public static RfqMerchantView of(Rfq r) {
        RfqMerchantView v = new RfqMerchantView();
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
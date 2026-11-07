package com.lanlink.shopping.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

public class LogisticsInvoiceDTO {

    @Data
    public static class InvoiceUploadDTO {
        private String invoiceNo;
        private String invoiceCode;
        private Long providerId;
        private BigDecimal totalAmount;
        private String sellerName;
        private String sellerTaxNo;
        private String buyerName;
        private String buyerTaxNo;
        private LocalDate invoiceDate;
        private String checkCode;
        private String fileUrl;
        private String invoiceType;
    }

    @Data
    public static class InvoiceBindDTO {
        private Long invoiceId;
        private Long settlementId;
    }
}

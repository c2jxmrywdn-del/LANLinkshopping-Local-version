package com.lanlink.shopping.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

public class LogisticsReconciliationDTO {

    @Data
    public static class ReconciliationImportDTO {
        private Long providerId;
        /** 账期周期 YYYY-MM */
        private String billPeriod;
        private String remark;
        private List<CarrierWaybillBillItem> items;
    }

    @Data
    public static class CarrierWaybillBillItem {
        private String waybillNo;
        private BigDecimal carrierBilledFreight;
        private BigDecimal carrierWeight;
        private String remark;
    }

    @Data
    public static class DisputeResolveDTO {
        private Long itemId;
        private BigDecimal adjustmentAmount;
        private String adjustNote;
    }
}

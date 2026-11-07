package com.lanlink.shopping.dto.rma;

import lombok.Data;

@Data
public class MerchantRmaApproveDTO {
    private String receiverName;
    private String receiverPhone;
    private String warehouseAddress;
    private String auditNote;
}
